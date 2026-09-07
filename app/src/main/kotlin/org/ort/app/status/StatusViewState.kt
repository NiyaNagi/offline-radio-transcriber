package org.ort.app.status

import org.ort.pipeline.CaptureStatus

/**
 * The status surface's render-ready state (FR-UI-7, FR-PLT-1 — build-plan P8). Kept as plain
 * data so it is testable without any Android view or Compose dependency; whatever renders it
 * (a Compose screen or, as built here, plain `TextView`s — see [org.ort.app.status.StatusActivity])
 * is thin glue over this.
 *
 * The four states a gap can be shown in are distinguishable **without colour** (constitution
 * VII's accessibility floor): [gapCount] is a plain number and [uncleanEndBanner] is text, not a
 * colour swatch.
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
)

public object StatusViewStateMapper {

    public fun from(status: CaptureStatus): StatusViewState = StatusViewState(
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
    )

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
