package org.ort.app.ui.data

import org.ort.core.Attribution
import org.ort.core.TransmissionState
import org.ort.data.entity.TranscriptPass
import java.util.Locale

/**
 * Real, per-transmission facts as they exist in `:data` right now (build-plan P14) —
 * Compose-agnostic, mirroring `TransmissionRow`'s own separation from the Room entity
 * (build-plan P11's doc comment on [org.ort.app.transmissions.TransmissionRow]).
 *
 * [currentTranscriptText] is `null` exactly when no [org.ort.data.entity.TranscriptEntity] row
 * exists yet for this transmission — the honest "captured, not yet transcribed" state (this
 * prompt's stated empty case), never an empty string standing in for "nothing happened yet".
 * [supersededTranscriptTexts] is every earlier version, oldest first, **excluding** the current
 * one — present only when a pass genuinely superseded an earlier transcript (FR-UI-1: "a
 * superseded partial is visibly superseded rather than silently replaced").
 */
public data class TransmissionDetail(
    val id: String,
    val startedAtUtcMillis: Long,
    val frequencyHz: Long?,
    val durationMs: Long,
    val signalStrength: Double?,
    val attribution: Attribution,
    val currentTranscriptText: String?,
    val supersededTranscriptTexts: List<String>,
    val hasAudio: Boolean,
    /**
     * The real `transmission.threadId` column (build-plan P15, FR-UI-2). Nothing populates it
     * yet — threading is M6 — so this is `null` for every transmission today; carried through
     * honestly rather than omitted, so [org.ort.app.ui.data.ThreadGroupingMapper] can render the
     * true "not yet grouped" state instead of fabricating conversations the data does not
     * support.
     */
    val threadId: String? = null,
    /** Build-plan P16, FR-OBS-4: the labelled-sample defaults need the session a transmission came from. */
    val sessionId: String = "",
    /** The transmission's position in its session, in samples at the capture rate (technical design §8). */
    val samplePosition: Long = 0L,
    /** Build-plan P16, FR-UI-8: the resolver's lattice/candidates/per-prior breakdown, if recorded. */
    val inspection: InspectionViewState = InspectionViewState.EMPTY,
    /**
     * The real `transmission.processingState` column (FR-RUN-7/FR-RUN-9). Audit F-003: a
     * `FAILED` or `REJECTED` transmission has [currentTranscriptText] `null` for exactly the
     * same reason a merely-still-pending `CAPTURED`/`PROCESSING` one does, so the reader needs
     * this to tell "will never have a transcript" from "does not have one yet". Defaults to
     * `CAPTURED` only for call sites that do not (yet) carry the real column — never used to
     * fabricate a terminal state.
     */
    val processingState: TransmissionState = TransmissionState.CAPTURED,
    /**
     * The real `transmission.rejectionReason` column, non-null only when [processingState] is
     * `REJECTED` (FR-RUN-9). A `FAILED` transmission's queue-side `lastError` is not carried
     * here: the reader's read path (`:app`'s [org.ort.app.ui.data.ReaderPolling]) reaches a
     * transmission by id, not by `(transmissionId, pass)`, and `:data`'s
     * `WorkQueueDao.findByTransmissionAndPass` needs the pass id to look one up — surfacing it
     * would need a new `:data` query this fix does not add. The `FAILED` label below is
     * therefore a state label without the underlying error text; see CHANGELOG for this date.
     */
    val rejectionReason: String? = null,
    /**
     * R-041 (ui-conformance WP5): which pass produced [currentTranscriptText], `null` exactly
     * when [currentTranscriptText] is `null` (no transcript row exists yet at all). This is the
     * structural signal [org.ort.app.ui.data.LogViewData] uses to tell a still-streaming Pass A
     * partial ("hearing…") from a Pass B/reprocess text still waiting on attribution
     * ("resolving…") from a genuinely finished row — never guessed from timing.
     */
    val currentTranscriptPass: TranscriptPass? = null,
    /**
     * R-040 (ui-conformance WP5): the real `transmission.corrected` column (FR-SPK-7). A human
     * override always outranks the `REVISED`/`NEW` badges a row could otherwise carry — see
     * [LogViewData]'s badge precedence.
     */
    val corrected: Boolean = false,
    /**
     * R-161 (ui-conformance WP5): the real `transmission.mode` column, `null` when the rig/CAT
     * link never reported one. [ui/data/ReaderPolling.kt]'s own `detailFrom` (WP4's file, not this
     * package's) does not set this field yet — a caller needing it re-attaches it from a direct
     * `entity.mode` read of its own (see `ThreadPolling`'s doc comment) rather than fabricating it.
     */
    val mode: String? = null,
)

/** One row of the live/log view — everything [org.ort.app.ui.screens.LogScreen] renders. */
public data class TransmissionListEntryViewState(
    val id: String,
    val timeLabel: String,
    val frequencyLabel: String,
    val transcriptText: String,
    val attribution: Attribution,
    /** Non-null exactly when this transmission's current transcript superseded an earlier one. */
    val revisionNote: String?,
    val signalLabel: String?,
)

/** The transmission detail (drill-in) view — everything [org.ort.app.ui.screens.TransmissionDetailScreen] renders. */
public data class TransmissionDetailViewState(
    val id: String,
    val timeLabel: String,
    val frequencyLabel: String,
    val durationLabel: String,
    val signalLabel: String?,
    val attribution: Attribution,
    val transcriptText: String,
    val revisionHistory: List<String>,
    val hasAudio: Boolean,
    /**
     * FR-UI-8: the resolver's lattice/candidates/per-prior breakdown. Honestly empty until a
     * future pass writes [org.ort.data.entity.PhoneticLatticeEntity] /
     * [org.ort.data.entity.CallsignCandidateEntity] rows.
     */
    val inspection: InspectionViewState = InspectionViewState.EMPTY,
    /** FR-OBS-4 labelled-sample defaults, derived from the real transmission this screen is showing. */
    val sessionId: String = "",
    val startSample: Long = 0L,
    val endSample: Long = 0L,
)

public object ReaderTransmissionViewStateMapper {

    private const val NOT_YET_TRANSCRIBED = "(captured, not yet transcribed)"
    private const val TRANSCRIPTION_FAILED = "(transcription failed)"
    private const val REJECTED_NO_REASON = "(rejected — no reason recorded)"

    public fun listEntry(detail: TransmissionDetail): TransmissionListEntryViewState = TransmissionListEntryViewState(
        id = detail.id,
        timeLabel = timeLabel(detail.startedAtUtcMillis),
        frequencyLabel = frequencyLabel(detail.frequencyHz),
        transcriptText = transcriptLabel(detail),
        attribution = detail.attribution,
        revisionNote = revisionNote(detail.supersededTranscriptTexts.size),
        signalLabel = signalLabel(detail.signalStrength),
    )

    /**
     * FR-RUN-9 (audit F-003): a transcript exists, or it doesn't for one of three genuinely
     * different reasons — still pending, permanently failed, or correctly rejected — and each
     * MUST read differently (constitution I, VII's text-not-colour accessibility floor).
     *
     * Public (R-041, ui-conformance WP5): [org.ort.app.ui.data.LogViewData] reuses this exact
     * mapping for its own `:data`-direct read path rather than re-deriving the same three-way
     * distinction a second time.
     */
    public fun transcriptLabel(detail: TransmissionDetail): String {
        detail.currentTranscriptText?.let { return it }
        return when (detail.processingState) {
            TransmissionState.FAILED -> TRANSCRIPTION_FAILED
            TransmissionState.REJECTED -> detail.rejectionReason?.let { "(rejected: $it)" } ?: REJECTED_NO_REASON
            TransmissionState.CAPTURED, TransmissionState.PROCESSING, TransmissionState.COMPLETE -> NOT_YET_TRANSCRIBED
        }
    }

    /** Samples per millisecond at the capture rate every retained transmission is stored at (technical design §5). */
    private const val SAMPLES_PER_MS = 16L

    public fun detailView(detail: TransmissionDetail): TransmissionDetailViewState = TransmissionDetailViewState(
        id = detail.id,
        timeLabel = timeLabel(detail.startedAtUtcMillis),
        frequencyLabel = frequencyLabel(detail.frequencyHz),
        durationLabel = "%.1fs".format(Locale.ROOT, detail.durationMs / 1000.0),
        signalLabel = signalLabel(detail.signalStrength),
        attribution = detail.attribution,
        transcriptText = transcriptLabel(detail),
        revisionHistory = detail.supersededTranscriptTexts,
        hasAudio = detail.hasAudio,
        inspection = detail.inspection,
        sessionId = detail.sessionId,
        startSample = detail.samplePosition,
        endSample = detail.samplePosition + detail.durationMs * SAMPLES_PER_MS,
    )

    /**
     * Exposed for [org.ort.app.ui.data.ThreadGroupingMapper], which cross-references
     * transmissions by time (FR-UI-2).
     */
    public fun timeLabelFor(detail: TransmissionDetail): String = timeLabel(detail.startedAtUtcMillis)

    /** `"23:32"` — the empty state's "Listening since HH:MM" (R-045) and the filter sheet's from/to fields. */
    public fun hourMinuteLabel(utcMillis: Long): String {
        val totalMinutes = utcMillis / 60_000
        val hours = (totalMinutes / 60) % 24
        val minutes = totalMinutes % 60
        return "%02d:%02d".format(Locale.ROOT, hours, minutes)
    }

    private fun revisionNote(supersededCount: Int): String? = when {
        supersededCount <= 0 -> null
        supersededCount == 1 -> "revised · 1 earlier version"
        else -> "revised · $supersededCount earlier versions"
    }

    /** Public (R-041/R-042, ui-conformance WP5): reused by [org.ort.app.ui.data.LogViewData]'s own read path. */
    public fun frequencyLabel(frequencyHz: Long?): String =
        frequencyHz?.let { "%.3f".format(Locale.ROOT, it / 1_000_000.0) } ?: "—"

    /** Public (R-041, ui-conformance WP5): reused by [org.ort.app.ui.data.LogViewData]'s own read path. */
    public fun signalLabel(signalStrength: Double?): String? = signalStrength?.let { "S%.0f".format(Locale.ROOT, it) }

    /**
     * R-249/R-331 (ui-conformance WP5): the one duration formatter every "how long" label in the
     * reader shares — "0.4 s" under a second (R-331, V3 pass 3 @16172f0: whole-second rounding was
     * silently dropping a 0.4 s rejected segment's own duration to "0 s"), "38 s" from a second up
     * to a minute, "1 m 55 s" at or beyond one (a space before every unit — the board's own
     * `Rows.dc.html`/`Thread-Detail.dc.html` wording; `Log-Rejected.dc.html`'s own static mockup
     * literally writes the unspaced "0.4s", but the register's own R-331 text asks for "0.4 s" —
     * this app's one established convention, not that one mockup's literal). Before this existed,
     * [org.ort.app.ui.data.LogViewData]'s gap-row formatter wrote "38s" (no space) and
     * [org.ort.app.ui.data.ThreadViewData]'s span formatter wrote the correct spaced form
     * independently — two implementations of one fact were exactly how they could drift apart.
     */
    public fun durationLabel(millis: Long): String {
        val clamped = millis.coerceAtLeast(0)
        if (clamped < 1000L) return "%.1f s".format(Locale.ROOT, clamped / 1000.0)
        val totalSeconds = clamped / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) "$minutes m $seconds s" else "$seconds s"
    }

    /** Public (R-040/R-041, ui-conformance WP5): reused by [org.ort.app.ui.data.LogViewData]'s own read path. */
    public fun timeLabel(startedAtUtcMillis: Long): String {
        val totalSeconds = startedAtUtcMillis / 1000
        val hours = (totalSeconds / 3600) % 24
        val minutes = (totalSeconds / 60) % 60
        val seconds = totalSeconds % 60
        return "%02d:%02d:%02d".format(Locale.ROOT, hours, minutes, seconds)
    }
}

// NowSummaryViewState/NowSummaryMapper moved to ui/data/NowSummaryMapper.kt — WP4's own file per
// ui-conformance-plan.md §D's WP4 row, which names "the NowSummaryMapper file" as a file distinct
// from this one even though this one (WP5's, in full otherwise) is where they used to live.
