package org.ort.app.ui.data

import android.content.Context
import org.ort.app.ui.components.LiveBarTone
import org.ort.app.ui.components.LiveBarViewState
import org.ort.data.OrtDatabase
import org.ort.data.entity.TranscriptPass
import org.ort.pipeline.capture.CaptureState
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
            // R-039: no level signal exists in `:pipeline` yet (see
            // `org.ort.app.ui.data.LevelViewState`'s own kdoc) — four bars at their floor height is
            // the honest "idle" rendering guide §6.6 itself shows for "nothing to hear" (`Now-First.dc.html`'s
            // live bar), never a fabricated waveform.
            level = List(LEVEL_BAR_COUNT) { 0f },
            partialText = sessionId?.let { newestPassAPartial(context, it) },
            label = label,
            tone = tone,
        )
    }

    /**
     * `Flow-Degrade.dc.html`'s own priority order: capture actually stopped outranks every
     * degradation, a stated tier drop outranks an unstated one, and a stale rig is the last, most
     * specific reason checked — the first real one found is shown, never stacked (guide §6.6: this
     * component "does not invent copy, it renders what it is given").
     */
    private fun toneAndLabel(): Pair<LiveBarTone, String> {
        val captureState = CaptureState.state
        val shedLevel = ShedStatus.currentLevel
        val thermal = ThermalStatus.state
        return when {
            captureState !is CaptureState.State.Capturing ->
                LiveBarTone.HALTED to if (captureState is CaptureState.State.Idle) "Not capturing" else "Halted"

            StorageForecast.state is StorageForecast.State.AtFloor -> LiveBarTone.HALTED to "Halted"

            shedLevel > 0 -> LiveBarTone.DEGRADED to "Tier ${(MAX_TIER - shedLevel).coerceIn(0, MAX_TIER)}"

            thermal is ThermalStatus.State.Warm || thermal is ThermalStatus.State.Hot ->
                LiveBarTone.DEGRADED to "Running warm"

            RigStatus.state is RigStatus.State.Stale -> LiveBarTone.DEGRADED to "Radio disconnected"

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
}
