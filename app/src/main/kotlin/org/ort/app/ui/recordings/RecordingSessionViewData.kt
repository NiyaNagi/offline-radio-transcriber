package org.ort.app.ui.recordings

import org.ort.core.Attribution
import org.ort.pipeline.archive.ArchiveState
import org.ort.pipeline.archive.SessionAudioDeletionRefusal
import org.ort.pipeline.archive.SessionAudioExportRefusal

/**
 * `Recording-Session.dc.html` (RC02, design-intent row RC02): view-states for one recording
 * session's own screen — every fact assembled by [RecordingSessionViewStateMapper]/
 * [RecordingSessionPolling] from real sources (`:pipeline`'s
 * `org.ort.pipeline.archive.recordingSessionSummaries`, the session's own transmission/gap DAOs,
 * [org.ort.pipeline.archive.SessionAudioDeletionService], [org.ort.pipeline.archive.SessionAudioExport]
 * and [org.ort.pipeline.label.TransmissionLabelRepository]) — no artboard example number is ever
 * hardcoded (constitution I).
 *
 * **Accepted deviation, recorded here (constitution VIII):** the artboard's coverage strip reads
 * "listened 8 h 07 m of 8 h 24 m" — no signal for "how much of this session's audio the operator
 * has actually listened to" exists anywhere in this schema (searched every `:data` entity before
 * writing this), and inventing one would be fabrication (constitution I: "never fabricate a
 * number"). [RecordingSessionCoverageViewState] omits it; the strip still carries every real fact
 * the board draws — the ticks (one per real over, amber for a real failure), the hatch (one per
 * real [org.ort.data.entity.CaptureGapEntity]) and the playhead (real only when the transport
 * controller is genuinely playing an over that belongs to this session).
 */
public data class RecordingSessionCoverageTick(
    public val fractionStart: Float,
    public val fractionEnd: Float,
    public val failed: Boolean,
)

public data class RecordingSessionCoverageGap(public val fractionStart: Float, public val fractionEnd: Float)

public data class RecordingSessionCoverageViewState(
    public val ticks: List<RecordingSessionCoverageTick>,
    public val gaps: List<RecordingSessionCoverageGap>,
    /** Real only when the transport controller's own loaded transmission belongs to this session
     * — never a fabricated "current position" (this screen is not live). */
    public val playheadFraction: Float?,
    /** Axis labels, real clock hours spanning the session — `emptyList()` for a session too short
     * to carry more than one label. */
    public val axisLabels: List<String>,
)

/** [RecordingSessionRow.Over]'s own render state, derived once from the real
 * [org.ort.core.TransmissionState] rather than left as several independent booleans a caller could
 * combine into an impossible state: [RESOLVED] (a transcript exists, whatever its attribution),
 * [PROCESSING] (still in flight — `CAPTURED`/`PROCESSING`), [REJECTED] (the segmenter or a pass
 * correctly declined it — P9: dimmed, not absent) and [FAILED] (`Retry` is offered, the audio is
 * kept). */
public enum class RecordingSessionOverStatus { RESOLVED, PROCESSING, REJECTED, FAILED }

/** One row of RC02's "Overs · in order" list — an over with its own play control, attribution and
 * label state, or a gap row with none, because nothing was recorded (FR-RUN-12). */
public sealed interface RecordingSessionRow {
    public val id: String

    public data class Over(
        override val id: String,
        public val timeLabel: String,
        public val frequencyLabel: String,
        public val durationLabel: String,
        public val status: RecordingSessionOverStatus,
        /** Real only for [RecordingSessionOverStatus.RESOLVED] — never a fabricated placeholder
         * for any other status (constitution I). */
        public val transcript: String?,
        public val attribution: Attribution?,
        public val callsign: String?,
        public val alternate: String?,
        public val attributionStateLabel: String?,
        public val inferredFromLabel: String?,
        public val hasAudio: Boolean,
        public val stationId: String?,
        /** "training · good" (`markedForTraining` + a real [rating]) — `null` when nothing to show. */
        public val trainingLabel: String?,
        public val markedForTraining: Boolean,
        /** [RecordingSessionOverStatus.REJECTED]'s own reason
         * ([org.ort.data.entity.TransmissionEntity.rejectionReason]), or [RecordingSessionOverStatus
         * .FAILED]'s own "Pass B errored 5 times" (the work queue's real attempt count) — `null` for
         * [RecordingSessionOverStatus.RESOLVED]/[RecordingSessionOverStatus.PROCESSING]. */
        public val statusReasonLabel: String?,
        /** `true` only when a real, currently-`FAILED` work-queue item exists for this
         * transmission — never offered against a stale or already-succeeded over. */
        public val canRetry: Boolean,
    ) : RecordingSessionRow

    public data class Gap(
        override val id: String,
        public val timeLabel: String,
        public val titleLabel: String,
        public val causeLabel: String,
        /** `null` for a gap this session ended inside of (never closed) — never a fabricated
         * resume time. */
        public val resumedLabel: String?,
        public val bluetoothAudioDropped: Boolean,
    ) : RecordingSessionRow
}

public data class RecordingSessionHeaderViewState(
    public val dateLabel: String,
    public val timeRangeLabel: String,
    public val durationLabel: String,
    /** "local microphone · room audio" / "USB-connected radio" — `null` on a pre-schema-v7
     * session this fact was never tracked for (constitution I: honest omission, never guessed). */
    public val modeLabel: String?,
    public val countsLabel: String,
)

/** RC02's Delete action (`SessionAudioTarget.BOTH` — the artboard offers one Delete, not a
 * per-target picker). [Idle] is the pre-tap state the header's own "frees N GB" caption reads
 * from directly ([RecordingSessionViewState.deleteFreesBytes]); this sealed type is the confirm
 * sheet's own state once Delete is tapped. */
public sealed interface RecordingSessionDeleteState {
    public data object Idle : RecordingSessionDeleteState

    /**
     * Round 2 (coordinator review): [bytesToFree] alone let the sheet repeat only the single
     * combined figure — the artboard's own comment ("the confirm sheet repeats it") plus D40/P9's
     * own "the operator sees what is removed" now shown as its own real breakdown too, named the
     * same as RC01's own budget card ("Over audio" / "Raw archive",
     * [org.ort.app.ui.recordings.RecordingsScreen]). [overAudioAlreadyRemoved]/[archiveState]
     * decide which line(s) the sheet shows at all — a half already gone, or an archive never kept,
     * is an honest omission, never a fabricated zero-byte line (constitution I).
     */
    public data class Preview(
        public val bytesToFree: Long,
        public val overAudioBytes: Long,
        public val overAudioAlreadyRemoved: Boolean,
        public val archiveBytes: Long,
        public val archiveState: ArchiveState,
    ) : RecordingSessionDeleteState

    /** Each typed refusal renders its own honest state — never a generic error (constitution II). */
    public data class Refused(public val reason: SessionAudioDeletionRefusal) : RecordingSessionDeleteState

    /** P9/constitution III: the session stays listed after this — [bytesFreed] and the two removal
     * timestamps are what [RecordingSessionPolling] re-reads immediately after, so the screen never
     * has to guess what changed. */
    public data class Deleted(
        public val bytesFreed: Long,
        public val overAudioRemovedAtMillis: Long?,
        public val archiveRemovedAtMillis: Long?,
    ) : RecordingSessionDeleteState
}

/** RC02's Export action (`SessionAudioExportTarget.BOTH`, mirroring [RecordingSessionDeleteState]'s
 * own single-target shape — the artboard offers one Export, not a per-target picker). */
public sealed interface RecordingSessionExportState {
    public data object Idle : RecordingSessionExportState

    public data class Preview(
        public val fileCount: Int,
        public val totalBytes: Long,
        public val suggestedFileName: String,
    ) : RecordingSessionExportState

    public data class Refused(public val reason: SessionAudioExportRefusal) : RecordingSessionExportState

    public data class Writing(public val bytesWritten: Long, public val totalBytes: Long) : RecordingSessionExportState

    public data class Written(public val fileCount: Int, public val totalBytes: Long) : RecordingSessionExportState
}

/** RC02's Label sheet — [markedForTraining] and [rating] are the two facts the per-over
 * "training · good" badge shows (`org.ort.data.entity.TransmissionLabelEntity`'s own doc comment). */
public data class RecordingSessionLabelSheetViewState(
    public val transmissionId: String,
    public val callsignLabel: String,
    public val markedForTraining: Boolean,
    public val rating: String?,
)

public data class RecordingSessionViewState(
    public val sessionId: String,
    public val header: RecordingSessionHeaderViewState,
    public val coverage: RecordingSessionCoverageViewState,
    public val rows: List<RecordingSessionRow>,
    /** The real bytes [org.ort.pipeline.archive.SessionAudioDeletionService.preview] reports right
     * now for `SessionAudioTarget.BOTH` — shown under the Delete action before it is tapped
     * (artboard: "frees 0.61 GB"), never an estimate (constitution VI). */
    public val deleteFreesBytes: Long,
    public val exportAvailable: Boolean,
)
