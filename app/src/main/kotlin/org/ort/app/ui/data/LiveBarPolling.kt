package org.ort.app.ui.data

import android.content.Context
import org.ort.app.ui.components.LiveBarTone
import org.ort.app.ui.components.LiveBarViewState
import org.ort.data.OrtDatabase
import org.ort.data.entity.TranscriptPass
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.InputStatus
import org.ort.pipeline.capture.LevelStatus
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.capture.ShedStatus
import org.ort.pipeline.capture.StorageForecast
import org.ort.pipeline.capture.ThermalStatus

/**
 * The persistent live bar's read path (ui-conformance-plan WP4, guide §6.6, `Flow-Degrade.dc.html`)
 * — every screen the reader shows while a session runs pins [org.ort.app.ui.components.LiveBar] to
 * its own bottom edge, fed by [current] the same way [ReaderPolling]'s other read paths are polled.
 *
 * A new file, not an addition to `ReaderPolling.kt`: this package's row names it separately
 * (`ui-conformance-plan.md` §D), and it reads a genuinely different shape of state — the four
 * process-wide capture holders plus, at most, the newest few transmissions, rather than a whole
 * session's history.
 */
public object LiveBarPolling {

    /** Bounded so a 2 s poll never re-reads more than a handful of rows (guide's own budget for a
     * "live" surface) — the newest transmissions are the only ones that could carry a still-fresh
     * Pass A partial anyway. */
    private const val RECENT_TRANSMISSIONS_TO_CHECK = 3
    private const val LEVEL_BAR_COUNT = 4

    public suspend fun current(context: Context, sessionId: String?): LiveBarViewState {
        val (tone, label) = toneAndLabel()
        return LiveBarViewState(
            level = levelBars(),
            partialText = sessionId?.let { newestPassAPartial(context, it) },
            label = label,
            tone = tone,
        )
    }

    /**
     * `Main.dc.html`'s footer bars, now real (R-112 closed this — see the prior "not measured"
     * doc comment this replaced): [LevelStatus.State.NotMeasured] keeps the honest floor-height
     * placeholder (guide §6.6's "nothing to hear" rendering, never a fabricated waveform); once
     * measured, every bar is one of the two real numbers a single meter tick actually carries —
     * [LevelStatus.State.Measured.rmsDbfs] and [LevelStatus.State.Measured.peakDbfs], each
     * normalised against [LevelViewState.CHART_FLOOR_DBFS]/[LevelViewState.CHART_CEILING_DBFS] (the
     * same scale [LevelMeterScreen]'s own chart uses, so the two surfaces never disagree about what
     * "loud" means) and alternated `rms, peak, rms, peak` — never a fabricated 4-band spectrum this
     * one signal cannot honestly support. `clipped` needs no separate bar treatment: it already
     * routes `toneAndLabel()` to [LiveBarTone.DEGRADED] ("Hot"), and
     * [org.ort.app.ui.components.LiveBar]'s own palette colours a `DEGRADED` meter amber on that
     * tone alone (guide §6.6) — colour follows tone, not a second, redundant per-bar flag here.
     */
    private fun levelBars(): List<Float> = when (val level = LevelStatus.state) {
        LevelStatus.State.NotMeasured -> List(LEVEL_BAR_COUNT) { 0f }
        is LevelStatus.State.Measured -> {
            val rmsFraction = normalizedFraction(level.rmsDbfs)
            val peakFraction = normalizedFraction(level.peakDbfs)
            listOf(rmsFraction, peakFraction, rmsFraction, peakFraction)
        }
    }

    private fun normalizedFraction(dbfs: Float): Float {
        val floor = LevelViewState.CHART_FLOOR_DBFS
        val ceiling = LevelViewState.CHART_CEILING_DBFS
        return ((dbfs - floor) / (ceiling - floor)).coerceIn(0f, 1f)
    }

    /**
     * `Flow-Degrade.dc.html`'s own priority order: capture actually stopped outranks every
     * degradation, a stated tier drop outranks an unstated one, and a stale rig is the last, most
     * specific reason checked — the first real one found is shown, never stacked (guide §6.6: this
     * component "does not invent copy, it renders what it is given"). ui-conformance-plan WP11b
     * (register R-100) added the input/level/storage-warning/backlog branches — each board names
     * its own live-bar label verbatim: `Fail-Disconnect.dc.html` "Gap", `Fail-Level.dc.html`
     * "Quiet"/`Level-Meter.dc.html`'s clip case "Hot", `Fail-Backlog.dc.html` "N behind",
     * `Fail-Rig.dc.html` "Rig lost" (renamed from the placeholder "Radio disconnected" this file
     * carried before no board had been checked against).
     */
    private fun toneAndLabel(): Pair<LiveBarTone, String> {
        val captureState = CaptureState.state
        val shedLevel = ShedStatus.currentLevel
        val shedBacklog = ShedStatus.backlog
        val thermal = ThermalStatus.state
        val level = LevelStatus.state
        return when {
            captureState !is CaptureState.State.Capturing ->
                LiveBarTone.HALTED to if (captureState is CaptureState.State.Idle) "Not capturing" else "Halted"

            StorageForecast.state is StorageForecast.State.AtFloor -> LiveBarTone.HALTED to "Halted"

            InputStatus.state is InputStatus.State.Lost -> LiveBarTone.DEGRADED to "Gap"

            level is LevelStatus.State.Measured && level.clipped -> LiveBarTone.DEGRADED to "Hot"

            level is LevelStatus.State.Measured && level.peakDbfs <= QUIET_PEAK_THRESHOLD_DBFS ->
                LiveBarTone.DEGRADED to "Quiet"

            StorageForecast.state is StorageForecast.State.ThreeNightsLeft ||
                StorageForecast.state is StorageForecast.State.OneNightLeft ->
                LiveBarTone.DEGRADED to "Low storage"

            shedLevel > 0 -> LiveBarTone.DEGRADED to "Tier ${(MAX_TIER - shedLevel).coerceIn(0, MAX_TIER)}"

            thermal is ThermalStatus.State.Warm || thermal is ThermalStatus.State.Hot ->
                LiveBarTone.DEGRADED to "Running warm"

            shedBacklog >= BACKLOG_GROWING_THRESHOLD -> LiveBarTone.DEGRADED to "$shedBacklog behind"

            RigStatus.state is RigStatus.State.Stale -> LiveBarTone.DEGRADED to "Rig lost"

            else -> LiveBarTone.NOMINAL to "Live"
        }
    }

    /**
     * The newest Pass A transcript across the session's most recent few transmissions, or `null`
     * when none of them carry one yet. No `:data` change: `TranscriptDao` has no
     * "newest Pass A this session" query, and adding one is a `:data` file, outside this package's
     * row — this reuses the two read methods [ReaderPolling] already calls elsewhere
     * (`listBySession`, `getAllVersions`) instead.
     */
    private suspend fun newestPassAPartial(context: Context, sessionId: String): String? {
        val db = OrtDatabase.create(context.applicationContext)
        val recent = db.transmissionDao().listBySession(sessionId).takeLast(RECENT_TRANSMISSIONS_TO_CHECK)
        var newestText: String? = null
        var newestCreatedAt = Long.MIN_VALUE
        for (transmission in recent) {
            db.transcriptDao().getAllVersions(transmission.id)
                .asSequence()
                .filter { it.pass == TranscriptPass.A }
                .forEach { version ->
                    if (version.createdAt > newestCreatedAt) {
                        newestCreatedAt = version.createdAt
                        newestText = version.text
                    }
                }
        }
        return newestText
    }

    private const val MAX_TIER: Int = 3

    /** Matches `org.ort.app.ui.failures.FailureMapper.QUIET_PEAK_THRESHOLD_DBFS` — the same
     * threshold, so the live bar and the F3 banner never disagree about whether the level is
     * "too quiet". */
    private const val QUIET_PEAK_THRESHOLD_DBFS: Float = -30f

    /** Matches `org.ort.app.ui.failures.FailureMapper.BACKLOG_GROWING_THRESHOLD`. */
    private const val BACKLOG_GROWING_THRESHOLD: Int = 10
}
