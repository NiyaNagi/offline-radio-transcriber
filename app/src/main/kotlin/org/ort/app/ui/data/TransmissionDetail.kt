package org.ort.app.ui.data

import org.ort.core.Attribution
import org.ort.core.AttributionState
import org.ort.core.TransmissionId
import org.ort.core.TransmissionState
import org.ort.data.entity.TranscriptPass
import org.ort.data.entity.TransmissionEntity
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

    /**
     * The single reconstruction of a type-safe [Attribution] (constitution I) from a
     * [TransmissionEntity]'s raw attribution columns — the one place
     * [org.ort.app.ui.data.ReaderPolling], [org.ort.app.ui.data.LogViewData],
     * [org.ort.app.ui.data.LiveMonitorViewData] and
     * [org.ort.app.ui.recordings.RecordingSessionViewStateMapper] now share, replacing four
     * independent copies of the identical function that had drifted: two (`LogViewData`,
     * `RecordingSessionViewStateMapper`) already carried the fix below (found and applied to each,
     * separately, on 2026-09-13); `ReaderPolling` and `LiveMonitorViewData` did not, so every
     * corrected transmission read as `UNKNOWN` on the reader/detail path (and therefore on
     * `org.ort.app.ui.data.ThreadPolling`, which reads through `ReaderPolling`, and on
     * `org.ort.app.ui.data.SearchPolling`, which reads through the same `detailFromEntity`) and on
     * the Live Monitor, even though the Log and a Recording Session already showed it correctly.
     * `org.ort.app.ui.data.CorrectionPolling.currentAttribution` patches the identical bug for the
     * one Detail-screen call site that reads it directly rather than through any of these four.
     *
     * **A human correction is checked first, and wins.**
     * [org.ort.data.dao.CorrectionDao.applyCorrectedAttribution] — the *only* write path a
     * correction ever takes — unconditionally writes `attributionState = INFERRED` with
     * `attributionConfidence = NULL`. The state-based branch below requires a non-null confidence
     * for `INFERRED`, so without this upfront check every corrected row would downgrade to
     * [Attribution.unknown], discarding the real, just-written [TransmissionEntity.stationId] the
     * moment it reached any of these screens — the exact bug this function closes.
     *
     * **Why a correction is `INFERRED`, never `CONFIRMED`, and carries no confidence**
     * (FR-SPK-13, constitution I): `CONFIRMED` means the callsign was heard and resolved *in this
     * transmission*. A correction is a human's after-the-fact judgement — typed, or picked from a
     * candidate list — and the callsign need not have been audible in this particular over at all,
     * so it may never be promoted to `CONFIRMED` regardless of how certain the operator is. There is
     * also no calibrated probability for "a human said so": [Attribution.confidence] is populated
     * only where one was actually computed, so a correction carries `null`, exactly as
     * [Attribution.withCorrection] already encodes.
     *
     * Falls back to [Attribution.unknown] for an *uncorrected* row, rather than throwing, if a
     * `CONFIRMED`/`INFERRED` state is ever missing the station or confidence its own factory
     * requires — a display concern must never crash a reader over a data inconsistency it did not
     * cause, but it must also never invent a station that was not actually written.
     */
    public fun attributionFrom(entity: TransmissionEntity): Attribution {
        correctedAttributionOrNull(entity)?.let { return it }
        val stationId = entity.stationId
        val confidence = entity.attributionConfidence
        return when (entity.attributionState) {
            AttributionState.CONFIRMED ->
                if (stationId != null && confidence != null) {
                    Attribution.confirmed(stationId, confidence)
                } else {
                    Attribution.unknown()
                }

            AttributionState.INFERRED ->
                if (stationId != null && confidence != null) {
                    Attribution.inferred(stationId, confidence, sourceId(entity))
                } else {
                    Attribution.unknown()
                }

            AttributionState.AMBIGUOUS -> Attribution.ambiguous()
            AttributionState.UNKNOWN -> Attribution.unknown()
        }
    }

    /**
     * Register R-1099: the one reconstruction of what a *correction* makes an attribution read as
     * — `null` when [entity] carries no correction, so a caller falls back to whatever it already
     * has for the uncorrected case. This is the fact [attributionFrom] (above) and
     * [org.ort.app.ui.data.CorrectionPolling.currentAttribution] now share, in place of each
     * separately hard-coding `Attribution.unknown().withCorrection(stationId)` — two copies of the
     * exact rule whose earlier divergence (one copy required a non-null confidence for INFERRED,
     * the other did not) was the R-1041-class defect this file's own [attributionFrom] doc comment
     * already describes at length. Public so `CorrectionPolling.kt` (a different package's file,
     * not extended by that fix) can call it directly rather than keeping its own copy.
     *
     * **Why [attributionFrom] and `currentAttribution` still differ beyond this shared call, and
     * why that is not the same bug again (register R-1099):** for an *uncorrected* row,
     * [attributionFrom] reconstructs the whole [Attribution] from [entity]'s own
     * `attributionState`/`attributionConfidence` columns; `currentAttribution` instead returns
     * whatever `fallback: Attribution` its caller already computed (`ReaderPolling`'s own, already
     * unified read). That is a genuine, tested difference in *shape*, not two rules for the same
     * fact: `currentAttribution`'s whole reason to exist is to patch the one case (a correction)
     * where a caller-supplied fallback can be wrong, while trusting that fallback everywhere else
     * rather than re-deriving it a second, possibly-inconsistent way. `CorrectionPollingTest`'s own
     * `R_189_currentAttribution_leaves_an_uncorrected_row_to_the_resolvers_own_fallback` pins this
     * exact difference: it hands `currentAttribution` a `fallback` that deliberately does **not**
     * match what [attributionFrom] would independently reconstruct from the same row, and asserts
     * the fallback wins — proving the two functions are not silently reconverging into a second
     * "reconstruct from scratch" implementation by accident.
     */
    public fun correctedAttributionOrNull(entity: TransmissionEntity): Attribution? =
        correctedAttributionOrNull(entity.stationId, entity.corrected)

    /**
     * The same rule as the [TransmissionEntity]-taking overload above, for a caller that already
     * holds just [stationId]/[corrected] rather than a whole entity — [DetailRevisionsScreen]
     * (register R-1142) is exactly such a caller: a Compose file with no `:data` access, whose own
     * [org.ort.app.ui.data.TranscriptVersionViewState] already carries these two fields (sourced
     * from this same entity's own columns — see that type's own doc comment) and nothing more. The
     * entity-taking overload now defers to this one so there is still only one place the rule
     * itself is written, never a second reconstruction beside it.
     */
    public fun correctedAttributionOrNull(stationId: String?, corrected: Boolean): Attribution? =
        if (corrected && stationId != null) Attribution.unknown().withCorrection(stationId) else null

    private fun sourceId(entity: TransmissionEntity): TransmissionId? =
        entity.attributionSourceTransmissionId?.let { runCatching { TransmissionId.parse(it) }.getOrNull() }
}

// NowSummaryViewState/NowSummaryMapper moved to ui/data/NowSummaryMapper.kt — WP4's own file per
// ui-conformance-plan.md §D's WP4 row, which names "the NowSummaryMapper file" as a file distinct
// from this one even though this one (WP5's, in full otherwise) is where they used to live.
