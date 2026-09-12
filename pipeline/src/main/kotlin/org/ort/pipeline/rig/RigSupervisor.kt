package org.ort.pipeline.rig

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.ort.core.Clock
import org.ort.core.SystemClock
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.capture.RigVerification
import org.ort.pipeline.diagnostics.DiagnosticsLog
import org.ort.pipeline.reconnectLadderPositionAt
import org.ort.rig.NullRigModule
import org.ort.rig.RigBand
import org.ort.rig.RigCapability
import org.ort.rig.RigHealth
import org.ort.rig.RigHealthIssue
import org.ort.rig.RigModule
import org.ort.rig.RigState
import org.ort.rig.RigStateConfidence
import org.ort.rig.RigTransportKind
import org.ort.rig.bluetooth.BluetoothReconnectBackoff
import org.ort.rig.descriptor.BundledDescriptors
import org.ort.rig.descriptor.DescriptorRigModule
import org.ort.rig.descriptor.RigDescriptor
import org.ort.rig.usb.UsbReconnectBackoff

/**
 * FR-RIG-9's closed set, as read by [RigSupervisor.frequencyForTransmission]. `inherited` and
 * `voice` are Pass D's (FR-RIG-10, out of this package's scope) — never produced here.
 */
public object FrequencyProvenance {
    public const val RIG: String = "rig"
    public const val MANUAL: String = "manual"
    public const val UNKNOWN: String = "unknown"
}

/**
 * FR-RIG-9: a frequency reading for one transmission, always carrying its provenance — "an
 * attribution without its confidence state is a bug" (constitution I) applies just as much here.
 * [changedDuringTransmission] is FR-RIG-6's flag: the rig reported a different value before the
 * transmission ended than it had at the start.
 */
public data class FrequencyReading(
    public val frequencyHz: Long?,
    public val provenance: String,
    public val changedDuringTransmission: Boolean = false,
) {
    public companion object {
        public val UNKNOWN: FrequencyReading = FrequencyReading(null, FrequencyProvenance.UNKNOWN)
    }
}

/** D23 (WPC3): the outcome of [RigSupervisor.bandAtTransmissionStart] — see that method's own kdoc
 * for the exact rule. [ambiguous] is true only for the "both bands open" case. */
public data class BandAtStart(public val band: RigBand?, public val ambiguous: Boolean) {
    public companion object {
        public val NONE: BandAtStart = BandAtStart(band = null, ambiguous = false)
    }
}

/** Bundled descriptors this package knows how to resolve a [CaptureConfiguration.rigId] against,
 * without depending on `:app`'s [org.ort.rig.catalogue.RigCatalogue] import flow (that catalogue
 * lives above this package — WPD/WPE own the picker; this only needs to rebuild the same module a
 * chosen catalogue entry names). A `rigId` this function does not recognise (a descriptor imported
 * through the catalogue, FR-RIG-19) is out of this package's scope for M-this-wave — see this
 * package's report — and degrades to the null module exactly as an invalid descriptor would
 * (FR-RIG-11), never blocking capture. */
public fun bundledDescriptorById(rigId: String): RigDescriptor? = when (rigId) {
    "kenwood-thd75a" -> BundledDescriptors.kenwoodThD75a()
    "generic-ascii-cat" -> BundledDescriptors.genericAsciiCat()
    else -> null
}

/**
 * WPC2 (FR-RIG-6/7/8/9/13/15, E2-B09's session half, E2-D08): builds the [RigModule] a session's
 * [CaptureConfiguration] names, over a [org.ort.rig.RigTransport] from [transportFactory], and
 * republishes [RigStatus] — `Connected`/`Stale`/`Absent`, carrying the transport kind and
 * descriptor id so the UI never re-derives either (E2-B09).
 *
 * **A control (rig) drop produces [RigStatus.State.Stale] and never a
 * [org.ort.data.entity.CaptureGapEntity] row** (FR-RIG-15, E2-D08). This class never references
 * `GapPersister`/`GapTracker`/`OrtDatabase` at all — the two failure domains (the audio route, the
 * rig-control link) are kept structurally separate, exactly as FR-RIG-13 requires the two axes to
 * be independent. [org.ort.pipeline.capture.RealCaptureService] is the only caller that could ever
 * connect the two, and it does not.
 *
 * **Reconnection is the transport's own responsibility, not this class's.** `UsbSerialTransport`
 * and `BluetoothSppTransport` (WPB) each run their own internal supervisor loop on the same
 * backoff-ladder policy `:capture-android`'s `AudioRecordSource` uses for the audio route
 * (`UsbReconnectBackoff`/`BluetoothReconnectBackoff` — duplicated rather than shared, since
 * `:rig-usb`/`:rig-bluetooth` may not depend on `:capture-android`), retrying forever from the one
 * [org.ort.rig.RigTransport.open] call [connect] makes — see each class's own kdoc. This class
 * therefore calls the [transportFactory] and connects **once** per session and never re-invokes
 * either; a `RigTransport` whose kind has no such self-healing (`:rig`'s own bare
 * `FakeRigTransport`, built for scripting failure modes, not for modelling a full reconnect state
 * machine) simply stays [RigStatus.State.Stale] until an external caller reopens it — an honest
 * reflection of what that transport actually does, not a gap this class papers over.
 */
public class RigSupervisor(
    private val transportFactory: RigTransportFactory,
    private val scope: CoroutineScope,
    private val clock: Clock = SystemClock,
    private val descriptorForId: (String) -> RigDescriptor? = ::bundledDescriptorById,
    /**
     * R-1015 (WPRIG2): how long this class tolerates [DescriptorRigModule.health] reporting
     * nothing but [RigHealth.Degraded] with [RigHealthIssue.TIMEOUT] — the module's own read loop
     * retrying forever, a signal nothing here read before this fix (see this class's own report:
     * [org.ort.pipeline.rig.DefaultRigLinkBridge]'s setup-time probe had exactly the same
     * unread-signal hole, R-1013, fixed the same morning) — before it declares [RigStatus] stale
     * on its own initiative. Derived from the descriptor's own poll cadence, the identical
     * reasoning [DefaultRigLinkBridge]'s `identifyTimeoutMillisFor`/`verifyTimeoutMillisFor` apply
     * at setup time: a rig polled every two seconds and one polled every ten times a second have
     * genuinely different silences worth tolerating — see [defaultHealthStaleTimeoutMillis]'s own
     * kdoc for the production default. Overridable here so a test can substitute a tiny, explicit
     * bound. Tests never sleep in real time regardless of the value chosen: the `delay` this
     * drives runs on whatever dispatcher [scope] uses, so [kotlinx.coroutines.test.TestScope]'s
     * virtual clock resolves it instantly.
     */
    private val healthStaleTimeoutMillisFor: (RigDescriptor) -> Long = Companion::defaultHealthStaleTimeoutMillis,
) {
    private var module: RigModule = NullRigModule()
    private var descriptorRigModule: DescriptorRigModule? = null
    private var activeTransportKind: RigTransportKind = RigTransportKind.NONE
    private var activeDescriptorId: String = NullRigModule.ID

    private var observeJob: Job? = null

    /** R-1015: watches [DescriptorRigModule.health] for the whole life of one [connect] —
     * [onRigState] alone (driven by [DescriptorRigModule.observe]) never fires again once the rig
     * falls silent without the transport itself reporting loss, which is exactly the hole this
     * closes. */
    private var healthWatchJob: Job? = null

    private val perBandState = java.util.Collections.synchronizedMap(LinkedHashMap<RigBand?, RigState>())

    /** R-1019: every [org.ort.rig.RigCapability] observed at least once this connection, across
     * every band — reset only on a fresh [connect], never on going [RigStatus.State.Stale] (a
     * capability once genuinely seen stays seen for the life of the link). Compared against
     * [RigModule.capabilities] to derive [RigVerification] the same way
     * [org.ort.pipeline.rig.RigLinkProbeState.Verified]/[org.ort.pipeline.rig.RigLinkProbeState
     * .VerifyTimedOut] already do at setup time — reusing [capabilitiesPresentIn], the exact
     * mapping [DefaultRigLinkBridge.probe] uses, rather than a second implementation of the same
     * fact. */
    private val seenCapabilities = java.util.Collections.synchronizedSet(mutableSetOf<RigCapability>())

    @Volatile
    private var manualFrequencyOverrideHz: Long? = null

    @Volatile
    private var lastKnownConnected: RigStatus.State.Connected? = null

    /**
     * F9 (WPC3): wall time of the *first* STALE observation this outage — `null` while connected.
     * Kept separate from [RigStatus.State.Stale.sinceMillis] (which this class already recomputes
     * as "now" on every STALE re-observation, unchanged, to avoid altering existing behaviour) so
     * [reconnectLadderPositionAt] always sees the real elapsed time since the drop began, even
     * though a dual-band rig's [org.ort.rig.descriptor.DescriptorRigModule.markAllStale] emits one
     * STALE [RigState] per band — multiple observations for what is one real retry cycle. A pure
     * function of elapsed time is idempotent across however many times it is called for the same
     * outage, so this never double-counts a retry the way an event-counted attempt would.
     */
    @Volatile
    private var staleSinceWallMillis: Long? = null

    /** R-1015: the in-flight "declare stale from silence" countdown — started the moment
     * [DescriptorRigModule.health] first reports [RigHealthIssue.TIMEOUT] with none already
     * running, cancelled the instant health recovers ([RigHealth.Healthy], or any other
     * [RigHealth.Degraded] issue — both mean the wire is not simply silent) or the transport
     * itself reports [org.ort.rig.TransportState.Lost] (already handled, immediately and with its
     * own reason, by [onRigState]'s STALE branch — this timer would only ever race it to the same
     * conclusion with the wrong [RigHealthIssue], so it is cancelled rather than left to run). */
    @Volatile
    private var healthStaleTimeoutJob: Job? = null

    /** FR-RIG-8: always available regardless of module, and takes precedence with provenance
     * `manual` once set (FR-RIG-9). `null` clears the override, returning to whatever the rig (or
     * nothing) reports. */
    public fun setManualFrequencyOverrideHz(hz: Long?) {
        manualFrequencyOverrideHz = hz
    }

    /**
     * Builds and connects the rig [config] names (FR-RIG-2/3/4/13/14). Never throws: an unknown
     * rig id, a transport the factory does not support, or a failed connect all fall back to
     * [NullRigModule] with a stated reason — capture must never block on the rig (FR-RIG-2,
     * constitution IV). Idempotent — calling this while already connected first calls [disconnect].
     */
    public fun connect(config: CaptureConfiguration) {
        disconnect()
        val transportKind = config.rigTransportKind

        if (config.rigId == NullRigModule.ID || transportKind == null) {
            adoptNullModule(reason = null)
            return
        }

        val descriptor = descriptorForId(config.rigId)
        if (descriptor == null) {
            adoptNullModule(reason = "unknown rig id: ${config.rigId}")
            return
        }

        val built = DescriptorRigModule(descriptor, transportFactory::create, clock, scope)
        // constitution IV / FR-RIG-2/11: capture must never block on, or crash from, the rig.
        // DescriptorRigModule.connect() only wraps a failing transport.open() in its own Result --
        // a RigTransportFactory that itself throws (DefaultRigTransportFactory does, for any kind
        // WPB has not wired yet) is not caught there, so it is caught here instead, treated
        // identically to a Result.failure.
        val result = runCatching { built.connect(transportKind, config.rigParams) }.getOrElse { Result.failure(it) }
        if (result.isFailure) {
            adoptNullModule(reason = result.exceptionOrNull()?.message ?: "rig connect failed")
            return
        }

        module = built
        descriptorRigModule = built
        activeTransportKind = transportKind
        activeDescriptorId = descriptor.id
        perBandState.clear()
        seenCapabilities.clear()
        watch(built)
        watchHealth(built, descriptor)
    }

    /** Cancels every job, disconnects the underlying module (if any), releases whatever
     * [transportFactory] allocated ([RigTransportFactory.dispose]) and reports [RigStatus.absent]. */
    public fun disconnect() {
        observeJob?.cancel()
        observeJob = null
        healthWatchJob?.cancel()
        healthWatchJob = null
        healthStaleTimeoutJob?.cancel()
        healthStaleTimeoutJob = null
        module.disconnect()
        transportFactory.dispose()
        module = NullRigModule()
        descriptorRigModule = null
        activeTransportKind = RigTransportKind.NONE
        activeDescriptorId = NullRigModule.ID
        lastKnownConnected = null
        staleSinceWallMillis = null
        perBandState.clear()
        seenCapabilities.clear()
        RigStatus.reset()
    }

    /**
     * FR-RIG-6: the rig's reading at [startNanos] for [band], and whether it changed again before
     * [endNanos] — a mid-transmission change. FR-RIG-8/9: [manualFrequencyOverrideHz] always takes
     * precedence, provenance `manual`; otherwise the rig's own history, provenance `rig` (staleness
     * is a [RigStatus] concern, not a provenance one — a stale rig reading is still rig-sourced);
     * [FrequencyReading.UNKNOWN] when nothing is known at all.
     */
    public fun frequencyForTransmission(band: RigBand?, startNanos: Long, endNanos: Long): FrequencyReading {
        manualFrequencyOverrideHz?.let { return FrequencyReading(it, FrequencyProvenance.MANUAL) }
        val dm = descriptorRigModule ?: return FrequencyReading.UNKNOWN
        val reading = dm.stateForTransmission(band, startNanos, endNanos) ?: return FrequencyReading.UNKNOWN
        return FrequencyReading(
            frequencyHz = reading.atStart.frequencyHz,
            provenance = FrequencyProvenance.RIG,
            changedDuringTransmission = reading.changedDuringTransmission,
        )
    }

    /**
     * D23 (WPC3): which band's squelch was open at [startNanos] — a dual-band rig's frequency
     * belongs to whichever receiver actually keyed, not to an unscoped, never-populated history
     * bucket (see this package's report on why `band = null` silently produced
     * [FrequencyReading.UNKNOWN] for every TH-D75A transmission before this).
     *
     * The rule (stated once, here, since the reference's own D23 table — `docs/reference/th-d75a-cat.md`
     * — is aspirational about "record both candidates", which nothing in this data shape carries
     * yet):
     * - **Exactly one band open** — that band, unambiguous.
     * - **Neither band open** — [BandAtStart.NONE]: nothing to attribute, never guessed
     *   (constitution I). Callers fall through to whatever [frequencyForTransmission] does for an
     *   unscoped/no-band rig — unchanged from before this method existed.
     * - **Both bands open** — the one that has been open **longer** (the earlier of the two
     *   contiguous open-since timestamps [org.ort.rig.descriptor.DescriptorRigModule.squelchOpenedAtNanos]
     *   reports) is used, on the reasoning that the transmission is more likely a continuation of
     *   whichever band keyed first; [BandAtStart.ambiguous] is set so the caller can reuse FR-RIG-6's
     *   existing `changedDuringTransmission` flag rather than adding a second one (constitution VII
     *   — one rule, expressed once) to mark the reading as needing review.
     *
     * A single-band/no-band rig (no [org.ort.rig.RigBand] ever appears in its history — the null
     * module, the generic ASCII CAT descriptor) reports [BandAtStart.NONE] here too, since neither
     * [org.ort.rig.RigBand.A] nor [org.ort.rig.RigBand.B] is ever open in an unscoped history —
     * exactly preserving the pre-existing `band = null` behaviour for every rig that isn't
     * dual-band.
     */
    public fun bandAtTransmissionStart(startNanos: Long): BandAtStart {
        val dm = descriptorRigModule ?: return BandAtStart.NONE
        val openSince = RigBand.entries.mapNotNull { band ->
            dm.squelchOpenedAtNanos(band, startNanos)?.let { openedAtNanos -> band to openedAtNanos }
        }
        return when (openSince.size) {
            0 -> BandAtStart.NONE
            1 -> BandAtStart(openSince.single().first, ambiguous = false)
            else -> BandAtStart(openSince.minByOrNull { it.second }!!.first, ambiguous = true)
        }
    }

    private fun adoptNullModule(reason: String?) {
        module = NullRigModule(descriptorError = reason)
        descriptorRigModule = null
        activeTransportKind = RigTransportKind.NONE
        activeDescriptorId = NullRigModule.ID
        staleSinceWallMillis = null
        RigStatus.absent()
        DiagnosticsLog.logRigAbsent()
    }

    /**
     * F9 (WPC3): the real backoff ladder [activeTransportKind] retries on — `null` for a kind with
     * no such ladder (the null module, or a future transport this package does not yet know).
     * `:pipeline` already depends on `:rig-usb`/`:rig-bluetooth` (`DefaultRigTransportFactory`), so
     * referencing their ladder objects directly adds no new module edge.
     */
    private fun reconnectLadderDelayFor(kind: RigTransportKind): ((Int) -> Long)? = when (kind) {
        RigTransportKind.USB_SERIAL -> UsbReconnectBackoff::delayMillisFor
        RigTransportKind.BLUETOOTH_SPP -> BluetoothReconnectBackoff::delayMillisFor
        RigTransportKind.BLE, RigTransportKind.NETWORK, RigTransportKind.NONE -> null
    }

    private fun watch(built: DescriptorRigModule) {
        observeJob = scope.launch { built.observe().collect(::onRigState) }
    }

    /**
     * R-1015: watches [DescriptorRigModule.health] for the whole life of this connection —
     * [DescriptorRigModule]'s own read loop retries a silent link forever, emitting
     * [RigHealth.Degraded] with [RigHealthIssue.TIMEOUT] on every cycle
     * ([DescriptorRigModule]'s own comment: "retries forever"), and nothing consumed that signal
     * before this fix — [onRigState] alone never fires again once the rig stops answering, unless
     * the transport itself reports [org.ort.rig.TransportState.Lost] (a genuinely different,
     * already-handled case; see [onRigState]'s own STALE branch and [RigStatus.State.Stale.issue]'s
     * own kdoc for why the two must never collapse into one).
     */
    private fun watchHealth(built: DescriptorRigModule, descriptor: RigDescriptor) {
        val timeoutMillis = healthStaleTimeoutMillisFor(descriptor)
        healthWatchJob = scope.launch {
            built.health().collect { health ->
                val isSilence = health is RigHealth.Degraded && health.issue == RigHealthIssue.TIMEOUT
                if (isSilence) startHealthStaleTimeoutIfAbsent(timeoutMillis) else cancelHealthStaleTimeout()
            }
        }
    }

    private fun startHealthStaleTimeoutIfAbsent(timeoutMillis: Long) {
        // Only one countdown per outage (see healthStaleTimeoutJob's own kdoc), and only while
        // still genuinely Connected -- once this (or the transport-lost path) has already declared
        // Stale, a further TIMEOUT health report is not a new fact worth re-scheduling for.
        if (healthStaleTimeoutJob != null) return
        if (RigStatus.state !is RigStatus.State.Connected) return
        val silentSinceWallMillis = clock.wallMillis()
        healthStaleTimeoutJob = scope.launch {
            delay(timeoutMillis)
            declareStaleFromSilence(silentSinceWallMillis)
        }
    }

    private fun cancelHealthStaleTimeout() {
        healthStaleTimeoutJob?.cancel()
        healthStaleTimeoutJob = null
    }

    /**
     * R-1015: flips [RigStatus] to [RigStatus.State.Stale] tagged [RigHealthIssue.TIMEOUT] once
     * silence has run the full bound [healthStaleTimeoutMillisFor] derives — never before, and
     * never indefinitely. Unlike the transport-lost path, no reconnect ladder is running
     * underneath this case (`UsbSerialTransport`/`BluetoothSppTransport`'s own supervisor loops
     * have no reason to retry a link they still believe is open), so
     * [RigStatus.State.Stale.attempt]/`ofTotal`/`nextRetryInMillis` are left `null` rather than
     * reporting a countdown that is not actually running (constitution I: never assert more than
     * is known). [silentSinceWallMillis] — the wall time this countdown *started*, not the wall
     * time it fired — is reported as [RigStatus.State.Stale.sinceMillis]: the onset of the
     * silence, the fact an operator actually needs, not the moment this class finally noticed it.
     *
     * Guarded on [RigStatus.state] still being [RigStatus.State.Connected]: a concurrent
     * transport-lost ([onRigState]'s STALE branch) or a [disconnect] between when this was
     * scheduled and when it fires must win — this must never downgrade a state already resolved
     * by a stronger signal, nor overwrite a fresh [RigStatus.absent].
     */
    private fun declareStaleFromSilence(silentSinceWallMillis: Long) {
        healthStaleTimeoutJob = null
        val connected = RigStatus.state as? RigStatus.State.Connected ?: return
        val base = lastKnownConnected ?: connected
        RigStatus.stale(base, silentSinceWallMillis, issue = RigHealthIssue.TIMEOUT)
        DiagnosticsLog.logRigStale(silentSinceWallMillis)
    }

    /** R-1019: [module]'s declared capabilities for [activeTransportKind] versus every capability
     * [seenCapabilities] has accumulated so far this connection — see [RigVerification]'s own
     * kdoc. An empty declared set reports [RigVerification.Full] immediately, the same reasoning
     * [org.ort.pipeline.rig.DefaultRigLinkBridge.probe] already applies (nothing to verify cannot
     * be left unverified). */
    private fun currentVerification(): RigVerification {
        val declared = module.capabilities(activeTransportKind)
        if (declared.isEmpty()) return RigVerification.Full
        val missing = declared - seenCapabilities
        return if (missing.isEmpty()) RigVerification.Full else RigVerification.Partial(missing)
    }

    private fun onRigState(state: RigState) {
        perBandState[state.band] = state
        val bands = synchronized(perBandState) {
            perBandState.values.map { s ->
                RigStatus.BandState(
                    band = s.band?.name ?: UNBANDED_LABEL,
                    frequencyHz = s.frequencyHz,
                    mode = s.mode,
                    squelchOpen = s.squelchOpen ?: false,
                )
            }
        }
        when (state.sourceConfidence) {
            RigStateConfidence.FRESH -> {
                cancelHealthStaleTimeout()
                seenCapabilities += capabilitiesPresentIn(state)
                val connected = RigStatus.State.Connected(
                    descriptor = module.displayName,
                    bands = bands,
                    transportKind = activeTransportKind,
                    descriptorId = activeDescriptorId,
                    verification = currentVerification(),
                )
                lastKnownConnected = connected
                staleSinceWallMillis = null
                RigStatus.connected(
                    connected.descriptor,
                    connected.bands,
                    connected.transportKind,
                    connected.descriptorId,
                    connected.verification,
                )
                DiagnosticsLog.logRigConnected(connected.bands.size)
                state.band?.let { DiagnosticsLog.logRigBand(it.name, state.frequencyHz, state.squelchOpen ?: false) }
            }
            RigStateConfidence.STALE -> {
                cancelHealthStaleTimeout()
                val base = lastKnownConnected ?: RigStatus.State.Connected(
                    descriptor = module.displayName,
                    bands = bands,
                    transportKind = activeTransportKind,
                    descriptorId = activeDescriptorId,
                    verification = currentVerification(),
                )
                val sinceMillis = clock.wallMillis()
                val staleSince = staleSinceWallMillis ?: sinceMillis.also { staleSinceWallMillis = it }
                val ladder = reconnectLadderDelayFor(activeTransportKind)
                val position = ladder?.let {
                    reconnectLadderPositionAt(
                        elapsedMillis = (sinceMillis - staleSince).coerceAtLeast(0L),
                        ofTotal = RECONNECT_LADDER_STEPS,
                        delayMillisFor = it,
                    )
                }
                RigStatus.stale(
                    base,
                    sinceMillis,
                    position?.attempt,
                    position?.ofTotal,
                    position?.nextRetryInMillis,
                    issue = RigHealthIssue.TRANSPORT_LOST,
                )
                DiagnosticsLog.logRigStale(sinceMillis)
            }
        }
    }

    private companion object {
        const val UNBANDED_LABEL = "-"

        /** F9 (WPC3): step count of `UsbReconnectBackoff`/`BluetoothReconnectBackoff` (1s, 2s, 5s,
         * 10s, 30s, then holding) — copied by the same documented policy those two objects and
         * `capture-android`'s `BackoffLadder` already use for the step *values* themselves, since
         * none of the three exposes its length publicly and `:pipeline` may not reach into their
         * private state. */
        const val RECONNECT_LADDER_STEPS = 5

        /** R-1015: multiplies the descriptor's own poll cadence to derive how long silence must
         * run before [RigSupervisor] declares [RigStatus] stale on the timeout path — the same
         * reasoning [org.ort.pipeline.rig.DefaultRigLinkBridge]'s own poll-cycle multiples apply
         * (R-1013/R-1014), chosen more generously here than either of those: this bound covers a
         * session's whole lifetime, not a one-shot setup probe, so a single missed poll cycle from
         * ordinary real-world jitter must never falsely declare a healthy night's rig stale — but
         * still bounded, since "forever" is exactly the defect this fixes. */
        const val HEALTH_STALE_POLL_CYCLES = 5L

        /** Floor for a descriptor with no [RigDescriptor.poll] at all (unsolicited-push-only) —
         * mirrors [org.ort.pipeline.rig.DefaultRigLinkBridge]'s own `NO_POLL_TIMEOUT_MILLIS` for
         * the identical situation: there is no cadence to derive a multiple of. */
        const val NO_POLL_HEALTH_STALE_TIMEOUT_MILLIS = 10_000L

        /** Floor applied to any derived value, so an unrealistically fast poll cadence (a
         * descriptor error, or a future rig with a genuine sub-second cadence) still gets a wait
         * worth calling a timeout rather than one that could fire before a reply could physically
         * arrive. */
        const val MIN_HEALTH_STALE_TIMEOUT_MILLIS = 5_000L

        /** See [healthStaleTimeoutMillisFor]'s own kdoc for why this is overridable at all; this
         * is the production default it falls back to. */
        fun defaultHealthStaleTimeoutMillis(descriptor: RigDescriptor): Long = descriptor.poll?.intervalMs
            ?.let { interval -> (interval * HEALTH_STALE_POLL_CYCLES).coerceAtLeast(MIN_HEALTH_STALE_TIMEOUT_MILLIS) }
            ?: NO_POLL_HEALTH_STALE_TIMEOUT_MILLIS
    }
}
