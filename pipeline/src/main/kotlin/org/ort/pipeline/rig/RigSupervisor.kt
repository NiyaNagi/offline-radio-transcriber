package org.ort.pipeline.rig

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.ort.core.Clock
import org.ort.core.SystemClock
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.diagnostics.DiagnosticsLog
import org.ort.rig.NullRigModule
import org.ort.rig.RigBand
import org.ort.rig.RigModule
import org.ort.rig.RigState
import org.ort.rig.RigStateConfidence
import org.ort.rig.RigTransportKind
import org.ort.rig.descriptor.BundledDescriptors
import org.ort.rig.descriptor.DescriptorRigModule
import org.ort.rig.descriptor.RigDescriptor

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
) {
    private var module: RigModule = NullRigModule()
    private var descriptorRigModule: DescriptorRigModule? = null
    private var activeTransportKind: RigTransportKind = RigTransportKind.NONE
    private var activeDescriptorId: String = NullRigModule.ID

    private var observeJob: Job? = null

    private val perBandState = java.util.Collections.synchronizedMap(LinkedHashMap<RigBand?, RigState>())

    @Volatile
    private var manualFrequencyOverrideHz: Long? = null

    @Volatile
    private var lastKnownConnected: RigStatus.State.Connected? = null

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
        watch(built)
    }

    /** Cancels every job, disconnects the underlying module (if any), releases whatever
     * [transportFactory] allocated ([RigTransportFactory.dispose]) and reports [RigStatus.absent]. */
    public fun disconnect() {
        observeJob?.cancel()
        observeJob = null
        module.disconnect()
        transportFactory.dispose()
        module = NullRigModule()
        descriptorRigModule = null
        activeTransportKind = RigTransportKind.NONE
        activeDescriptorId = NullRigModule.ID
        lastKnownConnected = null
        perBandState.clear()
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

    private fun adoptNullModule(reason: String?) {
        module = NullRigModule(descriptorError = reason)
        descriptorRigModule = null
        activeTransportKind = RigTransportKind.NONE
        activeDescriptorId = NullRigModule.ID
        RigStatus.absent()
        DiagnosticsLog.logRigAbsent()
    }

    private fun watch(built: DescriptorRigModule) {
        observeJob = scope.launch { built.observe().collect(::onRigState) }
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
                val connected = RigStatus.State.Connected(
                    descriptor = module.displayName,
                    bands = bands,
                    transportKind = activeTransportKind,
                    descriptorId = activeDescriptorId,
                )
                lastKnownConnected = connected
                RigStatus.connected(
                    connected.descriptor,
                    connected.bands,
                    connected.transportKind,
                    connected.descriptorId,
                )
                DiagnosticsLog.logRigConnected(connected.bands.size)
                state.band?.let { DiagnosticsLog.logRigBand(it.name, state.frequencyHz, state.squelchOpen ?: false) }
            }
            RigStateConfidence.STALE -> {
                val base = lastKnownConnected ?: RigStatus.State.Connected(
                    descriptor = module.displayName,
                    bands = bands,
                    transportKind = activeTransportKind,
                    descriptorId = activeDescriptorId,
                )
                val sinceMillis = clock.wallMillis()
                RigStatus.stale(base, sinceMillis)
                DiagnosticsLog.logRigStale(sinceMillis)
            }
        }
    }

    private companion object {
        const val UNBANDED_LABEL = "-"
    }
}
