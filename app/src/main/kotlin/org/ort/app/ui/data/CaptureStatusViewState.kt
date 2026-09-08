package org.ort.app.ui.data

import org.ort.pipeline.capture.AsrAvailability
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.capture.StorageForecast
import org.ort.pipeline.capture.ThermalStatus
import org.ort.pipeline.capture.VadAvailability
import java.util.Locale

/**
 * `Capture-Status.dc.html`'s full surface (ui-conformance-plan WP4, R-031/R-032/R-034/R-035/
 * R-038, FR-UI-7) — the field dump [org.ort.app.ui.screens.CaptureStatusScreen] used to render
 * (`org.ort.app.status.StatusViewState`) moved and reshaped into the artboard's three sections
 * (Audio/Processing/Device), each row a [KeyValueFacts] a [org.ort.app.ui.components.KeyValueRow]
 * renders directly — no screen-side string assembly left over from the old flat layout.
 *
 * **Every fact this cannot honestly measure renders "not measured", never a fabricated value**
 * (constitution I). Two rows are "not measured" on every build today, not a bug this package can
 * fix without touching a file outside its row:
 * - **Input** (device name / verified / resampler id): `RealCaptureService`
 *   (`org.ort.pipeline.capture.RealCaptureService`) knows the selected
 *   [org.ort.capture.android.AudioDeviceDescriptor] and
 *   [org.ort.capture.android.AudioRecordSource.resamplerIdentity], but neither is republished to
 *   a process-wide holder the way [CaptureState]/[ThermalStatus]/[RigStatus]/[StorageForecast]
 *   are — `RealCaptureService.kt`'s wiring is WP11a's file, not this package's, so this is named
 *   here and in this package's report rather than worked around.
 * - **Level** (peak/noise/headroom dBFS): no level signal exists in `:pipeline` at all yet —
 *   [org.ort.app.ui.data.LevelViewState] carries the same honesty for
 *   [org.ort.app.ui.screens.LevelMeterScreen] (R-039).
 */
public data class CaptureStatusViewState(
    val stateLabel: String,
    val stateTone: CaptureStateTone,
    val sinceElapsedLabel: String,
    val haltActionLabel: String?,
    val haltConfirmTitle: String,
    val haltConfirmBody: String,
    val input: KeyValueFacts,
    val level: KeyValueFacts,
    val radio: KeyValueFacts,
    val overs: KeyValueFacts,
    val backlog: KeyValueFacts,
    val tier: KeyValueFacts,
    val thermal: KeyValueFacts,
    val storage: KeyValueFacts,
    val battery: KeyValueFacts,
)

/** The state dot's tone — reuses the product's one halt/degrade/nominal vocabulary (guide §3's
 * "red is for one thing"), not a fourth ad hoc colour scheme for this one screen. */
public enum class CaptureStateTone { NOMINAL, DEGRADED, HALTED, IDLE }

/** One [org.ort.app.ui.components.KeyValueRow]'s content: value, optional sub-line, optional
 * trailing state dot. [trailingText] is the small mono word beside a dot ("verified", "connected"). */
public data class KeyValueFacts(
    val value: String,
    val subLine: String? = null,
    val trailingDot: CaptureStateTone? = null,
    val trailingText: String? = null,
)

/** `Level-Meter.dc.html`'s facts (R-039) — see [CaptureStatusViewState]'s own kdoc for why every
 * field here is `null` on every build today; [notMeasuredReason] is shown instead of fabricated
 * bars (guide §6.8: a missing capability is *failed*, not *empty*). */
public data class LevelViewState(
    val inputLabel: String,
    val peakDbfsLabel: String?,
    val noiseFloorDbfsLabel: String?,
    val headroomLabel: String?,
    val clippedSamplesLabel: String?,
    val notMeasuredReason: String?,
) {
    public companion object {
        /** The only real producer today — see this type's own kdoc for why. */
        public fun notMeasured(inputLabel: String = "No input device signal published yet"): LevelViewState =
            LevelViewState(
                inputLabel = inputLabel,
                peakDbfsLabel = null,
                noiseFloorDbfsLabel = null,
                headroomLabel = null,
                clippedSamplesLabel = null,
                notMeasuredReason = "No level (dBFS) signal is published by :pipeline yet. Audio is still " +
                    "captured and kept — this is only the meter, not capture itself.",
            )
    }
}

public object CaptureStatusMapper {

    private const val MAX_TIER: Int = 3
    private const val BYTES_PER_GIB: Double = 1024.0 * 1024.0 * 1024.0

    /**
     * The pure computation (no `Context`, no I/O — [org.ort.app.ui.data.ReaderPolling.captureStatus]
     * gathers every input below and calls this). [shedLevel]/[backlog] are `null` exactly when
     * [org.ort.pipeline.capture.ShedStatus] has not measured anything yet this process (mirrors
     * `StatusViewStateMapper`'s own "not measured" rule) — see that mapper's kdoc for why `0` must
     * never stand in for "no one has looked yet".
     */
    @Suppress("LongParameterList")
    public fun from(
        captureState: CaptureState.State,
        shedLevel: Int?,
        backlog: Int?,
        thermal: ThermalStatus.State,
        rig: RigStatus.State,
        storage: StorageForecast.State,
        asr: AsrAvailability.State,
        vad: VadAvailability.State,
        sinceLabel: String?,
        elapsedLabel: String,
        heartbeatSecondsAgo: Long?,
        isAlive: Boolean,
        transmissionCount: Int,
        rejectedCount: Int,
        failedCount: Int,
        gapCount: Int,
        batteryPercent: Int?,
        batteryCharging: Boolean,
        batteryExemptionReportsIgnoring: Boolean,
    ): CaptureStatusViewState {
        val isCapturing = captureState is CaptureState.State.Capturing
        val (stateLabel, stateTone) = titleFor(captureState, thermal)

        return CaptureStatusViewState(
            stateLabel = stateLabel,
            stateTone = stateTone,
            sinceElapsedLabel = sinceElapsedFacts(sinceLabel, elapsedLabel, heartbeatSecondsAgo, isAlive, isCapturing),
            haltActionLabel = if (isCapturing) "Stop" else null,
            haltConfirmTitle = "Stop capture?",
            haltConfirmBody = "Audio already captured is kept. Nothing already recorded is lost, and capture " +
                "can be started again from Now.",
            input = notMeasuredFacts(
                "no input device signal is published by :pipeline yet — see this package's report",
            ),
            level = notMeasuredFacts("no level signal is published by :pipeline yet (R-039)"),
            radio = radioFacts(rig),
            overs = overFacts(transmissionCount, rejectedCount, failedCount, gapCount),
            backlog = backlogFacts(backlog),
            tier = tierFacts(shedLevel, asr, vad),
            thermal = thermalFacts(thermal),
            storage = storageFacts(storage),
            battery = batteryFacts(batteryPercent, batteryCharging, batteryExemptionReportsIgnoring),
        )
    }

    /**
     * `Flow-Degrade.dc.html`'s five titles. "Capturing, text only" is the one this cannot honestly
     * render yet: no signal anywhere in `:pipeline` records that audio retention has been paused
     * (F06's storage-floor stage never wired) — inventing it here would be exactly the fabrication
     * constitution I forbids, so a captured-but-warm session reads "Capturing, warm" even at a
     * storage floor until that signal exists. `Interrupted` has no title of its own in the closed
     * set the artboard gives; it reads as `Halted` (capture is not moving audio right now) with the
     * real cause carried in [CaptureStatusViewState.sinceElapsedLabel]'s sub-line instead of a
     * sixth, un-designed title.
     */
    private fun titleFor(state: CaptureState.State, thermal: ThermalStatus.State): Pair<String, CaptureStateTone> =
        when (state) {
            CaptureState.State.Idle -> "Not capturing" to CaptureStateTone.IDLE
            is CaptureState.State.Failed, is CaptureState.State.Interrupted -> "Halted" to CaptureStateTone.HALTED
            CaptureState.State.Capturing -> when (thermal) {
                is ThermalStatus.State.Warm, is ThermalStatus.State.Hot ->
                    "Capturing, warm" to CaptureStateTone.DEGRADED
                is ThermalStatus.State.Nominal -> "Capturing" to CaptureStateTone.NOMINAL
            }
        }

    private fun sinceElapsedFacts(
        sinceLabel: String?,
        elapsedLabel: String,
        heartbeatSecondsAgo: Long?,
        isAlive: Boolean,
        isCapturing: Boolean,
    ): String {
        if (!isCapturing) return "Last heartbeat " + (heartbeatSecondsAgo?.let { "${it}s ago" } ?: "not measured")
        val heartbeat = when {
            !isAlive -> "not responding, heartbeat stale"
            heartbeatSecondsAgo != null -> "alive, heartbeat ${heartbeatSecondsAgo}s ago"
            else -> "alive, heartbeat not yet measured"
        }
        val since = sinceLabel?.let { "Since $it · " } ?: ""
        return "$since$elapsedLabel · $heartbeat"
    }

    private fun radioFacts(rig: RigStatus.State): KeyValueFacts = when (rig) {
        RigStatus.State.Absent -> KeyValueFacts(value = "No rig configured", subLine = "FR-RIG is not built yet")
        is RigStatus.State.Connected -> KeyValueFacts(
            value = rig.descriptor,
            subLine = rig.bands.joinToString(" · ") { band ->
                "${band.band} ${band.frequencyHz?.let { formatFrequencyMHz(it) } ?: "—"} " +
                    if (band.squelchOpen) "open" else "closed"
            },
            trailingDot = CaptureStateTone.NOMINAL,
            trailingText = "connected",
        )

        is RigStatus.State.Stale -> KeyValueFacts(
            value = rig.lastKnown.descriptor,
            subLine = "last known: " + rig.lastKnown.bands.joinToString(" · ") { band ->
                "${band.band} ${band.frequencyHz?.let { formatFrequencyMHz(it) } ?: "—"}?"
            },
            trailingDot = CaptureStateTone.DEGRADED,
            trailingText = "disconnected",
        )
    }

    private fun overFacts(transmissionCount: Int, rejectedCount: Int, failedCount: Int, gapCount: Int): KeyValueFacts =
        KeyValueFacts(
            value = "$transmissionCount captured",
            subLine = "$rejectedCount rejected · $failedCount failed · $gapCount gaps",
        )

    private fun backlogFacts(backlog: Int?): KeyValueFacts = if (backlog == null) {
        KeyValueFacts(value = "Not measured", subLine = "no shed reading published yet this session")
    } else if (backlog == 0) {
        KeyValueFacts(value = "0 overs waiting", subLine = "Pass B is caught up")
    } else {
        KeyValueFacts(value = "$backlog overs waiting")
    }

    /**
     * R-038: the artboard has no "shed level" row at all — Tier/Thermal/Backlog carry what the
     * shed order actually did. [shedLevel] maps to a tier number by the exact formula
     * `RealCaptureService.tierFromShedLevel()` already uses in production
     * (`(MAX_TIER - shedLevel).coerceIn(0, MAX_TIER)`), reused here rather than invented a second
     * time, so the notification and this screen never disagree about what "tier 2" means.
     *
     * The sub-line states the real ASR/VAD facts (R-034 — this is where they moved, off the raw
     * "ASR: …"/"VAD: …" lines the old field dump printed) but **never claims a Pass C state**:
     * no flag for Pass C exists anywhere in `:pipeline` yet (M4's fork is unbuilt), so asserting
     * "Pass C on" the way the artboard's own example text does would be exactly the fabrication
     * constitution I forbids. This is a deliberate, documented divergence from the artboard's
     * literal example string, not an oversight — see this package's report.
     */
    private fun tierFacts(shedLevel: Int?, asr: AsrAvailability.State, vad: VadAvailability.State): KeyValueFacts {
        val tierValue = shedLevel?.let { "${(MAX_TIER - it).coerceIn(0, MAX_TIER)} of $MAX_TIER" } ?: "Not measured"
        val asrPart = when (asr) {
            AsrAvailability.State.NotYetChecked -> "no model — see Models"
            is AsrAvailability.State.Available -> asr.modelRef
            is AsrAvailability.State.Unavailable -> "no model — see Models"
        }
        val vadPart = when (vad) {
            VadAvailability.State.Stub -> "energy VAD (not Silero)"
            VadAvailability.State.Real -> "Silero VAD"
            is VadAvailability.State.StubWithReason -> "energy VAD (not Silero)"
        }
        return KeyValueFacts(value = tierValue, subLine = "$asrPart · $vadPart")
    }

    private fun thermalFacts(thermal: ThermalStatus.State): KeyValueFacts {
        val rtf = thermal.realTimeFactor?.let { "RTF %.2f".format(Locale.ROOT, it) } ?: "RTF not yet measured"
        return when (thermal) {
            is ThermalStatus.State.Nominal -> KeyValueFacts(
                value = "Nominal",
                subLine = "$rtf · no throttling",
                trailingDot = CaptureStateTone.NOMINAL,
            )

            is ThermalStatus.State.Warm -> KeyValueFacts(
                value = "Warm",
                subLine = "$rtf · running warm",
                trailingDot = CaptureStateTone.DEGRADED,
            )

            is ThermalStatus.State.Hot -> KeyValueFacts(
                value = "Hot",
                subLine = "$rtf · throttling",
                trailingDot = CaptureStateTone.DEGRADED,
            )
        }
    }

    private fun storageFacts(storage: StorageForecast.State): KeyValueFacts {
        val gbText = "%.1f GB".format(Locale.ROOT, storage.audioDirectoryBytes / BYTES_PER_GIB)
        return when (storage) {
            is StorageForecast.State.NotYetMeasured ->
                KeyValueFacts(value = gbText, subLine = "not yet measured this session")

            is StorageForecast.State.Fine ->
                KeyValueFacts(value = gbText, subLine = "${nightsLeftLabel(storage.nightsLeft)} left")

            is StorageForecast.State.ThreeNightsLeft -> KeyValueFacts(
                value = gbText,
                subLine = "${nightsLeftLabel(storage.nightsLeft)} left",
                trailingDot = CaptureStateTone.DEGRADED,
            )

            is StorageForecast.State.OneNightLeft -> KeyValueFacts(
                value = gbText,
                subLine = "${nightsLeftLabel(storage.nightsLeft)} left",
                trailingDot = CaptureStateTone.DEGRADED,
            )

            is StorageForecast.State.AtFloor ->
                KeyValueFacts(value = gbText, subLine = "at the storage floor", trailingDot = CaptureStateTone.HALTED)
        }
    }

    private fun nightsLeftLabel(nightsLeft: Double): String =
        "${nightsLeft.toInt().coerceAtLeast(0)} night" + if (nightsLeft.toInt() == 1) "" else "s"

    private fun batteryFacts(percent: Int?, charging: Boolean, exemptionReportsIgnoring: Boolean): KeyValueFacts {
        val value = percent?.let { "$it% phone" + if (charging) " · charging" else "" } ?: "Not measured"
        // NFR-8 / constitution IV: isIgnoringBatteryOptimizations() lies on the reference device —
        // reported here, but explicitly labelled "not trusted", never as a liveness fact.
        val exemptionLabel = if (exemptionReportsIgnoring) {
            "exemption reports on — not trusted"
        } else {
            "exemption reports off — not trusted"
        }
        return KeyValueFacts(value = value, subLine = exemptionLabel)
    }

    private fun formatFrequencyMHz(hz: Long): String = "%.3f".format(Locale.ROOT, hz / 1_000_000.0)

    private fun notMeasuredFacts(reason: String): KeyValueFacts =
        KeyValueFacts(value = "Not measured", subLine = reason)
}
