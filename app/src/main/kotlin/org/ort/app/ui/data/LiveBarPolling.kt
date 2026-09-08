package org.ort.app.ui.data

import android.content.Context
import org.ort.app.ui.components.LiveBarTone
import org.ort.app.ui.components.LiveBarViewState
import org.ort.app.ui.failures.DebugFailureOverride
import org.ort.app.ui.failures.FailurePresentation
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
        val override = failureOverrideLiveBar()
        val (tone, label) = if (override != null) override.tone to override.label else toneAndLabel()
        return LiveBarViewState(
            level = levelBars(),
            partialText = override?.partialText ?: sessionId?.let { newestPassAPartial(context, it) },
            label = label,
            tone = tone,
            meterTone = override?.meterTone,
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

    /** The result [failureOverrideLiveBar] returns for the (currently two) `FailurePresentation`
     * ids whose live bar cannot be computed from [toneAndLabel]'s real-signal reading alone.
     * [meterTone] mirrors [LiveBarViewState.meterTone] — `null` means "follow [tone]", same
     * contract as that field's own default. */
    private data class OverrideLiveBar(
        val tone: LiveBarTone,
        val label: String,
        val partialText: String?,
        val meterTone: LiveBarTone? = null,
    )

    /**
     * Register R-128: the live bar must follow every failure `FailureMapper` can raise, including
     * a debug override — `DebugFailureOverride.activeOverride` wins outright in `FailureMapper`'s
     * own priority (see that object's kdoc), so it is consulted here too, ahead of every
     * real-signal branch in [toneAndLabel]. Exhaustive over every `FailurePresentation` id this
     * project owns, so the mapping is documented and testable per F-id (this package's brief) —
     * two ids genuinely need an override here (`Fail-Usb.dc.html`'s live bar reads "audio fine ·
     * radio needs permission" in `halt/text` red — F16 has no real signal at all, only the debug
     * override; `Fail-Storage.dc.html`'s "text only" stage the same way, register R-100's own
     * "Left open" note on `StorageAudioPausedViewState`). Every other id falls through (`null`) to
     * [toneAndLabel]'s real-signal computation, either because its own board shows no live bar at
     * all (F14/F19/F20/F21/F22 are not Now/session screens) or because its board explicitly keeps
     * the live bar nominal while the informational card shows (F5/F15 both read "Live") — nothing
     * here invents a label a board never specified.
     *
     * F16 (register F16/R-128, WP2's `meterTone`): the rig needing USB permission is a permission
     * problem, not an audio one — `Fail-Usb.dc.html` shows the meter itself staying green ("audio
     * fine") while the label calls for action ("Act", `halt/text` red). `meterTone = NOMINAL`
     * carries that distinction through to [LiveBarViewState.meterTone]; every other override here
     * leaves it `null` (follow [tone]) because no other board draws that split.
     */
    private fun failureOverrideLiveBar(): OverrideLiveBar? =
        when (DebugFailureOverride.activeOverride) {
            is FailurePresentation.Usb -> OverrideLiveBar(
                tone = LiveBarTone.HALTED,
                label = "Act",
                partialText = "audio fine · radio needs permission",
                meterTone = LiveBarTone.NOMINAL,
            )
            is FailurePresentation.StorageAudioPaused ->
                OverrideLiveBar(tone = LiveBarTone.DEGRADED, label = "Text only", partialText = null)
            is FailurePresentation.Route, is FailurePresentation.Disconnect, is FailurePresentation.Level,
            is FailurePresentation.Killed, is FailurePresentation.StorageWarning, is FailurePresentation.StorageHalt,
            is FailurePresentation.Thermal, is FailurePresentation.Backlog, is FailurePresentation.Rig,
            is FailurePresentation.Call, is FailurePresentation.Clock, is FailurePresentation.Interrupted,
            is FailurePresentation.Reconcile, is FailurePresentation.Migration, is FailurePresentation.AssetSwap,
            is FailurePresentation.Calibration, FailurePresentation.None, null,
            -> null
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

            // Register R-149: this must match `FailureMapper.mapThermalOrBacklogOrRigBanner`'s own
            // order exactly (thermal, then backlog, then rig) — a bare `shedLevel > 0` check used
            // to sit here *before* backlog, so the `backlog` scenario (shed level 3, thermal
            // nominal) read "Tier 0" instead of "112 behind": found by V6, `backlog/F8-banner.png`.
            // `FailureMapper` has no standalone "tier dropped, no thermal reason" case at all — the
            // tier number is only ever shown *as the reason `Fail-Thermal.dc.html` gives*.
            thermal is ThermalStatus.State.Warm || thermal is ThermalStatus.State.Hot ->
                LiveBarTone.DEGRADED to "Tier ${(MAX_TIER - shedLevel).coerceIn(0, MAX_TIER)}"

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
