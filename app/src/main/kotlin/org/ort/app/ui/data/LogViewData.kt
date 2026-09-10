package org.ort.app.ui.data

import android.content.Context
import org.ort.app.ui.components.LogRowBadge
import org.ort.app.ui.components.LogRowPartial
import org.ort.app.ui.components.LogRowViewState
import org.ort.core.Attribution
import org.ort.core.AttributionState
import org.ort.core.SystemClock
import org.ort.core.TransmissionId
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.CaptureGapCause
import org.ort.data.entity.CaptureGapEntity
import org.ort.data.entity.TranscriptPass
import org.ort.data.entity.TransmissionEntity
import org.ort.pipeline.capture.RigStatus
import java.io.File

/**
 * R-040/R-041/R-042/R-043/R-045 (ui-conformance WP5): the Log's own view-state and read path —
 * every variant `Log.dc.html`/`Rows.dc.html` show (group headers, gaps, rejected rows, the `NEW`
 * badge computed from the *first ever* over heard from a station across every session, the
 * ambiguous `or QRF` alternate), the Pass A/B partial states `Log-Partial.dc.html` shows
 * (FR-UI-1, P5), and the `Log-Filter.dc.html` sheet's own filtering, pure and tested apart from
 * the database.
 *
 * [LogPolling] reads `:data` directly through [OrtDatabase] — **not** through
 * `ui/data/ReaderPolling.kt** (that file is WP4's; this package's own row explicitly says "never
 * via ReaderPolling.kt") — duplicating the small amount of entity→[TransmissionDetail] mapping
 * [org.ort.app.ui.data.ReaderPolling.detailFrom] also does, rather than depending on a file this
 * package does not own.
 */

// -------------------------------------------------------------------------------------------
// Screen view-state.
// -------------------------------------------------------------------------------------------

/** One row of the Log's interleaved list — a transmission, a QSO group header, a gap, or a rejected segment. */
public sealed interface LogListItem {
    public val key: String

    public data class Group(val threadId: String, val label: String) : LogListItem {
        override val key: String get() = "group-$threadId"
    }

    public data class Row(val state: LogRowViewState) : LogListItem {
        override val key: String get() = "row-${state.id}"
    }

    public data class Gap(val id: String, val timeLabel: String, val label: String) : LogListItem {
        override val key: String get() = "gap-$id"
    }

    public data class RejectedItem(
        val id: String,
        val timeLabel: String,
        val frequencyLabel: String,
        val reason: String,
        // R-043 (`Log-Rejected.dc.html`'s `.why` line): the rejection record's own elaboration in
        // prose, e.g. "Too short, segment is 120 ms, below the 250 ms floor" — never the raw rule
        // token. `null` exactly when the record carries nothing beyond [reason] itself.
        val why: String? = null,
        /**
         * R-242 (V3 pass 2 @de56368): the segment's own duration, through the shared
         * [ReaderTransmissionViewStateMapper.durationLabel]. Wired into `RejectedRow`'s own
         * `durationLabel` param (`ui/components/Rows.kt`, `LogScreen.kt`) now that main carries
         * WP2's DUR-column change — shown only in the dedicated Rejected view, the same gating
         * [why] already uses (the interleaved list stays the compact row it always was).
         */
        val durationLabel: String? = null,
    ) : LogListItem {
        override val key: String get() = "rejected-$id"
    }
}

/** guide §6.8: the Log-Empty state keeps the header/chips/columns — see `LogScreen`, not this type, for that. */
public data class LogEmptyStateViewState(val message: String, val subMessage: String)

/** The closed set of quick filters `Log.dc.html`'s chip row offers — All, each real frequency, Named, Rejected. */
public sealed interface LogQuickFilterId {
    public data object All : LogQuickFilterId
    public data class Frequency(val hz: Long) : LogQuickFilterId
    public data object Named : LogQuickFilterId
    public data object Rejected : LogQuickFilterId
}

public data class LogQuickFilterChipViewState(val id: LogQuickFilterId, val label: String, val selected: Boolean)

/**
 * Everything [org.ort.app.ui.screens.LogScreen] renders. [rejectedFocus] is true exactly when the
 * `Rejected` quick chip is active — `Log-Rejected.dc.html`'s dedicated view (its own column
 * headers and explanatory line, not merely an added filter dimension) — as distinct from the
 * filter sheet's own "Also show → Rejected segments" toggle, which interleaves rejected rows
 * into the normal chronological list instead of replacing it (see [LogFilterSelection.showRejected]).
 */
public data class LogScreenViewState(
    val items: List<LogListItem>,
    val quickFilters: List<LogQuickFilterChipViewState>,
    val rejectedFocus: Boolean,
    val rejectedExplanation: String?,
    val emptyState: LogEmptyStateViewState?,
    /** E2-G04 (F23, FR-CAP-13): `Fail-Bluetooth-Audio.dc.html`'s own footnote line — "Every over
     * captured over Bluetooth carries the `bt audio` mark. Its accuracy is reported separately
     * from cabled audio, never averaged in." Present exactly when this session's own audio route
     * is Bluetooth SCO; `null` (every caller before this existed) omits the line entirely. */
    val bluetoothAudioFootnote: String? = null,
)

// -------------------------------------------------------------------------------------------
// Filter sheet view-state.
// -------------------------------------------------------------------------------------------

/**
 * The filter actually applied to the Log (`Log-Filter.dc.html`). Defaults are the least
 * surprising, most honest reading of the screen with nothing narrowed: every attribution state
 * visible (constitution I — hiding `UNKNOWN` by default would conflate "nothing resolved" with
 * "nothing happened"), not-listening gaps interleaved (FR-UI-12 — a gap is data), rejected
 * segments **not** interleaved by default (R-043 — dimmed noise stays one chip away, not on by
 * default), no frequency or time narrowing.
 */
public data class LogFilterSelection(
    val frequencyHz: Long? = null,
    val attributionStates: Set<AttributionState> = AttributionState.entries.toSet(),
    val showRejected: Boolean = false,
    val showGaps: Boolean = true,
    val fromMillis: Long? = null,
    val toMillis: Long? = null,
)

public data class LogFrequencyOptionViewState(val hz: Long?, val label: String, val count: Int, val selected: Boolean)

public data class LogAttributionOptionViewState(
    val state: AttributionState,
    val label: String,
    val count: Int,
    val checked: Boolean,
)

/** Everything [org.ort.app.ui.screens.LogFilterSheet] renders. */
public data class LogFilterSheetViewState(
    val frequencyOptions: List<LogFrequencyOptionViewState>,
    val attributionOptions: List<LogAttributionOptionViewState>,
    val rejectedCount: Int,
    val rejectedShown: Boolean,
    val gapsCount: Int,
    val gapsShown: Boolean,
    val fromLabel: String,
    val toLabel: String,
    val matchingCount: Int,
)

// -------------------------------------------------------------------------------------------
// Pure mapping (DB-free — LogViewDataTest exercises this directly).
// -------------------------------------------------------------------------------------------

/**
 * Pure builders, deliberately separated from [LogPolling]'s database reads so every rule here —
 * partial detection, badge precedence, grouping, filtering, empty-state wording — is testable
 * without Robolectric or a real (or in-memory) [OrtDatabase].
 */
public object LogItemsMapper {

    // NoSpeechProbRule/CompressionRatioRule (asr-api) write "no_speech_prob=$p exceeds ceiling=$c" /
    // "compression ratio=$r exceeds ceiling=$c" — the two rejection details that are a raw
    // key=value pair rather than a sentence; [whyFor] reformats them via these two patterns.
    private val NO_SPEECH_PROB_PATTERN = Regex("""no_speech_prob=([\d.]+) exceeds ceiling=([\d.]+)""")
    private val COMPRESSION_RATIO_PATTERN = Regex("""compression ratio=([\d.]+) exceeds ceiling=([\d.]+)""")

    /** guide §6.14: a row carries at most one badge; a human correction always outranks a machine fact. */
    public fun badgeFor(detail: TransmissionDetail, isFirstHeard: Boolean, isPartial: Boolean): LogRowBadge? = when {
        isPartial -> null
        detail.corrected -> LogRowBadge.CORRECTED
        detail.supersededTranscriptTexts.isNotEmpty() -> LogRowBadge.REVISED
        isFirstHeard -> LogRowBadge.NEW
        else -> null
    }

    /** guide §6.13/FR-UI-1/P5: a still-streaming Pass A over, or a Pass B/reprocess text awaiting attribution. */
    public fun partialFor(detail: TransmissionDetail): LogRowPartial? {
        val stillCapturingOrProcessing =
            detail.processingState == TransmissionState.CAPTURED ||
                detail.processingState == TransmissionState.PROCESSING
        if (!stillCapturingOrProcessing || detail.currentTranscriptText == null) return null
        return when (detail.currentTranscriptPass) {
            TranscriptPass.A -> LogRowPartial.HEARING
            TranscriptPass.B, TranscriptPass.REPROCESS -> LogRowPartial.RESOLVING
            null -> null
        }
    }

    /**
     * R-322 (V4 pass 2 @ee4fd07, register — reopened from R-240): the AMBIGUOUS row's *kept*
     * candidate — `States.dc.html` renders it in `text/high` beside the marker, with
     * [alternateFor]'s runner-up trailing as "or QRF" in amber.
     *
     * **Root cause of R-322.** This used to key off [CandidateInspectionViewState.selected], on the
     * assumption the resolver marks one candidate `selected` even on an AMBIGUOUS attribution. It
     * does not, by design: `AttributionState.AMBIGUOUS`'s own doc comment is "the system will not
     * choose", and `AmbiguousCandidatesFixtureTest` (WP6, `R_184`) asserts exactly that — "no
     * candidate should be pre-selected on an AMBIGUOUS over" — against the real `overnight` fixture.
     * The unit test this function shipped with fabricated a `selected = true` candidate no real
     * mapper output ever produces, so it passed while the device read only the alternate, forever.
     *
     * Fixed to read rank instead — the same `candidates.sortedBy { it.rank }.take(2)` pattern
     * `DetailViewStateMapper.ambiguousBody` already uses for `Detail-Ambiguous.dc.html`'s own "X or
     * Y" explanation (confirmed correct on device — register: "D03's own header is right") — so Log
     * and Detail can never name a different pair for the same over. `Attribution.ambiguous()`
     * deliberately carries no `stationId` of its own (`:core`'s own doc comment: attaching one would
     * misrepresent an undecided call as resolved), so this still reads the candidate list out of
     * band, just by rank rather than a flag the real data never sets for this state.
     */
    public fun keptCandidateFor(detail: TransmissionDetail): String? {
        if (detail.attribution.state != AttributionState.AMBIGUOUS) return null
        return detail.inspection.candidates.sortedBy { it.rank }.getOrNull(0)?.callsign
    }

    /** guide §6.1/R-322: the AMBIGUOUS row's "or QRF" alternate — the runner-up of
     * [keptCandidateFor]'s same rank-sorted top two, never the top-ranked candidate itself (see
     * [keptCandidateFor]'s doc comment for why this is no longer `selected`-based). */
    public fun alternateFor(detail: TransmissionDetail): String? {
        if (detail.attribution.state != AttributionState.AMBIGUOUS) return null
        return detail.inspection.candidates.sortedBy { it.rank }.getOrNull(1)?.callsign
    }

    public fun toRowState(
        detail: TransmissionDetail,
        isFirstHeard: Boolean,
        btAudioMark: Boolean = false,
    ): LogRowViewState {
        val partial = partialFor(detail)
        val text = if (partial != null) {
            detail.currentTranscriptText.orEmpty()
        } else {
            ReaderTransmissionViewStateMapper.transcriptLabel(detail)
        }
        return LogRowViewState(
            id = detail.id,
            timeLabel = ReaderTransmissionViewStateMapper.timeLabel(detail.startedAtUtcMillis),
            frequencyLabel = ReaderTransmissionViewStateMapper.frequencyLabel(detail.frequencyHz),
            transcript = text,
            partial = partial,
            attribution = if (partial == null) detail.attribution else null,
            alternate = if (partial == null) alternateFor(detail) else null,
            // R-240 (wired now that main carries `LogRowViewState.callsign`, WP2): the AMBIGUOUS
            // row's kept/leading candidate, shown beside `alternate`'s "or QRF" — see
            // `keptCandidateFor`'s own doc comment for why `Attribution.ambiguous()` cannot carry
            // this itself.
            callsign = if (partial == null) keptCandidateFor(detail) else null,
            signalLabel = if (partial == null) {
                ReaderTransmissionViewStateMapper.signalLabel(detail.signalStrength)
            } else {
                null
            },
            badge = badgeFor(detail, isFirstHeard, partial != null),
            btAudioMark = btAudioMark,
        )
    }

    /**
     * "not listening · 38 s · incoming call" (`Rows.dc.html`). FR-UI-12/FR-RUN-12: a gap is data,
     * never quiet. R-249 (V3 pass 2): the duration now goes through the one shared
     * [ReaderTransmissionViewStateMapper.durationLabel] — this used to write "38s" (no space
     * before the unit), which the board never does.
     *
     * E2-G04 (F23, FR-CAP-5, FR-CAP-13): [bluetoothAudioDropped] — `true` exactly when the gap is
     * still open ([CaptureGapEntity.endedAt] `null`) on a session whose audio route is Bluetooth
     * SCO and the cause is [CaptureGapCause.INPUT_LOST] — reads `Fail-Bluetooth-Audio.dc.html`'s
     * own "not listening · N and counting · Bluetooth audio dropped" instead of the generic
     * cause prose, computed from [nowMillis] against [CaptureGapEntity.startedAt] the same way
     * every other "so far" figure in this reader is. There is no dedicated `CaptureGapCause` value
     * for a Bluetooth-audio drop yet (`:data` still records it as `INPUT_LOST` — a later `:data`
     * change owes a distinct cause); this is deliberately named as an inference from the session's
     * own route, not a new fact `:data` records, and is reported as such (see this package's
     * report). A closed gap, or one whose cause is not `INPUT_LOST`, is never rewritten this way.
     */
    public fun gapLabel(
        gap: CaptureGapEntity,
        nowMillis: Long? = null,
        bluetoothAudioDropped: Boolean = false,
    ): String {
        if (bluetoothAudioDropped && gap.endedAt == null && gap.cause == CaptureGapCause.INPUT_LOST) {
            val elapsed = (nowMillis ?: SystemClock.wallMillis()) - gap.startedAt
            return "not listening · ${ReaderTransmissionViewStateMapper.durationLabel(elapsed)} and counting · " +
                "Bluetooth audio dropped"
        }
        val duration = gap.endedAt?.let {
            ReaderTransmissionViewStateMapper.durationLabel(it - gap.startedAt)
        } ?: "ongoing"
        return "not listening · $duration · ${gapCauseProse(gap.cause)}"
    }

    // R-106 (V3 pass 4, Review @e927690): read verbatim from the real boards, not paraphrased —
    // `Fail-Killed.dc.html`/`Fail-Interrupted.dc.html` both write "app stopped by the OS" for
    // OS_STOPPED (this used to read the shorter, non-matching "app stopped"); `Fail-Disconnect.dc.html`
    // confirms "input lost" for INPUT_LOST, already correct; `Fail-Call.dc.html`/`Log.dc.html`/
    // `Rows.dc.html` confirm the log row's own short "incoming call" for CALL, already correct.
    private fun gapCauseProse(cause: CaptureGapCause): String = when (cause) {
        CaptureGapCause.CALL -> "incoming call"
        CaptureGapCause.INPUT_LOST -> "input lost"
        CaptureGapCause.OS_STOPPED -> "app stopped by the OS"
        CaptureGapCause.ROUTE_LOST -> "route lost"
        CaptureGapCause.INTERRUPTION -> "interruption"
        CaptureGapCause.ROUTE_CHANGE -> "route changed"
        CaptureGapCause.DEVICE_LOST -> "device lost"
        CaptureGapCause.STORAGE -> "storage full"
        CaptureGapCause.UNKNOWN -> "reason unknown"
    }

    public fun attributionProse(state: AttributionState): String = when (state) {
        AttributionState.CONFIRMED -> "Confirmed"
        AttributionState.INFERRED -> "Inferred"
        AttributionState.AMBIGUOUS -> "Ambiguous"
        AttributionState.UNKNOWN -> "Unknown"
    }

    /**
     * R-043 (`Log-Rejected.dc.html`'s `.why` line): [org.ort.pipeline.passb.DataPassBResultSink]
     * writes `transmission.rejectionReason` as `"$rule: $detail"` (the six §8.2 rejection rules,
     * `org.ort.asrapi.rules.RejectionRuleId` — not imported here, since `:app`'s allowed edges do
     * not include `:asr-api`; matched by the enum's own name string instead). This turns that raw
     * record into operator prose — a category (never the raw `TOO_SHORT`/`NO_SPEECH_PROB`/… token)
     * plus the rule's own elaboration, reformatted where the rule wrote a technical `key=value`
     * detail rather than a sentence. `null` when [rejectionReason] carries no `": "` separator at
     * all (nothing beyond the short reason shown already) or an unrecognised rule token (never a
     * guessed category for a value this mapping does not know).
     */
    public fun whyFor(rejectionReason: String?): String? {
        val record = rejectionReason ?: return null
        val separator = record.indexOf(": ")
        if (separator < 0) return null
        val ruleToken = record.substring(0, separator)
        val detail = record.substring(separator + 2).trim()
        val category = rejectionCategoryProse(ruleToken) ?: return null
        val elaboration = rejectionDetailProse(ruleToken, detail)
        return if (elaboration.isBlank()) category else "$category, $elaboration"
    }

    private fun rejectionCategoryProse(ruleToken: String): String? = when (ruleToken) {
        "TOO_SHORT" -> "Too short"
        "VAD_NO_SPEECH" -> "No speech detected"
        "NO_SPEECH_PROB" -> "Low speech confidence"
        "REPETITION" -> "Repeated text"
        "BLOCKLIST" -> "Known hallucination phrase"
        "COMPRESSION_RATIO" -> "Unusual compression ratio"
        else -> null // an unrecognised token never gets a guessed category.
    }

    /**
     * R-331 (V3 pass 3 @16172f0): `Log-Rejected.dc.html`'s list REASON column — "rejected · squelch
     * tail", not the raw `"VAD_NO_SPEECH: squelch tail, 0.4 s"` record `whyFor`'s own elaboration
     * reads from. Deliberately a *different*, terser mapping than [rejectionCategoryProse] (which
     * stays exactly as it was — the register: "the human why-line beneath is right"): the board's
     * own six example rows name "squelch tail" / "hallucination" / "too short" / "repeated text" /
     * "no-speech probability" for five of the six real rules — read verbatim from that mockup, not
     * guessed. `COMPRESSION_RATIO` has no example row on that board; "compression ratio" continues
     * its own short, jargon-free, lowercase style. `RejectedRow` (`ui/components/Rows.kt`, WP2's)
     * already renders `"rejected · $reason".uppercase()` — this only changes what `reason` itself
     * carries. Falls back to the raw record when the token is unrecognised, or the whole record
     * when it never had a `"$rule: "` prefix at all (a bare short reason already, unchanged), or
     * "no reason recorded" when there is no record at all — never a blank row.
     */
    public fun shortReasonFor(rejectionReason: String?): String {
        val record = rejectionReason ?: return "no reason recorded"
        val separator = record.indexOf(": ")
        if (separator < 0) return record
        val ruleToken = record.substring(0, separator)
        return rejectionShortReasonProse(ruleToken) ?: record
    }

    private fun rejectionShortReasonProse(ruleToken: String): String? = when (ruleToken) {
        "TOO_SHORT" -> "too short"
        "VAD_NO_SPEECH" -> "squelch tail"
        "NO_SPEECH_PROB" -> "no-speech probability"
        "REPETITION" -> "repeated text"
        "BLOCKLIST" -> "hallucination"
        "COMPRESSION_RATIO" -> "compression ratio"
        else -> null // an unrecognised token never gets a guessed short reason.
    }

    /** [TOO_SHORT_PATTERN] etc. reformat the two rules that wrote a `key=value` detail rather than a sentence. */
    private fun rejectionDetailProse(ruleToken: String, detail: String): String = when (ruleToken) {
        "NO_SPEECH_PROB" -> NO_SPEECH_PROB_PATTERN.find(detail)?.let { match ->
            val (score, ceiling) = match.destructured
            "no-speech score $score, above the $ceiling ceiling"
        } ?: detail
        "COMPRESSION_RATIO" -> COMPRESSION_RATIO_PATTERN.find(detail)?.let { match ->
            val (ratio, ceiling) = match.destructured
            "compression ratio $ratio, above the $ceiling ceiling"
        } ?: detail
        else -> detail
    }

    private fun matchesAttribution(detail: TransmissionDetail, selection: LogFilterSelection): Boolean =
        detail.attribution.state in selection.attributionStates

    private fun matchesFrequency(detail: TransmissionDetail, selection: LogFilterSelection): Boolean =
        selection.frequencyHz == null || detail.frequencyHz == selection.frequencyHz

    private fun matchesTime(startedAtUtcMillis: Long, selection: LogFilterSelection): Boolean =
        (selection.fromMillis == null || startedAtUtcMillis >= selection.fromMillis) &&
            (selection.toMillis == null || startedAtUtcMillis <= selection.toMillis)

    /**
     * Builds the dedicated `Log-Rejected.dc.html` view: every rejected transmission in the
     * session, chronological, regardless of the sheet's own filter (P9 — nothing rejected is
     * ever hidden further by a filter combination; the quick chip is the one always-reachable path).
     */
    public fun buildRejectedFocus(details: List<TransmissionDetail>): List<LogListItem.RejectedItem> = details
        .filter { it.processingState == TransmissionState.REJECTED }
        .sortedBy { it.startedAtUtcMillis }
        .map { detail ->
            LogListItem.RejectedItem(
                id = detail.id,
                timeLabel = ReaderTransmissionViewStateMapper.timeLabel(detail.startedAtUtcMillis),
                frequencyLabel = ReaderTransmissionViewStateMapper.frequencyLabel(detail.frequencyHz),
                reason = shortReasonFor(detail.rejectionReason),
                why = whyFor(detail.rejectionReason),
                durationLabel = ReaderTransmissionViewStateMapper.durationLabel(detail.durationMs),
            )
        }

    /** One interleaved item, before grouping — [threadId] drives the QSO-group pass below. */
    private data class Timed(val atMillis: Long, val item: LogListItem, val threadId: String?)

    private fun rowsAsTimed(
        details: List<TransmissionDetail>,
        selection: LogFilterSelection,
        firstHeardIds: Set<String>,
        btAudioMark: Boolean = false,
    ): List<Timed> = details
        .filter { it.processingState != TransmissionState.REJECTED }
        .filter {
            matchesAttribution(it, selection) &&
                matchesFrequency(it, selection) &&
                matchesTime(it.startedAtUtcMillis, selection)
        }
        .map { detail ->
            Timed(
                detail.startedAtUtcMillis,
                LogListItem.Row(toRowState(detail, detail.id in firstHeardIds, btAudioMark)),
                detail.threadId,
            )
        }

    private fun rejectedAsTimed(details: List<TransmissionDetail>, selection: LogFilterSelection): List<Timed> {
        if (!selection.showRejected) return emptyList()
        return details
            .filter { it.processingState == TransmissionState.REJECTED }
            .filter { matchesFrequency(it, selection) && matchesTime(it.startedAtUtcMillis, selection) }
            .map { detail ->
                Timed(
                    detail.startedAtUtcMillis,
                    LogListItem.RejectedItem(
                        id = detail.id,
                        timeLabel = ReaderTransmissionViewStateMapper.timeLabel(detail.startedAtUtcMillis),
                        frequencyLabel = ReaderTransmissionViewStateMapper.frequencyLabel(detail.frequencyHz),
                        reason = shortReasonFor(detail.rejectionReason),
                        why = whyFor(detail.rejectionReason),
                        durationLabel = ReaderTransmissionViewStateMapper.durationLabel(detail.durationMs),
                    ),
                    null,
                )
            }
    }

    private fun gapsAsTimed(
        gaps: List<CaptureGapEntity>,
        selection: LogFilterSelection,
        bluetoothAudioSession: Boolean = false,
        nowMillis: Long? = null,
    ): List<Timed> {
        if (!selection.showGaps) return emptyList()
        return gaps
            .filter { matchesTime(it.startedAt, selection) }
            .map { gap ->
                Timed(
                    gap.startedAt,
                    LogListItem.Gap(
                        id = gap.id,
                        timeLabel = ReaderTransmissionViewStateMapper.timeLabel(gap.startedAt),
                        label = gapLabel(gap, nowMillis, bluetoothAudioSession),
                    ),
                    null,
                )
            }
    }

    /** The QSO-grouping pass: a run of consecutive same-`threadId` items gets one header in front of it. */
    private fun groupRuns(merged: List<Timed>): List<LogListItem> {
        val result = mutableListOf<LogListItem>()
        var i = 0
        while (i < merged.size) {
            val threadId = merged[i].threadId
            if (threadId == null) {
                result.add(merged[i].item)
                i++
                continue
            }
            var j = i
            val run = mutableListOf<Timed>()
            while (j < merged.size && merged[j].threadId == threadId) {
                run.add(merged[j])
                j++
            }
            result.add(LogListItem.Group(threadId = threadId, label = groupLabel(run)))
            result.addAll(run.map { it.item })
            i = j
        }
        return result
    }

    private fun groupLabel(run: List<Timed>): String {
        val overCount = run.size
        val stationCount = run
            .mapNotNull { (it.item as? LogListItem.Row)?.state?.attribution?.stationId }
            .toSet()
            .size
        return "QSO · $overCount over${if (overCount == 1) "" else "s"} · " +
            "$stationCount station${if (stationCount == 1) "" else "s"}"
    }

    /** The normal, chronological, interleaved view (`Log.dc.html`) — [selection] governs it fully. */
    public fun buildItems(
        details: List<TransmissionDetail>,
        gaps: List<CaptureGapEntity>,
        selection: LogFilterSelection,
        firstHeardIds: Set<String>,
        // E2-G04 (F23, FR-CAP-13): the session's own Bluetooth-audio fact, threaded down to both
        // the row mark and the gap wording — `false` (every caller before this existed) renders
        // exactly as before.
        bluetoothAudioSession: Boolean = false,
        nowMillis: Long? = null,
    ): List<LogListItem> {
        val merged = (
            rowsAsTimed(details, selection, firstHeardIds, bluetoothAudioSession) +
                rejectedAsTimed(details, selection) +
                gapsAsTimed(gaps, selection, bluetoothAudioSession, nowMillis)
            ).sortedBy { it.atMillis }
        return groupRuns(merged)
    }

    public fun quickFilters(
        frequencies: List<Long>,
        active: LogQuickFilterId,
        rejectedCount: Int,
    ): List<LogQuickFilterChipViewState> = buildList {
        add(LogQuickFilterChipViewState(LogQuickFilterId.All, "All", active == LogQuickFilterId.All))
        frequencies.forEach { hz ->
            val id = LogQuickFilterId.Frequency(hz)
            add(LogQuickFilterChipViewState(id, ReaderTransmissionViewStateMapper.frequencyLabel(hz), active == id))
        }
        add(LogQuickFilterChipViewState(LogQuickFilterId.Named, "Named", active == LogQuickFilterId.Named))
        val rejectedLabel = if (rejectedCount > 0) "Rejected · $rejectedCount" else "Rejected"
        add(LogQuickFilterChipViewState(LogQuickFilterId.Rejected, rejectedLabel, active == LogQuickFilterId.Rejected))
    }

    /** The selection a quick chip applies on top of whatever the sheet already has set (`Log.dc.html`'s row). */
    public fun selectionFor(quickFilter: LogQuickFilterId, sheetSelection: LogFilterSelection): LogFilterSelection =
        when (quickFilter) {
            LogQuickFilterId.All ->
                sheetSelection.copy(frequencyHz = null, attributionStates = AttributionState.entries.toSet())
            is LogQuickFilterId.Frequency -> sheetSelection.copy(frequencyHz = quickFilter.hz)
            LogQuickFilterId.Named ->
                sheetSelection.copy(attributionStates = setOf(AttributionState.CONFIRMED, AttributionState.INFERRED))
            LogQuickFilterId.Rejected -> sheetSelection // unused — rejectedFocus bypasses selection entirely.
        }

    public fun rejectedExplanation(count: Int): String {
        val segments = if (count == 1) "segment" else "segments"
        return "$count $segments rejected tonight. Audio for every one is kept; opening a row plays it."
    }

    /**
     * R-247/R-248 (V3 pass 2): [frequencies] is the rig's currently-known bands (see
     * [LogPolling.connectedFrequencies]) — when non-empty, the sentence names them exactly as
     * `NowViewStateMapper`'s own "listening on ..." fact does for the same session, so the two
     * screens can never disagree; empty (no rig configured, or one whose bands are unknown) omits
     * the clause entirely rather than fabricate a frequency the rig has not actually reported.
     */
    public fun emptyStateFor(
        hasAnyTransmission: Boolean,
        sessionStartedAtUtcMillis: Long?,
        frequencies: List<Long> = emptyList(),
    ): LogEmptyStateViewState = if (hasAnyTransmission) {
        LogEmptyStateViewState(
            message = "No overs match this filter.",
            subMessage = "Try a different frequency or attribution filter.",
        )
    } else {
        val since = sessionStartedAtUtcMillis
            ?.let { ReaderTransmissionViewStateMapper.hourMinuteLabel(it) }
            ?: "—"
        val onClause = if (frequencies.isEmpty()) {
            ""
        } else {
            " on " + frequencies.joinToString(" and ") { ReaderTransmissionViewStateMapper.frequencyLabel(it) }
        }
        LogEmptyStateViewState(
            message = "No overs yet.",
            subMessage = "Listening since $since$onClause. The first one appears here the moment squelch opens.",
        )
    }

    /**
     * R-243 (V3 pass 2): [dataExtentEndMillis] pre-fills the `to` bound the same way
     * [sessionStartedAtUtcMillis] already pre-fills `from` — the caller's own extent of the data
     * ([LogPolling.filterSheetState] passes the session's end, or the latest transmission's start
     * if the session is still open), never "now" (which is not a real event in the data and would
     * make the sheet's own pre-fill drift every time it re-renders).
     */
    public fun filterSheetState(
        nonRejected: List<TransmissionDetail>,
        rejectedCount: Int,
        gaps: List<CaptureGapEntity>,
        selection: LogFilterSelection,
        sessionStartedAtUtcMillis: Long?,
        dataExtentEndMillis: Long? = null,
    ): LogFilterSheetViewState {
        val frequencies = nonRejected.mapNotNull { it.frequencyHz }.distinct().sorted()
        val frequencyOptions = buildList {
            add(LogFrequencyOptionViewState(null, "All", nonRejected.size, selection.frequencyHz == null))
            frequencies.forEach { hz ->
                val count = nonRejected.count { it.frequencyHz == hz }
                add(
                    LogFrequencyOptionViewState(
                        hz,
                        ReaderTransmissionViewStateMapper.frequencyLabel(hz),
                        count,
                        selection.frequencyHz == hz,
                    ),
                )
            }
        }
        val attributionOptions = AttributionState.entries.map { state ->
            LogAttributionOptionViewState(
                state = state,
                label = attributionProse(state),
                count = nonRejected.count { it.attribution.state == state },
                checked = state in selection.attributionStates,
            )
        }
        val gapsCount = gaps.size
        // R-276: the count must be honest for a seeded time window too, not only frequency/
        // attribution — "Show N overs" previously ignored `fromMillis`/`toMillis` entirely, so a
        // window-seeded filter (Frequency.dc.html's "The N overs" stat) would have shown a number
        // larger than what actually renders once `Show` is tapped.
        //
        // R-330: a gap is not an over (domain vocabulary — "Transmission (one keying, the atomic
        // unit)... Over (its UI synonym)"; a not-listening gap is neither) — the CTA must never
        // fold `gapsCount` into its own total even when "Not-listening gaps" is checked, though the
        // gap rows themselves still interleave into the list exactly as before.
        val matchingCount = nonRejected.count {
            matchesAttribution(it, selection) &&
                matchesFrequency(it, selection) &&
                matchesTime(it.startedAtUtcMillis, selection)
        } + (if (selection.showRejected) rejectedCount else 0)
        return LogFilterSheetViewState(
            frequencyOptions = frequencyOptions,
            attributionOptions = attributionOptions,
            rejectedCount = rejectedCount,
            rejectedShown = selection.showRejected,
            gapsCount = gapsCount,
            gapsShown = selection.showGaps,
            fromLabel = selection.fromMillis?.let { ReaderTransmissionViewStateMapper.hourMinuteLabel(it) }
                ?: sessionStartedAtUtcMillis?.let { ReaderTransmissionViewStateMapper.hourMinuteLabel(it) } ?: "—",
            toLabel = selection.toMillis?.let { ReaderTransmissionViewStateMapper.hourMinuteLabel(it) }
                ?: dataExtentEndMillis?.let { ReaderTransmissionViewStateMapper.hourMinuteLabel(it) } ?: "—",
            matchingCount = matchingCount,
        )
    }
}

// -------------------------------------------------------------------------------------------
// The real, :data-direct read path.
// -------------------------------------------------------------------------------------------

/**
 * R-040..R-045's real read path. Deliberately independent of `ui/data/ReaderPolling.kt` (WP4's
 * file, per this package's own row) — every query here goes straight through [OrtDatabase].
 */
public object LogPolling {

    public suspend fun screenState(
        context: Context,
        sessionId: String,
        selection: LogFilterSelection,
        activeQuickFilter: LogQuickFilterId,
    ): LogScreenViewState {
        val db = OrtDatabase.create(context.applicationContext)
        val entities = db.transmissionDao().listBySession(sessionId)
        val details = entities.map { buildDetail(context, db, it) }
        val gaps = db.captureGapDao().listBySession(sessionId)
        val session = db.sessionDao().getById(sessionId)
        // E2-G04 (F23, FR-CAP-13): the WPF seam — see that type's own kdoc.
        val routeFacts = RoomSessionRouteFactsReader(context).forSession(sessionId)
        val bluetoothAudioFootnote = if (routeFacts.isBluetoothAudio) {
            "Every over captured over Bluetooth carries the bt audio mark. Its accuracy is " +
                "reported separately from cabled audio, never averaged in."
        } else {
            null
        }
        // R-248: union the rig's currently-known bands with what's actually been heard, so a
        // configured-but-silent-so-far frequency still shows its own quick-filter chip (dimmed,
        // in the unselected chip style) rather than waiting for a first over on it.
        val distinctFrequencies = (entities.mapNotNull { it.frequencyHz } + connectedFrequencies()).distinct().sorted()
        val rejectedCount = details.count { it.processingState == TransmissionState.REJECTED }
        val quickFilters = LogItemsMapper.quickFilters(distinctFrequencies, activeQuickFilter, rejectedCount)

        if (activeQuickFilter == LogQuickFilterId.Rejected) {
            val items = LogItemsMapper.buildRejectedFocus(details)
            val emptyState = if (items.isEmpty()) {
                LogEmptyStateViewState("No rejected segments.", "Nothing has been rejected this session.")
            } else {
                null
            }
            return LogScreenViewState(
                items = items,
                quickFilters = quickFilters,
                rejectedFocus = true,
                rejectedExplanation = LogItemsMapper.rejectedExplanation(items.size),
                emptyState = emptyState,
                bluetoothAudioFootnote = bluetoothAudioFootnote,
            )
        }

        val firstHeardIds = firstHeardTransmissionIds(db)
        val effectiveSelection = LogItemsMapper.selectionFor(activeQuickFilter, selection)
        val items = LogItemsMapper.buildItems(
            details,
            gaps,
            effectiveSelection,
            firstHeardIds,
            bluetoothAudioSession = routeFacts.isBluetoothAudio,
            nowMillis = SystemClock.wallMillis(),
        )
        val emptyState = if (items.isEmpty()) {
            LogItemsMapper.emptyStateFor(
                hasAnyTransmission = details.isNotEmpty(),
                sessionStartedAtUtcMillis = session?.startedAt,
                frequencies = distinctFrequencies,
            )
        } else {
            null
        }
        return LogScreenViewState(
            items = items,
            quickFilters = quickFilters,
            rejectedFocus = false,
            rejectedExplanation = null,
            emptyState = emptyState,
            bluetoothAudioFootnote = bluetoothAudioFootnote,
        )
    }

    public suspend fun filterSheetState(
        context: Context,
        sessionId: String,
        selection: LogFilterSelection,
    ): LogFilterSheetViewState {
        val db = OrtDatabase.create(context.applicationContext)
        val entities = db.transmissionDao().listBySession(sessionId)
        val details = entities.map { buildDetail(context, db, it) }
        val gaps = db.captureGapDao().listBySession(sessionId)
        val session = db.sessionDao().getById(sessionId)
        val nonRejected = details.filter { it.processingState != TransmissionState.REJECTED }
        val rejectedCount = details.size - nonRejected.size
        // R-243: the `to` bound pre-fills with the data's own extent — the session's end if it has
        // one, else the latest transmission actually seen so far (never wall-clock "now", which
        // is not itself a fact this data carries and would make the pre-fill drift on every poll).
        val dataExtentEndMillis = session?.endedAt ?: entities.maxOfOrNull { it.startedAtUtc }
        return LogItemsMapper.filterSheetState(
            nonRejected,
            rejectedCount,
            gaps,
            selection,
            session?.startedAt,
            dataExtentEndMillis,
        )
    }

    /**
     * R-247: no session at all yet (`sessionId` itself `null` — the true first-launch state) —
     * still an honest, fully-drawn empty Log (headline, sentence, quick-filter chips), never the
     * blank body a `sessionId == null` early-return otherwise left behind.
     */
    public fun noSessionState(): LogScreenViewState {
        val frequencies = connectedFrequencies()
        return LogScreenViewState(
            items = emptyList(),
            quickFilters = LogItemsMapper.quickFilters(frequencies, LogQuickFilterId.All, rejectedCount = 0),
            rejectedFocus = false,
            rejectedExplanation = null,
            emptyState = LogItemsMapper.emptyStateFor(
                hasAnyTransmission = false,
                sessionStartedAtUtcMillis = null,
                frequencies = frequencies,
            ),
        )
    }

    /**
     * R-247/R-248: the rig's currently-connected bands — the same [RigStatus] read
     * `ui/data/ReaderPolling.kt`'s `activeNowViewState` already does for its own "listening on ..."
     * fact (`RigStatus.State.Stale`/`Absent` never fabricate a frequency the rig is not presently
     * reporting, so only `Connected` counts here, matching that call site exactly).
     */
    private fun connectedFrequencies(): List<Long> = (RigStatus.state as? RigStatus.State.Connected)?.bands
        ?.mapNotNull { it.frequencyHz }
        ?.distinct()
        ?.sorted()
        ?: emptyList()

    /** R-040's `NEW` badge: the first-ever over heard from a station, across every session, not just this one. */
    private suspend fun firstHeardTransmissionIds(db: OrtDatabase): Set<String> {
        val all = db.transmissionDao().listAll()
        return all
            .filter { it.stationId != null }
            .groupBy { it.stationId }
            .mapNotNull { (_, group) -> group.minByOrNull { it.startedAtUtc }?.id }
            .toSet()
    }

    private suspend fun buildDetail(context: Context, db: OrtDatabase, entity: TransmissionEntity): TransmissionDetail {
        val versions = db.transcriptDao().getAllVersions(entity.id)
        val current = versions.firstOrNull { it.isCurrent }
        val superseded = versions.filter { !it.isCurrent }.sortedBy { it.createdAt }.map { it.text }
        val audioFile = File(context.filesDir, entity.audioPath())
        val inspection = InspectionViewStateMapper.from(
            db.catalogDao().latticesFor(entity.id),
            db.catalogDao().candidatesFor(entity.id),
        )
        return TransmissionDetail(
            id = entity.id,
            startedAtUtcMillis = entity.startedAtUtc,
            frequencyHz = entity.frequencyHz,
            durationMs = entity.durationMs,
            signalStrength = entity.signalStrength,
            attribution = attributionFrom(entity),
            currentTranscriptText = current?.text,
            supersededTranscriptTexts = superseded,
            hasAudio = audioFile.isFile,
            threadId = entity.threadId,
            sessionId = entity.sessionId,
            samplePosition = entity.samplePosition,
            inspection = inspection,
            processingState = entity.processingState,
            rejectionReason = entity.rejectionReason,
            currentTranscriptPass = current?.pass,
            corrected = entity.corrected,
            mode = entity.mode,
        )
    }

    /** Duplicated from `ReaderPolling` deliberately (see file doc comment) — that function is private there. */
    private fun attributionFrom(entity: TransmissionEntity): Attribution {
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

    private fun sourceId(entity: TransmissionEntity): TransmissionId? =
        entity.attributionSourceTransmissionId?.let { runCatching { TransmissionId.parse(it) }.getOrNull() }
}
