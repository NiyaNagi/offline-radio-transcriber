package org.ort.app.ui.data

import org.ort.pipeline.capture.AsrAvailability
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.InputStatus
import org.ort.pipeline.capture.LevelStatus
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
 * (constitution I). Input and Level were both "not measured" on every build when this file was
 * first written — WP11c has since added [InputStatus]/[LevelStatus] (register R-112/R-113), the
 * same process-wide-holder pattern [CaptureState]/[ThermalStatus]/[RigStatus]/[StorageForecast]
 * already use, and [from] now reads them for real. `LevelViewState`
 * ([org.ort.app.ui.screens.LevelMeterScreen], R-039) carries the equivalent honesty for the full
 * meter screen — its own kdoc covers `NotMeasured`.
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

/**
 * `Level-Meter.dc.html`'s facts (R-039/R-112). [LevelStatus] now publishes a real reading
 * (WP11c) — [notMeasuredReason] is non-null, and every other field `null`/empty, only for the
 * genuinely-honest case: [LevelStatus.State.NotMeasured], before the first frame this session
 * (guide §6.8: a missing capability is *failed*, not *empty*), never fabricated bars.
 *
 * [TARGET_BAND_TOP_DBFS]/[TARGET_BAND_BOTTOM_DBFS] are the artboard's own fixed green band (a
 * target zone the operator turns the radio's volume to sit inside, like a speedometer's redline)
 * — a UI reference, not a per-session measurement, so it is a constant here rather than something
 * [LevelViewStateMapper] derives from a reading.
 */
public data class LevelViewState(
    val inputLabel: String,
    val peakDbfsLabel: String?,
    val noiseFloorDbfsLabel: String?,
    /** The raw value [noiseFloorDbfsLabel] formats — kept alongside it so the chart never has to
     * parse display text back into a number. */
    val noiseFloorDbfsRaw: Float?,
    val headroomLabel: String?,
    val clippedLastSecondLabel: String?,
    /** Oldest-first, up to 60 entries — [LevelStatus.peakHistoryDbfs] carried straight through. */
    val historyDbfs: List<Float>,
    /** R-175: `null` when no over this session recorded a signal strength at all — see
     * [ReaderPolling.weakestOverLabel]'s own kdoc for why this is never a fabricated dBFS pairing. */
    val weakestOverLabel: String? = null,
    /** R-175: the bottom state sentence with its dot ("In the band. Nothing to adjust.") — `null`
     * exactly when [notMeasuredReason] is non-null (nothing to judge against the target band yet). */
    val bandStateSentence: String? = null,
    val bandStateTone: CaptureStateTone? = null,
    val notMeasuredReason: String?,
) {
    public companion object {
        public const val TARGET_BAND_TOP_DBFS: Float = -12f
        public const val TARGET_BAND_BOTTOM_DBFS: Float = -18f
        public const val CHART_CEILING_DBFS: Float = 0f
        public const val CHART_FLOOR_DBFS: Float = -60f

        /** The only real producer today — see this type's own kdoc for why. */
        public fun notMeasured(inputLabel: String = "No input device signal published yet"): LevelViewState =
            LevelViewState(
                inputLabel = inputLabel,
                peakDbfsLabel = null,
                noiseFloorDbfsLabel = null,
                noiseFloorDbfsRaw = null,
                headroomLabel = null,
                clippedLastSecondLabel = null,
                historyDbfs = emptyList(),
                notMeasuredReason = "No level (dBFS) signal has been measured yet this session. Audio is still " +
                    "captured and kept — this is only the meter, not capture itself.",
            )
    }
}

public object LevelViewStateMapper {

    /**
     * [history] is [LevelStatus.peakHistoryDbfs] — read alongside [LevelStatus.state] by the
     * caller (never derived here), so both always belong to the same snapshot (see that object's
     * own kdoc on why they are read together). [weakestOverLabel] is
     * [ReaderPolling.weakestOverLabel]'s own result, threaded through rather than queried here —
     * this mapper stays pure, no `Context`.
     */
    public fun from(
        level: LevelStatus.State,
        history: List<Float>,
        inputLabel: String,
        weakestOverLabel: String? = null,
    ): LevelViewState = when (level) {
        LevelStatus.State.NotMeasured -> LevelViewState.notMeasured(inputLabel)
        is LevelStatus.State.Measured -> LevelViewState(
            inputLabel = inputLabel,
            peakDbfsLabel = "%.0f dBFS".format(Locale.ROOT, level.peakDbfs),
            noiseFloorDbfsLabel = level.noiseFloorDbfs?.let { "%.0f dBFS".format(Locale.ROOT, it) },
            noiseFloorDbfsRaw = level.noiseFloorDbfs,
            headroomLabel = "%.0f dB".format(Locale.ROOT, LevelViewState.CHART_CEILING_DBFS - level.peakDbfs),
            clippedLastSecondLabel = "${level.clipCountLastSecond}",
            historyDbfs = history,
            weakestOverLabel = weakestOverLabel,
            bandStateSentence = bandStateSentence(level),
            bandStateTone = bandStateTone(level),
            notMeasuredReason = null,
        )
    }

    /**
     * R-175: `Level-Meter.dc.html`'s bottom sentence — the one place this screen tells the operator
     * whether the radio's volume knob needs turning, judged against [LevelViewState]'s own fixed
     * target band, never a fabricated recommendation beyond what [level] actually measured.
     */
    private fun bandStateSentence(level: LevelStatus.State.Measured): String = when {
        level.clipped -> "Clipping. Turn the volume down."
        level.peakDbfs > LevelViewState.TARGET_BAND_TOP_DBFS -> "Above the band. Turn the volume down."
        level.peakDbfs < LevelViewState.TARGET_BAND_BOTTOM_DBFS -> "Below the band. Turn the volume up."
        else -> "In the band. Nothing to adjust."
    }

    private fun bandStateTone(level: LevelStatus.State.Measured): CaptureStateTone = when {
        level.clipped -> CaptureStateTone.HALTED
        level.peakDbfs > LevelViewState.TARGET_BAND_TOP_DBFS -> CaptureStateTone.DEGRADED
        level.peakDbfs < LevelViewState.TARGET_BAND_BOTTOM_DBFS -> CaptureStateTone.DEGRADED
        else -> CaptureStateTone.NOMINAL
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
        input: InputStatus.State,
        level: LevelStatus.State,
        nowMillis: Long,
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
            input = inputFacts(input, nowMillis),
            level = levelFacts(level),
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
     * `Capture-Status.dc.html`'s Input row (R-113): device name, native rate and resampler id from
     * [InputStatus.State.Opened], "verified" only once [InputStatus.State.Opened.routeVerified]
     * is actually `true` (constitution IV — never claim a route verified before the OS has
     * confirmed it), "mismatch — halted" for [InputStatus.State.Mismatch] (capture does not
     * continue on it — see that state's own kdoc), and "lost Ns ago" for
     * [InputStatus.State.Lost], computed from [InputStatus.State.Lost.sinceMillis] against the
     * device's current wall clock at read time.
     */
    private fun inputFacts(input: InputStatus.State, nowMillis: Long): KeyValueFacts = when (input) {
        InputStatus.State.None -> KeyValueFacts(value = "Not measured", subLine = "no input opened yet this session")

        is InputStatus.State.Opened -> KeyValueFacts(
            value = input.descriptor.label,
            subLine = "${kHzLabel(input.nativeRateHz)} native · resampler ${input.resamplerId}",
            trailingDot = if (input.routeVerified && input.routedDeviceMatches) {
                CaptureStateTone.NOMINAL
            } else {
                CaptureStateTone.DEGRADED
            },
            trailingText = if (input.routeVerified && input.routedDeviceMatches) "verified" else "verifying…",
        )

        is InputStatus.State.Mismatch -> KeyValueFacts(
            value = input.expected.label,
            subLine = "routed to ${input.actual?.label ?: "an unknown device"} instead",
            trailingDot = CaptureStateTone.HALTED,
            trailingText = "mismatch — halted",
        )

        is InputStatus.State.Lost -> KeyValueFacts(
            value = input.lastKnown.descriptor.label,
            subLine = "${kHzLabel(input.lastKnown.nativeRateHz)} native · resampler ${input.lastKnown.resamplerId}",
            trailingDot = CaptureStateTone.DEGRADED,
            trailingText = "lost ${secondsAgo(input.sinceMillis, nowMillis)}s ago",
        )
    }

    /**
     * `Capture-Status.dc.html`'s Level row (R-112): peak/RMS dBFS from [LevelStatus.State.Measured],
     * amber and named "clipping" when [LevelStatus.State.Measured.clipped] is true this tick —
     * never silently folded into the peak number, since a clipped sample is a distinct fact from a
     * merely loud one.
     */
    private fun levelFacts(level: LevelStatus.State): KeyValueFacts = when (level) {
        LevelStatus.State.NotMeasured ->
            KeyValueFacts(value = "Not measured", subLine = "no level signal yet this session")

        is LevelStatus.State.Measured -> {
            val noiseFloor = level.noiseFloorDbfs?.let { "noise %.0f dBFS".format(Locale.ROOT, it) }
                ?: "noise floor not yet tracked"
            KeyValueFacts(
                value = "%.0f dBFS peaks".format(Locale.ROOT, level.peakDbfs),
                subLine = "RMS %.0f dBFS · $noiseFloor".format(Locale.ROOT, level.rmsDbfs),
                trailingDot = if (level.clipped) CaptureStateTone.DEGRADED else null,
                trailingText = if (level.clipped) "clipping" else null,
            )
        }
    }

    private fun kHzLabel(rateHz: Int): String = "%.0f kHz".format(Locale.ROOT, rateHz / 1000.0)

    private fun secondsAgo(sinceMillis: Long, nowMillis: Long): Long =
        ((nowMillis - sinceMillis) / 1000).coerceAtLeast(0)

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
        // R-263: guide §9 — operator copy never carries a spec id. "FR-RIG" named the requirement,
        // not a fact this screen's own operator would recognise; "no radio support in this build
        // yet" says the same real thing (the rig module is genuinely unbuilt — register R-084)
        // without it.
        RigStatus.State.Absent ->
            KeyValueFacts(value = "No rig configured", subLine = "no radio support in this build yet")
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
}
