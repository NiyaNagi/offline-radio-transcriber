package org.ort.pipeline.rig

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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

/**
 * FR-SEG-5 (WPSQUELCH): one squelch-union update, timestamped in the session's own *monotonic*
 * domain (FR-RUN-17's "timestamped on receipt") — never a sample position, which is `:segment`'s
 * own `org.ort.segment.SquelchUpdate` shape instead. Converting the two is deliberately not this
 * class's job: [RigSupervisor] only ever exposes the rig's own facts, never an audio-timeline
 * computation (`:pipeline`'s `RealCaptureService.buildSegmenter`, which already owns a session's
 * [org.ort.core.SampleClock], does that conversion — see
 * `org.ort.pipeline.capture.pushSquelchTransition`).
 *
 * [open] is `null` for a **loss** (R-1062 follow-up, constitution IV): squelch authority has
 * gone away — the transport was lost, this class disconnected, or the connection went stale past
 * [RigSupervisor.squelchFusionEligible]'s own bound (see the private staleness watchdog for the
 * exact rule and its margin) — and the receiving [org.ort.segment.SquelchGate] must revert
 * fusion to VAD-only rather than freeze the last-known value. `true`/`false` is a genuine
 * open/close transition, exactly as before this fix.
 */
public data class RigSquelchTransition(public val open: Boolean?, public val timestampNanos: Long)

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

    /** WPSQUELCH (FR-SEG-5): the descriptor behind the current connection, if any — needed only
     * by [squelchFusionEligible] to read the descriptor's own latency shape (`unsolicited`
     * vs. `poll`); every other use in this class already goes through [module]/[descriptorRigModule]. */
    private var activeDescriptor: RigDescriptor? = null

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

    /** WPSQUELCH (FR-SEG-5): the union (across every band) of the rig's own squelch state, as
     * seen by [onRigState] — `null` until the first genuine squelch reading arrives this
     * connection, exactly the "not yet known" state [org.ort.segment.Segmenter] treats as
     * VAD-only fallback. Reset on every [connect]/[disconnect] so a fresh connection starts
     * honestly unknown again, never carrying a stale union from a previous rig. */
    @Volatile
    private var squelchUnionOpen: Boolean? = null

    /** WPSQUELCH (FR-SEG-5): emits only on a genuine union-level change, and only while
     * [squelchFusionEligible] holds — see [observeSquelchUnion]'s own kdoc. */
    private val squelchEvents = MutableSharedFlow<RigSquelchTransition>(extraBufferCapacity = 64)

    /** R-1062 follow-up: the in-flight "declare squelch stale" countdown — restarted on every
     * FRESH [RigState] (see [restartSquelchStaleWatchdog]'s own kdoc for why any fresh state is
     * sufficient proof of liveness) and cancelled the moment this connection ends or goes STALE
     * by some stronger signal first. `null` whenever no countdown is running. */
    @Volatile
    private var squelchStaleTimeoutJob: Job? = null

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
        activeDescriptor = descriptor
        perBandState.clear()
        seenCapabilities.clear()
        squelchUnionOpen = null
        watch(built)
        watchHealth(built, descriptor)
    }

    /** Cancels every job, disconnects the underlying module (if any), releases whatever
     * [transportFactory] allocated ([RigTransportFactory.dispose]) and reports [RigStatus.absent].
     *
     * R-1062 follow-up (FR-SEG-5, constitution IV): a supervisor stop or rig change (this is also
     * called at the top of every [connect]) is exactly as much a loss of squelch authority as a
     * transport drop — emitted here, before anything else resets, so a caller building a fresh
     * [org.ort.segment.SquelchGate]-bridged session never inherits a stale "still open" reading
     * from whatever connection just ended.
     */
    public fun disconnect() {
        observeJob?.cancel()
        observeJob = null
        healthWatchJob?.cancel()
        healthWatchJob = null
        healthStaleTimeoutJob?.cancel()
        healthStaleTimeoutJob = null
        cancelSquelchStaleWatchdog()
        emitSquelchLoss(clock.monotonicNanos())
        module.disconnect()
        transportFactory.dispose()
        module = NullRigModule()
        descriptorRigModule = null
        activeTransportKind = RigTransportKind.NONE
        activeDescriptorId = NullRigModule.ID
        activeDescriptor = null
        lastKnownConnected = null
        staleSinceWallMillis = null
        perBandState.clear()
        seenCapabilities.clear()
        RigStatus.reset()
    }

    /**
     * FR-SEG-5 / FR-RUN-17: the union (across every band) of the rig's own squelch state, one
     * event per genuine open/closed transition — see [Segmenter][org.ort.segment.Segmenter]'s own
     * kdoc for the fusion rule this feeds. **D23's two-band finding applies here as the
     * conservative "union of open intervals" this task's own report names**: a single
     * `org.ort.segment.Segmenter` processes one, already-mixed audio stream (the TH-D75A receives
     * on both bands at once and mixes the audio into one output — `docs/reference/th-d75a-cat.md`),
     * so a segment boundary can only honestly reflect "some band is open", never "band A alone" —
     * frequency/band *attribution* for a resulting transmission is a separate, already-solved
     * concern ([bandAtTransmissionStart]), untouched by this method. **Open question, reported
     * rather than silently resolved (constitution I):** two genuinely overlapping but distinct
     * transmissions on different bands are, under this rule, fused into one segment when their
     * open intervals overlap — correct per the union rule, but a spec reader might reasonably want
     * them split. Splitting would need a second, band-aware `Segmenter` per band, which the
     * current one-audio-stream architecture does not support; flagged for the lead/spec, not
     * resolved here.
     *
     * Emits nothing at all — matching [org.ort.segment.Segmenter]'s own "no squelch capability"
     * fallback — unless [squelchFusionEligible] holds: no [RigCapability.SQUELCH_STATE], or a
     * poll-only descriptor whose cadence cannot honestly meet FR-RUN-17's ≤250 ms correlation
     * bound, means no event is ever pushed, exactly the same as no [org.ort.segment.SquelchGate]
     * being wired at all.
     */
    public fun observeSquelchUnion(): Flow<RigSquelchTransition> = squelchEvents

    /**
     * FR-SEG-5 / FR-RUN-17: whether this connection's squelch reporting is trustworthy enough to
     * fuse into segmentation boundaries — true exactly when [RigCapability.SQUELCH_STATE] is
     * declared for the active transport **and** the descriptor's own latency shape keeps the
     * receipt-timestamp-to-transition skew within FR-RUN-17's ≤250 ms bound.
     *
     * The reference doc's own finding (`docs/reference/th-d75a-cat.md`, "`AI` gives push, not
     * poll") is the source for the two cases distinguished here. An `unsolicited` (push)
     * descriptor reports a transition the instant it happens, so the receipt timestamp
     * [DescriptorRigModule.applyMatch] already stamps (FR-RUN-17: "timestamped on receipt") is a
     * tight bound on the true transition instant — comfortably inside 250 ms, exactly the
     * reference doc's own "makes the budget comfortable". A poll-only descriptor's receipt
     * timestamp can lag the true transition by up to a full poll interval; where that interval
     * itself exceeds the bound (the TH-D75A's own documented resync-only fallback is 2000 ms),
     * the skew cannot be bounded and this returns `false` — FR-RUN-17's own "downgrade" rule,
     * applied here to squelch fusion since the spec states no separate rule for it (reported in
     * this task's own report as a spec-level decision worth confirming explicitly).
     */
    public fun squelchFusionEligible(): Boolean {
        val descriptor = activeDescriptor ?: return false
        if (RigCapability.SQUELCH_STATE !in module.capabilities(activeTransportKind)) return false
        if (descriptor.unsolicited != null) return true
        val pollIntervalMs = descriptor.poll?.intervalMs ?: return false
        return pollIntervalMs <= MAX_SQUELCH_CORRELATION_SKEW_MILLIS
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
        activeDescriptor = null
        staleSinceWallMillis = null
        squelchUnionOpen = null
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
        cancelSquelchStaleWatchdog()
        // R-1062 round 3: the generic link-silence timeout is a squelch-authority loss too, but
        // ONLY for a descriptor that actually polls -- a push-only descriptor with no poll at all
        // is the exact case round 3 fixed the watchdog for (a quiet radio genuinely sends
        // nothing, by design, so silence proves nothing), and this backstop must honour that same
        // rule identically, or it would silently reintroduce the false loss the watchdog fix just
        // removed via a second path. For a descriptor that does poll, this bound (5 poll cycles /
        // a 5-10s floor, see defaultHealthStaleTimeoutMillis) is far looser than
        // squelchStalenessBoundMillis's own, so in practice the tighter squelch-specific watchdog
        // almost always fires first; this is the honest backstop for whatever silence this path
        // alone catches, not a duplicate of it.
        if (activeDescriptor?.poll != null) {
            emitSquelchLoss(clock.monotonicNanos())
        }
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
        emitSquelchUnionIfChanged(state)
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
                // R-1062 follow-up: any fresh reading proves this connection is still genuinely
                // talking, so it restarts the squelch staleness countdown regardless of whether
                // this particular line carried squelch content — see restartSquelchStaleWatchdog's
                // own kdoc.
                restartSquelchStaleWatchdog()
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
                // R-1062 follow-up: a transport-lost STALE reading is an immediate, certain loss
                // of squelch authority -- no need to wait for the staleness watchdog to time out
                // on its own when the transport has already announced the loss directly.
                cancelSquelchStaleWatchdog()
                emitSquelchLoss(state.timestampNanos)
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

    /**
     * WPSQUELCH (FR-SEG-5, D23): recomputes the union across every band in [perBandState] and
     * emits exactly on a genuine change — called only for a FRESH [RigState] (a STALE one no
     * longer reaches this method at all: [onRigState]'s STALE branch calls [emitSquelchLoss]
     * directly instead, the R-1062 follow-up fix — a STALE reading's `squelchOpen`, carried
     * forward unchanged by [DescriptorRigModule.markAllStale], must never be read as if it were
     * still current).
     *
     * Gated on [squelchFusionEligible] so an ineligible connection (no [RigCapability.SQUELCH_STATE],
     * or a poll cadence too slow to trust — see that method's own kdoc) never emits at all, exactly
     * matching "no squelch capability" from a [org.ort.segment.Segmenter]'s point of view.
     */
    private fun emitSquelchUnionIfChanged(state: RigState) {
        if (state.squelchOpen == null) return
        if (!squelchFusionEligible()) return
        val unionOpen = synchronized(perBandState) { perBandState.values.any { it.squelchOpen == true } }
        if (unionOpen == squelchUnionOpen) return
        squelchUnionOpen = unionOpen
        squelchEvents.tryEmit(RigSquelchTransition(unionOpen, state.timestampNanos))
    }

    /**
     * R-1062 follow-up (FR-SEG-5, constitution IV "capture never lies"): squelch authority is
     * gone as of [timestampNanos] — a transport loss, a disconnect/rig change, or staleness (see
     * [restartSquelchStaleWatchdog]). Emits [RigSquelchTransition] with `open = null` exactly
     * once per genuine loss (guarded on [squelchUnionOpen] already being non-null, the same
     * "only on a real change" discipline [emitSquelchUnionIfChanged] already applies) — a
     * connection that was never [squelchFusionEligible] in the first place never had
     * [squelchUnionOpen] set at all, so this is already a no-op for it, with no separate
     * eligibility check needed here.
     */
    private fun emitSquelchLoss(timestampNanos: Long) {
        if (squelchUnionOpen == null) return
        squelchUnionOpen = null
        squelchEvents.tryEmit(RigSquelchTransition(open = null, timestampNanos = timestampNanos))
    }

    /**
     * R-1062 round 3 (FR-SEG-5 / FR-RUN-17, constitution IV): restarts the "declare squelch
     * stale" countdown — cancelling whatever was already running first, so only the latest fresh
     * reading's own countdown is ever live. Any FRESH [RigState] restarts it, not only one
     * carrying squelch content: proving the transport is still talking at all is what this
     * watchdog needs, since a poll cycle re-emits state on schedule regardless of content
     * ([DescriptorRigModule.applyMatch] has no dedup).
     *
     * **A no-op unless the descriptor actually polls** ([RigDescriptor.poll] non-null) — this is
     * round 3's own fix. Round 2 gave every push-capable descriptor a flat, short bound, on the
     * reasoning that a quiet radio sending nothing "looked like" silence worth watching. That
     * reasoning was wrong for the real TH-D75A descriptor, which declares **both** `unsolicited`
     * *and* a 2000 ms `poll` fallback: `DescriptorRigModule`'s poll loop writes every command for
     * a cycle back-to-back, then sleeps the *whole* interval before writing the next cycle (see
     * that class's own `startLoops`), so the natural gap between one cycle's last reply and the
     * next cycle's first reply is genuinely close to the interval itself, plus real reply
     * latency — round 2's watchdog was firing on this ordinary rhythm, cutting an over that was
     * never actually interrupted (CON-SEG-1: unlike every other bug, this one cannot be
     * reprocessed away). A push-only descriptor with **no** poll fallback has no natural heartbeat
     * at all — a quiet radio with nothing to report sends nothing, by design
     * (`docs/reference/th-d75a-cat.md`'s own "`AI` gives push, not poll" finding) — so silence
     * from it is not evidence of anything, and this watchdog must not run for it at all. Loss for
     * such a descriptor comes only from the transport-lost and disconnect paths (still exactly as
     * before), unless a future descriptor adds a real heartbeat command — which this class does
     * not do today, and this comment says so explicitly rather than silently assuming one exists.
     */
    private fun restartSquelchStaleWatchdog() {
        squelchStaleTimeoutJob?.cancel()
        squelchStaleTimeoutJob = null
        if (!squelchFusionEligible()) return
        val pollIntervalMs = activeDescriptor?.poll?.intervalMs ?: return
        val boundMillis = squelchStalenessBoundMillis(pollIntervalMs)
        squelchStaleTimeoutJob = scope.launch {
            delay(boundMillis)
            squelchStaleTimeoutJob = null
            emitSquelchLoss(clock.monotonicNanos())
        }
    }

    private fun cancelSquelchStaleWatchdog() {
        squelchStaleTimeoutJob?.cancel()
        squelchStaleTimeoutJob = null
    }

    /**
     * R-1062 round 3 (FR-SEG-5 / FR-RUN-17): how long squelch authority may go unrefreshed, for a
     * descriptor with a genuine poll heartbeat, before [restartSquelchStaleWatchdog] declares it
     * lost. Only ever called with [pollIntervalMs] from a real [RigDescriptor.poll] — see that
     * method's own kdoc for why a push-only descriptor never reaches this at all.
     *
     * **The bound is `[pollIntervalMs] × [SQUELCH_STALENESS_POLL_MARGIN_MULTIPLIER]`, and here is
     * the arithmetic that multiplier has to survive**, worked out against the real TH-D75A
     * descriptor and `DescriptorRigModule`'s actual poll loop (`bands=[0,1]`, `perBand=[FQ, BY]`,
     * so 4 writes per cycle, issued back-to-back with no wait between them, before the loop
     * sleeps [pollIntervalMs] and repeats):
     * - Every reply lands at write-time + its own round-trip latency. Modelling a realistic link
     *   (≥40 ms base, occasionally another ~300 ms of jitter — Bluetooth SPP's own worst case,
     *   per this task's own report) puts any single reply's latency in roughly `[40, 340]` ms.
     * - This watchdog restarts on *every* fresh reply, so the *last* restart in cycle N happens
     *   at cycle N's own write-time + that cycle's *slowest* reply (up to 340 ms) — the earliest
     *   the *next* restart can happen is cycle N+1's write-time (exactly [pollIntervalMs] later)
     *   plus that cycle's *fastest* reply (as little as 40 ms).
     * - **Worst-case real gap between restarts** is therefore `[pollIntervalMs] + 340 − 40` =
     *   `[pollIntervalMs] + 300` ms — for the TH-D75A's 2000 ms interval, up to 2300 ms, **already
     *   above the flat 2000 ms bound round 2 used**, which is exactly the defect this round fixes.
     * - `× 2` gives 4000 ms against that 2300 ms worst case — 1700 ms (74%) of headroom above the
     *   worst realistic single-cycle gap this task's own model produces, comfortably absorbing
     *   real-world variance beyond that model (a slower Bluetooth SPP link, an occasional dropped
     *   reply) while still declaring a link that has missed *two full cycles* — genuinely
     *   abnormal — lost within a bounded time. `AC_69`-style precision at the 250 ms grade
     *   [MAX_SQUELCH_CORRELATION_SKEW_MILLIS] gives fresh, on-time data is not what this bound is
     *   for; it exists only to catch a link that has actually gone quiet.
     * - `RigSupervisorSquelchTest`'s realistic-latency cases drive this arithmetic directly
     *   against the real bundled descriptor and assert zero false losses over 30 s of continuous
     *   squelch-open virtual time; confirming the margin against the real hardware (not just this
     *   modelled latency range) is this task's own named hardware check.
     */
    private fun squelchStalenessBoundMillis(pollIntervalMs: Long): Long =
        pollIntervalMs * SQUELCH_STALENESS_POLL_MARGIN_MULTIPLIER

    private companion object {
        const val UNBANDED_LABEL = "-"

        /** FR-RUN-17's own bound, restated here since [squelchFusionEligible] is the one place
         * that decides whether a rig's squelch correlation is trustworthy enough to fuse
         * (FR-SEG-5). */
        const val MAX_SQUELCH_CORRELATION_SKEW_MILLIS = 250L

        /** See [squelchStalenessBoundMillis]'s own kdoc for the full reasoning and its source. */
        const val SQUELCH_STALENESS_POLL_MARGIN_MULTIPLIER = 2L

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
