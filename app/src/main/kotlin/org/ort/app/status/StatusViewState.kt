package org.ort.app.status

import org.ort.pipeline.CaptureStatus
import org.ort.pipeline.capture.AsrAvailability
import org.ort.pipeline.capture.VadAvailability

/**
 * The status surface's render-ready state (FR-UI-7, FR-PLT-1 — build-plan P8). Kept as plain
 * data so it is testable without any Android view or Compose dependency; whatever renders it
 * (a Compose screen or, as built here, plain `TextView`s — see [org.ort.app.status.StatusActivity])
 * is thin glue over this.
 *
 * The four states a gap can be shown in are distinguishable **without colour** (constitution
 * VII's accessibility floor): [gapCount] is a plain number and [uncleanEndBanner] is text, not a
 * colour swatch.
 *
 * [asrStatusLabel] and [vadStatusLabel] carry FR-UI-7's "current tier" fact for transcription
 * specifically (audit F-004): today no ASR/VAD model can be installed at all (build-plan P18 has
 * not shipped model fetch), so the honest default is "not started", never a healthy-looking
 * default — see [StatusViewStateMapper.from]. [transcriptionUnavailableMessage] is the one-line
 * summary [org.ort.app.ui.screens.NowScreen] shows in its header when transcripts will not appear
 * for this reason; it is `null` only once a real model is actually loaded.
 */
public data class StatusViewState(
    val stateLabel: String,
    val elapsedLabel: String,
    val transmissionCount: Int,
    val gapCount: Int,
    val shedLevel: Int,
    val shedLevelLabel: String,
    val livenessLabel: String,
    val uncleanEndBanner: String?,
    val asrStatusLabel: String = "ASR: not started",
    val vadStatusLabel: String = "VAD: not started",
    val transcriptionUnavailableMessage: String? =
        "No transcription model installed — transcripts will not appear",
)

public object StatusViewStateMapper {

    public fun from(
        status: CaptureStatus,
        asrState: AsrAvailability.State = AsrAvailability.State.NotYetChecked,
        vadState: VadAvailability.State = VadAvailability.State.Stub,
    ): StatusViewState = StatusViewState(
        stateLabel = if (status.isCapturing) "Capturing" else "Idle",
        elapsedLabel = formatElapsed(status.elapsedMillis),
        transmissionCount = status.transmissionCount,
        gapCount = status.gapCount,
        shedLevel = status.shedLevel,
        shedLevelLabel = shedLabel(status.shedLevel),
        // Liveness is shown from the heartbeat only (AC-65) — never from a battery API.
        livenessLabel = if (status.isAlive) "Alive (heartbeat current)" else "Not responding (heartbeat stale)",
        uncleanEndBanner = status.uncleanEndFromPreviousLaunch?.let {
            "The previous session ended unexpectedly. Last heartbeat: ${formatWallMillis(it.lastHeartbeatWallMillis)}."
        },
        asrStatusLabel = asrLabel(asrState),
        vadStatusLabel = vadLabel(vadState),
        // CONFIRMED-style optimism is forbidden here too (constitution I): only an actually loaded
        // model clears this message. NotYetChecked and Unavailable both keep it, honestly.
        transcriptionUnavailableMessage = if (asrState is AsrAvailability.State.Available) {
            null
        } else {
            "No transcription model installed — transcripts will not appear"
        },
    )

    private fun asrLabel(state: AsrAvailability.State): String = when (state) {
        AsrAvailability.State.NotYetChecked -> "ASR: not started"
        is AsrAvailability.State.Available -> "ASR: available (${state.modelRef})"
        is AsrAvailability.State.Unavailable -> "ASR: unavailable — ${state.reason}"
    }

    private fun vadLabel(state: VadAvailability.State): String = when (state) {
        VadAvailability.State.Stub -> "VAD: energy fallback (not started)"
        VadAvailability.State.Real -> "VAD: Silero (real)"
        is VadAvailability.State.StubWithReason -> "VAD: energy fallback (${state.reason})"
    }

    private fun shedLabel(level: Int): String = when (level) {
        0 -> "Nominal"
        1 -> "Level 1 — live hypothesis paused"
        2 -> "Level 2 — speaker identity paused"
        3 -> "Level 3 — offline model downgraded"
        4 -> "Level 4 — processing deferred, capture continues"
        5 -> "Level 5 — storage exhausted, capture stopped"
        else -> "Level $level"
    }

    private fun formatElapsed(elapsedMillis: Long): String {
        val totalSeconds = elapsedMillis / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }

    private fun formatWallMillis(wallMillis: Long): String {
        val instant = java.time.Instant.ofEpochMilli(wallMillis)
        return java.time.format.DateTimeFormatter.ISO_INSTANT.format(instant)
    }
}
