package org.ort.app.status

import org.ort.pipeline.CaptureStatus
import org.ort.pipeline.capture.AsrAvailability
import org.ort.pipeline.capture.VadAvailability

/**
 * The status surface's render-ready state (FR-UI-7, FR-PLT-1 — build-plan P8). Kept as plain
 * data so it is testable without any Android view or Compose dependency; whatever renders it
 * (today, [org.ort.app.ui.screens.StatusScreen]) is thin glue over this.
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
 *
 * [shedLevel] and [backlog] (FR-RUN-5, audit F-002) are `null` — rendered as "Not measured" via
 * [shedLevelLabel]/[backlogLabel] — until [org.ort.pipeline.capture.ShedStatus] has actually
 * published a real reading from `RealCaptureService`'s `ShedController`. Before this fix, `:app`
 * built its own inert, always-nominal `ShedController` purely to have *something* to show, so the
 * surface could never read anything but "Nominal" — a fabricated value of exactly the kind
 * constitution IV forbids. A real `0` is a measured level; it must never stand in for "no one has
 * looked yet".
 */
public data class StatusViewState(
    val stateLabel: String,
    val elapsedLabel: String,
    val transmissionCount: Int,
    val gapCount: Int,
    val shedLevel: Int?,
    val shedLevelLabel: String,
    val livenessLabel: String,
    val uncleanEndBanner: String?,
    // R-031: these two fields used to carry their own "ASR: "/"VAD: " prefix baked in, and every
    // caller that displayed them (the old StatusScreen.LabeledLine("ASR", state.asrStatusLabel))
    // added a second one, rendering "ASR: ASR: unavailable — …". Neither field is displayed as a
    // labelled line on the reader any more (CaptureStatusScreen's Tier row carries the ASR/VAD
    // facts as a plain sub-line sentence instead — R-034), but the fields themselves are fixed
    // here too, at the source, so nothing that reads them next gets to reintroduce the bug by
    // pairing an unprefixed label with an already-prefixed value.
    val asrStatusLabel: String = "not started",
    val vadStatusLabel: String = "not started",
    val transcriptionUnavailableMessage: String? =
        "No transcription model installed — transcripts will not appear",
    val backlog: Int? = null,
    val backlogLabel: String = NOT_MEASURED_LABEL,
) {
    public companion object {
        public const val NOT_MEASURED_LABEL: String = "Not measured"
    }
}

public object StatusViewStateMapper {

    public fun from(
        status: CaptureStatus,
        asrState: AsrAvailability.State = AsrAvailability.State.NotYetChecked,
        vadState: VadAvailability.State = VadAvailability.State.Stub,
        // FR-RUN-5 / audit F-002: deliberately NOT `status.shedLevel` — that field is fed by
        // whatever `ShedController` the caller had to construct to satisfy
        // `CaptureStatusRepository`'s constructor, which historically was an inert, never-ticked
        // fake (see `ReaderPolling.statusRepository`'s doc comment). The real reading comes from
        // `org.ort.pipeline.capture.ShedStatus` and is passed in here explicitly; `null` means
        // "nothing has been measured yet", not "level zero".
        shedLevel: Int? = null,
        backlog: Int? = null,
    ): StatusViewState = StatusViewState(
        stateLabel = if (status.isCapturing) "Capturing" else "Idle",
        elapsedLabel = formatElapsed(status.elapsedMillis),
        transmissionCount = status.transmissionCount,
        gapCount = status.gapCount,
        shedLevel = shedLevel,
        shedLevelLabel = shedLevel?.let { shedLabel(it) } ?: StatusViewState.NOT_MEASURED_LABEL,
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
        backlog = backlog,
        backlogLabel = backlog?.let { "$it queued" } ?: StatusViewState.NOT_MEASURED_LABEL,
    )

    // R-031: no "ASR: "/"VAD: " prefix here — that was the other half of the doubled-prefix bug
    // (see asrStatusLabel/vadStatusLabel's own comment). A caller that wants a label prepends its
    // own, once.
    private fun asrLabel(state: AsrAvailability.State): String = when (state) {
        AsrAvailability.State.NotYetChecked -> "not started"
        is AsrAvailability.State.Available -> "available (${state.modelRef})"
        is AsrAvailability.State.Unavailable -> "unavailable — ${state.reason}"
    }

    private fun vadLabel(state: VadAvailability.State): String = when (state) {
        VadAvailability.State.Stub -> "energy fallback (not started)"
        VadAvailability.State.Real -> "Silero (real)"
        is VadAvailability.State.StubWithReason -> "energy fallback (${state.reason})"
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
