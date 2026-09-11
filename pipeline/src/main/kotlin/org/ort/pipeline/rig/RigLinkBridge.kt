package org.ort.pipeline.rig

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
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

/** One bonded (paired) Bluetooth device as the setup UI's rig-pairing picker needs it (FR-RIG-14,
 * S10b) — the same shape [org.ort.rig.bluetooth.PairedBluetoothDevice] carries, renamed into this
 * package so `:app` (WPD) never needs to import `:rig-bluetooth` directly (constitution VII: the
 * setup UI may not depend on `:rig-usb`/`:rig-bluetooth`, only on this bridge). */
public data class PairedRigDevice(
    public val name: String?,
    public val address: String,
    public val sppSupport: SppSupport,
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
 */
public sealed interface RigLinkProbeState {
    public data object Opening : RigLinkProbeState
    public data object Open : RigLinkProbeState
    public data class Identified(public val rigId: String) : RigLinkProbeState
    public data class Verified(public val capabilities: Set<RigCapability>) : RigLinkProbeState
    public data class Lost(public val reason: String) : RigLinkProbeState
    public data object NoPermission : RigLinkProbeState
    public data class Failed(public val reason: String) : RigLinkProbeState
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
) : RigLinkBridge {

    override fun pairedDevices(): PairedRigDevicesResult {
        val link = AndroidBluetoothLink(context)
        if (!link.hasConnectPermission()) return PairedRigDevicesResult(emptyList(), permissionGranted = false)
        val devices = BluetoothSppTransport.pairedDevices(link).map {
            PairedRigDevice(name = it.name, address = it.address, sppSupport = it.advertisesSpp)
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
        // close(), called from whichever of these two jobs reaches a terminal state first) -- a
        // benign race between two independent collectors of the same one-shot module, never a
        // real error.
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
        val observeJob = launch {
            module.observe().collect { state ->
                if (!identified) {
                    identified = true
                    sendUnlessClosed(RigLinkProbeState.Identified(rigId))
                    if (declaredCapabilities.isEmpty()) {
                        sendUnlessClosed(RigLinkProbeState.Verified(declaredCapabilities))
                        close()
                        return@collect
                    }
                }
                seenCapabilities += capabilitiesPresentIn(state)
                if (seenCapabilities.containsAll(declaredCapabilities)) {
                    sendUnlessClosed(RigLinkProbeState.Verified(declaredCapabilities))
                    close()
                }
            }
        }

        awaitClose {
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
}
