package org.ort.pipeline.rig

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.ort.pipeline.capture.CaptureState
import org.ort.rig.RigCapability
import org.ort.rig.RigHealth
import org.ort.rig.RigHealthIssue
import org.ort.rig.RigState
import org.ort.rig.RigTransportKind
import org.ort.rig.bluetooth.AndroidBluetoothLink
import org.ort.rig.bluetooth.BluetoothSppTransport
import org.ort.rig.bluetooth.SppSupport
import org.ort.rig.descriptor.DescriptorRigModule
import org.ort.rig.descriptor.RigDescriptor
import org.ort.rig.usb.UsbSerialTransport

/**
 * Whether a bonded device advertises the SPP UUID, expressed at this bridge's own boundary — the
 * `:rig-bluetooth` [SppSupport] this is mapped from ([DefaultRigLinkBridge.pairedDevices]) is not
 * on `:app`'s classpath at all (`ModuleGraph`'s `allowed[":app"]` names `:pipeline`, `:rig`,
 * `:core`, never `:rig-bluetooth`/`:rig-usb` — constitution VII), and `pipeline/build.gradle.kts`
 * deliberately declares `:rig-bluetooth` `implementation`, not `api`, so it must not leak
 * transitively through a public return type either. Found live: before this type existed,
 * [PairedRigDevice.sppSupport] was declared as the `:rig-bluetooth` type directly, and the one
 * real consumer (`:app`'s `BridgeRigLinkPort`) could not even `import` it to write a `when` —
 * confirmed by reading that class's own reflection workaround, now obsolete (see this bridge's
 * report). [UNKNOWN] is deliberate here too, for the same reason [SppSupport.UNKNOWN] is: some
 * Android stacks report no UUIDs at all for a bonded device until it has connected once, and
 * reporting that as [NO] would be a guess (constitution I).
 */
public enum class RigLinkSppSupport { YES, NO, UNKNOWN }

/** One bonded (paired) Bluetooth device as the setup UI's rig-pairing picker needs it (FR-RIG-14,
 * S10b) — the same shape [org.ort.rig.bluetooth.PairedBluetoothDevice] carries, renamed into this
 * package so `:app` (WPD) never needs to import `:rig-bluetooth` directly (constitution VII: the
 * setup UI may not depend on `:rig-usb`/`:rig-bluetooth`, only on this bridge). [sppSupport] is
 * [RigLinkSppSupport] — this bridge's own type, not `:rig-bluetooth`'s [SppSupport] — see
 * [RigLinkSppSupport]'s own kdoc for why. */
public data class PairedRigDevice(
    public val name: String?,
    public val address: String,
    public val sppSupport: RigLinkSppSupport,
)

/**
 * FR-PLT-2's discipline applied to pairing (WPC3): an empty [devices] list is ambiguous on its
 * own — "nothing is paired" and "`BLUETOOTH_CONNECT` is absent, so nothing CAN be listed" look
 * identical unless [permissionGranted] is carried alongside it (constitution I — an empty result
 * without its own reason is exactly the kind of silent gap this repository's failures are made
 * of). WPD's S10b renders its `Pair in system settings` prompt from [permissionGranted], never by
 * inferring absence-of-permission from an empty list.
 */
public data class PairedRigDevicesResult(
    public val devices: List<PairedRigDevice>,
    public val permissionGranted: Boolean,
)

/**
 * One state in a [RigLinkBridge.probe] run, in the order a healthy connection passes through them
 * (FR-RIG-3/14, S10b's "open → identify → verify" checklist, E2-E10):
 *
 * [Opening] → [Open] (the transport itself connected) → [Identified] (the descriptor's own
 * protocol produced a first real [RigState] — proof this rig actually speaks it, not just that a
 * socket opened) → [Verified] (every capability the descriptor declares for this transport has
 * now been observed at least once — proof the link is not just alive but functionally complete).
 *
 * [Lost] and [NoPermission] can interrupt the sequence at any point after [Open] — mirrored
 * straight from the transport's own [org.ort.rig.TransportState.Lost], never invented here.
 * [Failed] covers everything that never gets as far as opening a transport at all (an unknown rig
 * id, a rig/transport combination the descriptor does not declare, or capture already running).
 *
 * [IdentifyTimedOut] and [VerifyTimedOut] (R-1013/R-1014) are the two other ways [Open] can end:
 * the transport genuinely opened and stayed open, but the descriptor's own identify/verify
 * sequence never completed within a bounded wait. Neither collapses into [Failed] or [Lost] —
 * constitution I: "a log that silently guesses is worse than one that admits it does not know",
 * and each names a different fact an operator would act on differently. [Lost] means the
 * transport itself reported the link gone; [IdentifyTimedOut] means the link is still open but
 * nothing ever spoke; [VerifyTimedOut] means the rig *did* speak — [Identified] genuinely
 * happened — but never finished reporting every capability the descriptor declares. A probe run
 * always reaches exactly one of [Verified], [Lost], [NoPermission], [Failed], [IdentifyTimedOut]
 * or [VerifyTimedOut] — never sits at [Open] forever (constitution IV: capture-adjacent surfaces
 * must never lie about work still being in progress once it has, in fact, concluded).
 */
public sealed interface RigLinkProbeState {
    public data object Opening : RigLinkProbeState
    public data object Open : RigLinkProbeState
    public data class Identified(public val rigId: String) : RigLinkProbeState
    public data class Verified(public val capabilities: Set<RigCapability>) : RigLinkProbeState
    public data class Lost(public val reason: String) : RigLinkProbeState
    public data object NoPermission : RigLinkProbeState
    public data class Failed(public val reason: String) : RigLinkProbeState

    /**
     * R-1013: [Open] was reached — the transport itself connected — but the descriptor's identify
     * sequence never produced a single [RigState] (no line ever matched one of its patterns)
     * within [timeoutMillis] of opening. This is the "wrong framing, unexpected line terminator, a
     * radio mode that needs setup before it will talk CAT, or simply not the rig the descriptor
     * expects" case: the link is not lost (no [org.ort.rig.TransportState.Lost] was ever reported)
     * and the rig has told this bridge nothing at all, ever — a fact the operator needs distinct
     * from "the transport dropped" ([Lost]) and from "it spoke but not enough" ([VerifyTimedOut]).
     * [timeoutMillis] is carried so the UI can state the bound that was actually applied rather
     * than a number the caller has to already know.
     */
    public data class IdentifyTimedOut(public val rigId: String, public val timeoutMillis: Long) : RigLinkProbeState

    /**
     * R-1014: [Identified] was reached — the rig genuinely answered and this bridge parsed at
     * least one line from it — but [seenCapabilities] never grew to cover every capability
     * [declaredCapabilities] the descriptor declares, within [timeoutMillis] of identifying. This
     * is **partial success, not failure**: the rig is there and speaking the protocol; it simply
     * never reported every field this transport promises in the time allowed (a band with no
     * signal reports no `SIGNAL_STRENGTH`; a VFO with no memory channel reports no
     * `MEMORY_CHANNEL`; a descriptor that declares `TIME` can never complete at all — see
     * [capabilitiesPresentIn]'s own kdoc). Carrying [seenCapabilities] lets the caller say
     * "identified, N of M capabilities seen" instead of reporting nothing, which is exactly what
     * constitution I requires of a machine conclusion that stops short of certainty.
     */
    public data class VerifyTimedOut(
        public val rigId: String,
        public val seenCapabilities: Set<RigCapability>,
        public val declaredCapabilities: Set<RigCapability>,
        public val timeoutMillis: Long,
    ) : RigLinkProbeState
}

/**
 * WPC3's bridge so the setup UI (`:app`, WPD) can list paired Bluetooth devices and run a one-shot
 * "does this rig actually respond" probe without ever depending on `:rig-usb`/`:rig-bluetooth`
 * directly (constitution VII — `ModuleGraph`'s rule already forbids `:app -> :rig-bluetooth`).
 * [RigSupervisor] stays the *session-lifetime* rig connection capture itself uses; this is the
 * short-lived, throwaway connection the picker uses to prove a choice works before capture ever
 * starts.
 */
public interface RigLinkBridge {
    /** Bonded devices for the SPP picker (FR-RIG-14) — see [PairedRigDevicesResult]'s own kdoc for
     * why permission is a first-class field here rather than an inferred fact. */
    public fun pairedDevices(): PairedRigDevicesResult

    /**
     * One-shot: opens [transportKind] for [rigId] with [params], runs the descriptor's own
     * identify/verify sequence (see [RigLinkProbeState]'s own kdoc), and closes the transport the
     * moment the returned [Flow] is cancelled or reaches [RigLinkProbeState.Verified]/
     * [RigLinkProbeState.Lost]/[RigLinkProbeState.NoPermission]/[RigLinkProbeState.Failed] — never
     * left open after the caller stops collecting.
     *
     * Refuses with [RigLinkProbeState.Failed] rather than opening anything at all while
     * [CaptureState.isCapturing] — a live session's [RigSupervisor] already holds that rig's
     * transport, and probing concurrently would race it for the same USB/Bluetooth link.
     */
    public fun probe(
        rigId: String,
        transportKind: RigTransportKind,
        params: Map<String, String>,
    ): Flow<RigLinkProbeState>
}

/**
 * Production [RigLinkBridge]: [pairedDevices] over a fresh [AndroidBluetoothLink]; [probe] over
 * [transportFactory] (default [DefaultRigTransportFactory]) and [catalogue] (default
 * [bundledDescriptorById] — the same lookup [RigSupervisor] itself uses, so a `rigId` this bridge
 * resolves is exactly one a live session would too). [moduleScope] is the
 * [org.ort.rig.descriptor.DescriptorRigModule] scope [probe] builds its one-shot module on —
 * exposed (rather than hardcoded) so a test can give it a dedicated dispatcher, isolated from
 * whatever else the same JVM's shared `Dispatchers.Default` pool is doing (this class's own test
 * suite does exactly that for its two real-transport scenarios).
 */
public class DefaultRigLinkBridge(
    private val context: Context,
    private val transportFactory: RigTransportFactory = DefaultRigTransportFactory(context),
    private val catalogue: (String) -> RigDescriptor? = ::bundledDescriptorById,
    private val moduleScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /** R-1013: how long [probe] waits from [RigLinkProbeState.Open] for the first [RigState] at
     * all before concluding [RigLinkProbeState.IdentifyTimedOut] — a function of the descriptor
     * being probed, not a fixed constant (see [defaultIdentifyTimeoutMillis]'s own kdoc for the
     * derivation), and overridable here so a test can substitute a tiny, explicit bound instead of
     * relying on the default's magnitude. Tests still never sleep in real time regardless of the
     * value chosen — the `delay` this drives runs on whatever dispatcher collects the returned
     * [Flow], so [kotlinx.coroutines.test.TestScope]'s virtual clock resolves it instantly. */
    private val identifyTimeoutMillisFor: (RigDescriptor) -> Long = Companion::defaultIdentifyTimeoutMillis,
    /** R-1014: the same idea as [identifyTimeoutMillisFor], applied to bounding [RigLinkProbeState
     * .Identified] -> [RigLinkProbeState.Verified] before concluding [RigLinkProbeState
     * .VerifyTimedOut] — see [defaultVerifyTimeoutMillis]'s own kdoc. */
    private val verifyTimeoutMillisFor: (RigDescriptor) -> Long = Companion::defaultVerifyTimeoutMillis,
) : RigLinkBridge {

    override fun pairedDevices(): PairedRigDevicesResult {
        val link = AndroidBluetoothLink(context)
        if (!link.hasConnectPermission()) return PairedRigDevicesResult(emptyList(), permissionGranted = false)
        val devices = BluetoothSppTransport.pairedDevices(link).map {
            PairedRigDevice(name = it.name, address = it.address, sppSupport = toRigLinkSppSupport(it.advertisesSpp))
        }
        return PairedRigDevicesResult(devices, permissionGranted = true)
    }

    override fun probe(
        rigId: String,
        transportKind: RigTransportKind,
        params: Map<String, String>,
    ): Flow<RigLinkProbeState> = channelFlow {
        send(RigLinkProbeState.Opening)

        if (CaptureState.isCapturing) {
            send(RigLinkProbeState.Failed("capture is running; the rig link cannot be probed while it is in use"))
            return@channelFlow
        }

        val descriptor = catalogue(rigId)
        if (descriptor == null) {
            send(RigLinkProbeState.Failed("no descriptor named '$rigId' for transport $transportKind"))
            return@channelFlow
        }

        val module = DescriptorRigModule(descriptor, transportFactory::create, scope = moduleScope)
        if (transportKind !in module.transports) {
            send(RigLinkProbeState.Failed("'$rigId' has no $transportKind transport declared"))
            return@channelFlow
        }

        val connectResult = module.connect(transportKind, params)
        if (connectResult.isFailure) {
            send(RigLinkProbeState.Failed(connectResult.exceptionOrNull()?.message ?: "connect failed"))
            return@channelFlow
        }
        send(RigLinkProbeState.Open)

        val declaredCapabilities = module.capabilities(transportKind)
        val seenCapabilities = mutableSetOf<RigCapability>()
        var identified = false

        // sendUnlessClosed swallows a send racing an already-closed channel (this producer's own
        // close(), called from whichever of these jobs reaches a terminal state first) -- a
        // benign race between independent collectors of the same one-shot module, never a real
        // error.
        val healthJob = launch {
            module.health().collect { health ->
                if (health !is RigHealth.Degraded || health.issue != RigHealthIssue.TRANSPORT_LOST) return@collect
                val terminal = if (health.detail in NO_PERMISSION_REASONS) {
                    RigLinkProbeState.NoPermission
                } else {
                    RigLinkProbeState.Lost(health.detail ?: "transport lost")
                }
                sendUnlessClosed(terminal)
                close()
            }
        }

        // R-1013: bounds Open -> Identified; R-1014: bounds Identified -> Verified once
        // identification actually happens. See each launcher's own kdoc for why neither can be
        // driven by module.health() alone -- a merely-silent (not lost) transport never produces
        // RigHealth.Degraded(TRANSPORT_LOST) at all (see this bridge's own report on what
        // DescriptorRigModule's read loop does instead: it retries forever).
        val identifyTimeoutMillis = identifyTimeoutMillisFor(descriptor)
        val identifyTimeoutJob = launchIdentifyTimeoutJob(rigId, identifyTimeoutMillis)
        var verifyTimeoutJob: Job? = null

        val observeJob = launch {
            module.observe().collect { state ->
                if (!identified) {
                    identified = true
                    identifyTimeoutJob.cancel()
                    sendUnlessClosed(RigLinkProbeState.Identified(rigId))
                    if (declaredCapabilities.isEmpty()) {
                        sendUnlessClosed(RigLinkProbeState.Verified(declaredCapabilities))
                        close()
                        return@collect
                    }
                    verifyTimeoutJob = launchVerifyTimeoutJob(
                        rigId,
                        seenCapabilitiesSoFar = { seenCapabilities.toSet() },
                        declaredCapabilities,
                        verifyTimeoutMillisFor(descriptor),
                    )
                }
                seenCapabilities += capabilitiesPresentIn(state)
                if (seenCapabilities.containsAll(declaredCapabilities)) {
                    verifyTimeoutJob?.cancel()
                    sendUnlessClosed(RigLinkProbeState.Verified(declaredCapabilities))
                    close()
                }
            }
        }

        awaitClose {
            identifyTimeoutJob.cancel()
            verifyTimeoutJob?.cancel()
            healthJob.cancel()
            observeJob.cancel()
            module.disconnect()
        }
    }

    private companion object {
        /** Both transports' own [Reason] constants for "the platform refused this connection for
         * a permission reason" — named here rather than imported as a shared constant, since
         * `:rig-usb`/`:rig-bluetooth` intentionally share no common module below `:rig` itself. */
        val NO_PERMISSION_REASONS: Set<String> = setOf(
            BluetoothSppTransport.Reason.NO_PERMISSION,
            UsbSerialTransport.Reason.PERMISSION_DENIED,
            UsbSerialTransport.Reason.PERMISSION_LOST,
        )

        /** The one place `:rig-bluetooth`'s [SppSupport] is named at all in this file — everything
         * past this function's return sees only [RigLinkSppSupport] (see that type's own kdoc). An
         * exhaustive `when`, not an `else`, so a future [SppSupport] value fails this file's own
         * compile rather than silently mapping to [RigLinkSppSupport.UNKNOWN]. */
        fun toRigLinkSppSupport(support: SppSupport): RigLinkSppSupport = when (support) {
            SppSupport.YES -> RigLinkSppSupport.YES
            SppSupport.NO -> RigLinkSppSupport.NO
            SppSupport.UNKNOWN -> RigLinkSppSupport.UNKNOWN
        }

        /** R-1013: multiplies a descriptor's own [org.ort.rig.descriptor.PollSpec.intervalMs] to
         * bound how long [probe] waits from [RigLinkProbeState.Open] for the identify sequence to
         * produce a first [RigState] at all — derived from the descriptor's own declared cadence,
         * not a hardcoded duration, because a rig that polls every two seconds and a rig that polls
         * ten times a second have genuinely different silences worth tolerating before concluding
         * the radio is not answering. Three cycles gives a full extra cycle of margin for a reply
         * that lands just after this bridge started waiting. */
        const val IDENTIFY_POLL_CYCLES = 3L

        /** Same reasoning as [IDENTIFY_POLL_CYCLES], applied to bounding [RigLinkProbeState
         * .Identified] -> [RigLinkProbeState.Verified]: verification needs every declared
         * capability to have appeared at least once, which — unlike identification, satisfied by a
         * single matched line — can genuinely take several poll cycles for a multi-field, per-band
         * descriptor. Doubled against [IDENTIFY_POLL_CYCLES] for that stated reason, not picked to
         * make any one test pass. */
        const val VERIFY_POLL_CYCLES = 6L

        /** Floor for a descriptor with no [RigDescriptor.poll] at all (an unsolicited-push-only
         * rig — including this file's own [FakeRigLinkBridge]-adjacent test descriptors) — there is
         * no cadence to derive a multiple of, so this names a fixed ceiling for "nothing arrived on
         * its own" instead of refusing to bound the wait at all. */
        const val NO_POLL_TIMEOUT_MILLIS = 5_000L

        /** Floor applied to any derived timeout, so a descriptor with an unrealistically fast poll
         * cadence (a descriptor error, or a future rig with a genuine sub-second cadence) still
         * gets a wait worth calling a timeout rather than one that fires before a reply could ever
         * physically arrive. */
        const val MIN_TIMEOUT_MILLIS = 2_000L

        /** See [identifyTimeoutMillisFor]'s own kdoc for why this is overridable at all; this is
         * the production default it falls back to. */
        fun defaultIdentifyTimeoutMillis(descriptor: RigDescriptor): Long = descriptor.poll?.intervalMs
            ?.let { interval -> (interval * IDENTIFY_POLL_CYCLES).coerceAtLeast(MIN_TIMEOUT_MILLIS) }
            ?: NO_POLL_TIMEOUT_MILLIS

        /** See [verifyTimeoutMillisFor]'s own kdoc for why this is overridable at all; this is the
         * production default it falls back to. */
        fun defaultVerifyTimeoutMillis(descriptor: RigDescriptor): Long = descriptor.poll?.intervalMs
            ?.let { interval -> (interval * VERIFY_POLL_CYCLES).coerceAtLeast(MIN_TIMEOUT_MILLIS) }
            ?: NO_POLL_TIMEOUT_MILLIS
    }
}

/** Sends [state], silently doing nothing if the channel is already closed — [DefaultRigLinkBridge
 * .probe]'s two independent collectors ([RigHealth] and [RigState]) can each reach a terminal
 * state and call `close()` in either order; the loser's own pending send racing that close is
 * expected, not an error, so it is swallowed here rather than propagated into the collector's own
 * coroutine (which would otherwise cancel `observeJob`/`healthJob` with an unhandled exception). */
private suspend fun ProducerScope<RigLinkProbeState>.sendUnlessClosed(state: RigLinkProbeState) {
    try {
        send(state)
    } catch (e: ClosedSendChannelException) {
        // Already closed by the other collector -- see this function's own kdoc.
    }
}

/** R-1013: the job [DefaultRigLinkBridge.probe] races against reaching [RigLinkProbeState
 * .Identified] -- the caller cancels it the instant identification actually happens; otherwise it
 * alone ends the wait after [timeoutMillis], since nothing else in [probe] ever will (see that
 * function's own kdoc). */
private fun ProducerScope<RigLinkProbeState>.launchIdentifyTimeoutJob(rigId: String, timeoutMillis: Long): Job =
    launch {
        delay(timeoutMillis)
        sendUnlessClosed(RigLinkProbeState.IdentifyTimedOut(rigId, timeoutMillis))
        close()
    }

/** R-1014: the same idea as [launchIdentifyTimeoutJob], for [RigLinkProbeState.Verified] -- started
 * only once [RigLinkProbeState.Identified] actually happens and cancelled the instant every
 * declared capability is seen. [seenCapabilitiesSoFar] is read lazily, at the moment this actually
 * fires, rather than passed as a value captured when the job was launched -- the caller's mutable
 * set keeps growing for as long as more lines arrive, and [RigLinkProbeState.VerifyTimedOut] must
 * report what was seen by the time this fired, not what had been seen when it started waiting. */
private fun ProducerScope<RigLinkProbeState>.launchVerifyTimeoutJob(
    rigId: String,
    seenCapabilitiesSoFar: () -> Set<RigCapability>,
    declaredCapabilities: Set<RigCapability>,
    timeoutMillis: Long,
): Job = launch {
    delay(timeoutMillis)
    sendUnlessClosed(
        RigLinkProbeState.VerifyTimedOut(
            rigId = rigId,
            seenCapabilities = seenCapabilitiesSoFar(),
            declaredCapabilities = declaredCapabilities,
            timeoutMillis = timeoutMillis,
        ),
    )
    close()
}

/** [RigState]'s own fields, translated to the [RigCapability] each one derives (WPC3) — the same
 * mapping [org.ort.rig.descriptor.DescriptorValidator]'s internal `FIELD_TO_CAPABILITY` encodes on
 * the descriptor side, duplicated here rather than shared because that table is `internal` to
 * `:rig` and this is the observed-state side of the same fact, not the declared-schema side.
 * [RigCapability.TIME] has no corresponding [RigState] field today (`:rig`'s own gap, not this
 * bridge's) and is therefore never derivable here — a descriptor that declares only `TIME` would
 * never reach [RigLinkProbeState.Verified], which is an honest reflection of that gap rather than
 * a defect introduced by this function. */
internal fun capabilitiesPresentIn(state: RigState): Set<RigCapability> = buildSet {
    if (state.frequencyHz != null) add(RigCapability.FREQUENCY)
    if (state.mode != null) add(RigCapability.MODE)
    if (state.squelchOpen != null) add(RigCapability.SQUELCH_STATE)
    if (state.signalStrength != null) add(RigCapability.SIGNAL_STRENGTH)
    if (state.memoryChannel != null) add(RigCapability.MEMORY_CHANNEL)
    if (state.channelName != null) add(RigCapability.CHANNEL_NAME)
    if (state.band != null) add(RigCapability.SUB_BAND)
    if (state.position != null) add(RigCapability.POSITION)
}

/**
 * Test-only seam so [RigLinkBridge]'s callers (WPD's setup UI) can be written and tested before
 * any real device exists — never a stub: every state [RigLinkProbeState] declares is reachable by
 * scripting, exactly the discipline constitution II requires of a fake.
 */
public class FakeRigLinkBridge(
    private var pairedDevicesResult: PairedRigDevicesResult =
        PairedRigDevicesResult(emptyList(), permissionGranted = true),
    private val probeFlows: MutableMap<String, Flow<RigLinkProbeState>> = mutableMapOf(),
) : RigLinkBridge {

    public fun setPairedDevices(result: PairedRigDevicesResult) {
        pairedDevicesResult = result
    }

    /** Scripts the exact [Flow] returned for [rigId]/[transportKind] — the caller builds it (a
     * plain `flowOf(...)`, or a `channelFlow` scripting a drop mid-sequence) so every scenario this
     * class's own tests need is expressible without this fake reimplementing the real state
     * machine. */
    public fun scriptProbe(rigId: String, transportKind: RigTransportKind, flow: Flow<RigLinkProbeState>) {
        probeFlows[key(rigId, transportKind)] = flow
    }

    override fun pairedDevices(): PairedRigDevicesResult = pairedDevicesResult

    override fun probe(
        rigId: String,
        transportKind: RigTransportKind,
        params: Map<String, String>,
    ): Flow<RigLinkProbeState> {
        val scripted = probeFlows[key(rigId, transportKind)]
        if (scripted != null) return scripted
        val message = "no probe scripted for '$rigId' over $transportKind"
        return kotlinx.coroutines.flow.flowOf(RigLinkProbeState.Failed(message))
    }

    private fun key(rigId: String, transportKind: RigTransportKind): String = "$rigId/$transportKind"

    public companion object {
        /**
         * R-1013, scriptable directly: a probe that reaches [RigLinkProbeState.Open] and then
         * never emits again — the exact defect this package fixes with a bounded timeout in
         * [DefaultRigLinkBridge]. A caller proving that ITS OWN caller (WPD's setup UI) survives a
         * hang scripts this via [scriptProbe] rather than reimplementing a hanging transport —
         * constitution II: "a fake that cannot be told to fail, hang or return a hallucination is
         * a stub." [awaitCancellation] rather than a long `delay`, since the point is to hang until
         * cancelled, not to eventually resolve on its own — a caller that itself applies no bound
         * (the pre-fix defect exactly) hangs forever, precisely as intended here.
         */
        public fun hangingAfterOpen(): Flow<RigLinkProbeState> = flow {
            emit(RigLinkProbeState.Opening)
            emit(RigLinkProbeState.Open)
            awaitCancellation()
        }
    }
}
