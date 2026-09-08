package org.ort.app.ui.data

import android.content.Context
import org.ort.core.Attribution
import org.ort.core.AttributionState
import org.ort.data.OrtDatabase
import org.ort.pipeline.capture.ShedStatus

/**
 * FR-UI-2: explains an attribution — "which transmission confirmed a callsign, and which
 * inherited it" (functional spec's own wording). Pure and DB-free. [ThreadListMapper.detailState]
 * is the caller that turns this into each over's reasoning line on `Thread-Detail.dc.html`; the
 * grouping half this object used to own (`from`, returning the now-removed `ThreadGroupViewState`)
 * was `Threads`'s pre-R-044 flat-list rendering — superseded by [ThreadListMapper]/[ThreadScreen]'s
 * cards and removed once nothing referenced it (see this package's CHANGELOG for the commit).
 */
public object ThreadGroupingMapper {

    /**
     * R-332 (V3 pass 3 @16172f0): used to read `"inherited from transmission $sourceId at
     * $time"` — a raw ULID in operator copy. The board (`Thread-Detail.dc.html`) shows "inherited
     * by voice from 02:16:02", the source id dropped entirely (it names nothing an operator reads;
     * [reasoningLinkTimeFor] is how the id survives, as the string the time link taps through with,
     * not as visible text) and the method named honestly from real, already-recorded fields:
     * - a real `sourceTransmissionId` only ever comes from [Attribution.inferred]'s own voiceprint-
     *   match construction (functional spec §4's own row: INFERRED is "attributed by voiceprint
     *   cluster match" — the *only* mechanism it documents) — "by voice".
     * - [Attribution.corrected] with no source is exactly [Attribution.withCorrection]'s own shape
     *   (a human typed or picked a callsign; no source, no score) — "by callsign".
     */
    public fun reasoningFor(detail: TransmissionDetail, timeLabelById: Map<String, String>): String =
        when (detail.attribution.state) {
            AttributionState.CONFIRMED -> "callsign confirmed in this transmission"
            AttributionState.INFERRED -> {
                val sourceId = detail.attribution.sourceTransmissionId?.toString()
                when {
                    sourceId != null && timeLabelById.containsKey(sourceId) ->
                        "inherited by voice from ${timeLabelById.getValue(sourceId)}"
                    sourceId != null -> "inherited by voice (source transmission not recorded)"
                    detail.attribution.corrected -> "inherited by callsign"
                    else -> "inherited (source transmission not recorded)"
                }
            }
            AttributionState.AMBIGUOUS -> "more than one candidate; the system will not choose"
            AttributionState.UNKNOWN -> "no callsign resolved"
        }

    /**
     * R-332: the linkable time [reasoningFor] embeds at the end of an INFERRED-with-resolved-source
     * line — `ThreadDetailScreen` (this package's own file) styles this exact trailing substring as
     * the mono/green link guide §6.7 requires ("if it acts, it looks like it acts"). `null` for
     * every other case, where [reasoningFor] already returns a complete sentence with nothing left
     * to link (including "by callsign", which never had a source transmission to link to at all).
     */
    public fun reasoningLinkTimeFor(detail: TransmissionDetail, timeLabelById: Map<String, String>): String? {
        if (detail.attribution.state != AttributionState.INFERRED) return null
        val sourceId = detail.attribution.sourceTransmissionId?.toString() ?: return null
        return timeLabelById[sourceId]
    }
}

// -------------------------------------------------------------------------------------------
// R-044 (ui-conformance WP5): `Threads.dc.html`/`Thread-Detail.dc.html`/`Threads-Ungrouped.dc.html`.
// -------------------------------------------------------------------------------------------

/**
 * R-163: one shared plural rule ("1 over", "2 overs") so the several spots that need a
 * word-count string can't drift apart into "1 overs" — audit V3 @3e2d4ee found exactly that in
 * `Threads-Ungrouped.dc.html`'s rendering before this existed.
 */
public fun pluralize(count: Int, singular: String, plural: String = "${singular}s"): String =
    "$count " + if (count == 1) singular else plural

/** One card in the grouped Threads list (`Threads.dc.html`). [kindLabel] is `null` until the data
 * supports deriving one — never guessed. See [ThreadListMapper]'s `deriveKind` for the two rules
 * that do apply (R-160): two stations strictly alternating on the thread = "QSO"; one station
 * repeating = "Activity". Anything else (a third station, an unresolved over, a single over)
 * stays `null` and the card leads with the frequency instead. */
public data class ThreadCardViewState(
    val threadId: String,
    val timeLabel: String,
    val kindLabel: String?,
    val frequencyLabel: String,
    val overCount: Int,
    val titleText: String,
    val metaText: String,
    val ambiguous: Boolean,
    val isNew: Boolean,
)

/** One frequency's tally for the ungrouped "by frequency, meanwhile" list. */
public data class FrequencyMeanwhileEntry(
    val frequencyHz: Long,
    val frequencyLabel: String,
    val overCount: Int,
    val stationCount: Int,
)

/**
 * The whole Threads list, one of three honest states: nothing captured yet, every over
 * individually reachable because nothing is grouped yet (`Threads-Ungrouped.dc.html` — real state
 * today, since `threadId` is `null` on every row until M6), or real cards for the `threadId`
 * groups that do exist, with a count of what remains ungrouped alongside them.
 */
public sealed interface ThreadListViewState {
    public data object Empty : ThreadListViewState

    /**
     * [currentTier] (R-163): the real shed tier — `(3 - ShedStatus.currentLevel).coerceIn(0, 3)`,
     * the exact formula `RealCaptureService.tierFromShedLevel()`/`CaptureStatusViewState`'s own
     * `tierFacts` already use (duplicated here, not imported: `:pipeline`'s copy is `private`, and
     * `:app`'s own copy lives in a different package) — so the paragraph explaining why grouping
     * is not available and the "What tier N can and cannot do" link below it always name the same
     * number, never a hardcoded "tier 1" independent of what the phone is actually running.
     */
    public data class Ungrouped(
        val totalOvers: Int,
        val byFrequency: List<FrequencyMeanwhileEntry>,
        val currentTier: Int,
    ) : ThreadListViewState

    public data class Grouped(val summary: String, val cards: List<ThreadCardViewState>, val ungroupedOvers: Int) :
        ThreadListViewState
}

/** One "how these were attributed" line on `Thread-Detail.dc.html` — a marker plus its explanation. */
public data class ThreadAttributionExplanationLine(val attribution: Attribution, val text: String)

/** One over row on `Thread-Detail.dc.html` — reasoning, plus a source over to link to when inherited. */
public data class ThreadDetailOverViewState(
    val transmissionId: String,
    val timeLabel: String,
    val attribution: Attribution,
    val transcript: String,
    val reasoning: String,
    val sourceTransmissionId: String?,
    /**
     * R-332: the trailing substring of [reasoning] that names the source over's own time — the one
     * `ThreadDetailScreen` styles as the mono/green link (guide §6.7). Non-null exactly when
     * [reasoning] ends with it (see [ThreadGroupingMapper.reasoningLinkTimeFor]); `null` for every
     * complete, non-linkable reasoning sentence, including "inherited by callsign" (which never had
     * a source transmission to link to).
     */
    val sourceTimeLabel: String? = null,
)

/** [modeLabel] (R-161) is `null` when no transmission in the thread ever had a recorded mode —
 * never guessed from the frequency/band. */
public data class ThreadDetailViewState(
    val threadId: String,
    val kindLabel: String?,
    val frequencyLabel: String,
    val modeLabel: String?,
    val titleText: String,
    val metaText: String,
    val howAttributed: List<ThreadAttributionExplanationLine>,
    val overs: List<ThreadDetailOverViewState>,
)

/** Builds [ThreadListViewState] and [ThreadDetailViewState] from real [TransmissionDetail]s — pure, DB-free. */
public object ThreadListMapper {

    public fun listState(
        details: List<TransmissionDetail>,
        firstHeardIds: Set<String>,
        currentTier: Int,
    ): ThreadListViewState {
        if (details.isEmpty()) return ThreadListViewState.Empty
        val (grouped, ungrouped) = details.partition { it.threadId != null }
        if (grouped.isEmpty()) {
            return ThreadListViewState.Ungrouped(
                totalOvers = details.size,
                byFrequency = byFrequency(details),
                currentTier = currentTier,
            )
        }
        val cards = grouped.groupBy { it.threadId }
            .map { (threadId, group) -> cardFor(threadId!!, group, firstHeardIds) }
            .sortedByDescending { it.timeLabel }
        val summary = "${pluralize(cards.size, "conversation")} · ${pluralize(grouped.size, "over")} · newest first"
        return ThreadListViewState.Grouped(summary = summary, cards = cards, ungroupedOvers = ungrouped.size)
    }

    private fun byFrequency(details: List<TransmissionDetail>): List<FrequencyMeanwhileEntry> = details
        .filter { it.frequencyHz != null }
        .groupBy { it.frequencyHz }
        .map { (hz, group) ->
            FrequencyMeanwhileEntry(
                frequencyHz = hz!!,
                frequencyLabel = ReaderTransmissionViewStateMapper.frequencyLabel(hz),
                overCount = group.size,
                stationCount = group.mapNotNull { it.attribution.stationId }.toSet().size,
            )
        }
        .sortedByDescending { it.overCount }

    private fun cardFor(
        threadId: String,
        group: List<TransmissionDetail>,
        firstHeardIds: Set<String>,
    ): ThreadCardViewState {
        val sorted = group.sortedBy { it.startedAtUtcMillis }
        val first = sorted.first()
        val last = sorted.last()
        val stationIds = sorted.mapNotNull { it.attribution.stationId }.distinct()
        val confirmedCount = sorted.count { it.attribution.state == AttributionState.CONFIRMED }
        val inferredCount = sorted.count { it.attribution.state == AttributionState.INFERRED }
        val ambiguousCount = sorted.count { it.attribution.state == AttributionState.AMBIGUOUS }
        val unknownCount = sorted.count { it.attribution.state == AttributionState.UNKNOWN }
        val title = when {
            stationIds.isNotEmpty() -> joinWithAnd(stationIds)
            ambiguousCount > 0 -> "Ambiguous stations"
            else -> pluralize(sorted.size, "unidentified voice")
        }
        val counts = buildList {
            if (confirmedCount > 0) add("$confirmedCount confirmed")
            if (inferredCount > 0) add("$inferredCount inferred")
            if (ambiguousCount > 0) add("$ambiguousCount ambiguous")
            if (unknownCount > 0) add("$unknownCount unknown")
        }
        val timeRange = "${ReaderTransmissionViewStateMapper.timeLabelFor(first)} – " +
            ReaderTransmissionViewStateMapper.timeLabelFor(last)
        val meta = (counts + timeRange).joinToString(" · ")
        return ThreadCardViewState(
            threadId = threadId,
            timeLabel = ReaderTransmissionViewStateMapper.timeLabelFor(first),
            kindLabel = deriveKind(sorted),
            frequencyLabel = ReaderTransmissionViewStateMapper.frequencyLabel(first.frequencyHz),
            overCount = sorted.size,
            titleText = title,
            metaText = meta,
            ambiguous = ambiguousCount > 0,
            isNew = sorted.any { it.id in firstHeardIds },
        )
    }

    /**
     * R-160: derived only from the two patterns the data can support honestly, never a third
     * guessed case — "Net"/"Activation" need real facts (a net-control role, a POTA/SOTA
     * reference) this schema does not carry yet, so this never emits either.
     *
     * - Every over in the thread names a resolved station (an unresolved over means the pattern
     *   itself is uncertain, so this returns `null` rather than classify around the gap), AND
     * - exactly one distinct station repeating → **"Activity"**, or
     * - exactly two distinct stations, with no two consecutive overs from the same one (a clean
     *   A-B-A-B exchange) → **"QSO"**.
     *
     * Anything else (three or more stations, two stations *not* alternating, a single over) → `null`.
     */
    private fun deriveKind(sorted: List<TransmissionDetail>): String? {
        val stationSequence = sorted.map { it.attribution.stationId }
        if (stationSequence.any { it == null } || stationSequence.size < 2) return null
        @Suppress("UNCHECKED_CAST")
        val sequence = stationSequence as List<String>
        val distinctStations = sequence.distinct()
        return when {
            distinctStations.size == 1 -> "Activity"
            distinctStations.size == 2 && sequence.zipWithNext().all { (a, b) -> a != b } -> "QSO"
            else -> null
        }
    }

    /** R-161: the first mode any transmission in the thread actually recorded — `null` when none did. */
    private fun deriveMode(sorted: List<TransmissionDetail>): String? = sorted.firstNotNullOfOrNull { it.mode }

    /**
     * R-161/R-249: "1 m 55 s" / "38 s" — `Thread-Detail.dc.html`'s span line, first over start to
     * last over start, through the one shared duration formatter (`ReaderTransmissionViewStateMapper.durationLabel`)
     * every "how long" label in the reader now uses.
     */
    private fun spanDurationLabel(firstStartMillis: Long, lastStartMillis: Long): String =
        ReaderTransmissionViewStateMapper.durationLabel(lastStartMillis - firstStartMillis)

    public fun detailState(threadId: String, details: List<TransmissionDetail>): ThreadDetailViewState? {
        val group = details.filter { it.threadId == threadId }
        if (group.isEmpty()) return null
        val sorted = group.sortedBy { it.startedAtUtcMillis }
        val card = cardFor(threadId, group, emptySet())
        val timeLabelById = details.associate { it.id to ReaderTransmissionViewStateMapper.timeLabelFor(it) }
        val howAttributed = howAttributedLines(sorted)
        val overs = sorted.map { detail ->
            ThreadDetailOverViewState(
                transmissionId = detail.id,
                timeLabel = ReaderTransmissionViewStateMapper.timeLabelFor(detail),
                attribution = detail.attribution,
                transcript = ReaderTransmissionViewStateMapper.transcriptLabel(detail),
                reasoning = ThreadGroupingMapper.reasoningFor(detail, timeLabelById),
                sourceTransmissionId = detail.attribution.sourceTransmissionId?.toString(),
                sourceTimeLabel = ThreadGroupingMapper.reasoningLinkTimeFor(detail, timeLabelById),
            )
        }
        val first = sorted.first()
        val last = sorted.last()
        val span = spanDurationLabel(first.startedAtUtcMillis, last.startedAtUtcMillis)
        return ThreadDetailViewState(
            threadId = threadId,
            kindLabel = card.kindLabel,
            frequencyLabel = card.frequencyLabel,
            modeLabel = deriveMode(sorted),
            titleText = card.titleText,
            metaText = "${pluralize(sorted.size, "over")} · " +
                "${ReaderTransmissionViewStateMapper.timeLabelFor(first)} – " +
                "${ReaderTransmissionViewStateMapper.timeLabelFor(last)} · $span",
            howAttributed = howAttributed,
            overs = overs,
        )
    }

    private fun howAttributedLines(sorted: List<TransmissionDetail>): List<ThreadAttributionExplanationLine> {
        val positionById = sorted.mapIndexed { index, detail -> detail.id to (index + 1) }.toMap()
        val lines = mutableListOf<ThreadAttributionExplanationLine>()
        sorted
            .filter { it.attribution.state == AttributionState.CONFIRMED }
            .groupBy { it.attribution.stationId }
            .forEach { (station, group) ->
                if (station == null) return@forEach
                val positions = group.mapNotNull { positionById[it.id] }.sorted()
                val overWord = if (positions.size == 1) "over" else "overs"
                lines += ThreadAttributionExplanationLine(
                    attribution = group.first().attribution,
                    text = "$station heard in $overWord ${positions.joinToString(" and ")}",
                )
            }
        sorted.filter { it.attribution.state == AttributionState.INFERRED }.forEach { detail ->
            val station = detail.attribution.stationId ?: return@forEach
            val pos = positionById[detail.id]
            val sourcePos = detail.attribution.sourceTransmissionId?.toString()?.let { positionById[it] }
            val confidenceSuffix = detail.attribution.confidence?.let { " · %.2f".format(it) }.orEmpty()
            val text = if (sourcePos != null) {
                "Over $pos matched $station's voice from over $sourcePos$confidenceSuffix"
            } else {
                "Over $pos inherited $station$confidenceSuffix"
            }
            lines += ThreadAttributionExplanationLine(attribution = detail.attribution, text = text)
        }
        return lines
    }

    private fun joinWithAnd(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        2 -> "${names[0]} and ${names[1]}"
        else -> "${names.dropLast(1).joinToString(", ")} and ${names.last()}"
    }
}

/**
 * The real read path for the Threads destination (build-plan P15, extended R-044). Reuses
 * `ui/data/ReaderPolling.kt`'s existing detail mapping (this package's own row does not restrict
 * `ThreadViewData.kt` to a direct `:data` read the way it does `LogViewData.kt`), plus two direct
 * reads of its own: [OrtDatabase] for the `NEW`-badge cross-session "first ever heard" fact
 * `ReaderPolling` has no query for, and [ShedStatus] (a process-wide holder, not `:data` — see its
 * own doc comment) for the real shed tier R-163's Threads-Ungrouped paragraph names.
 */
public object ThreadPolling {

    /** R-163: the exact `(3 - ShedStatus.currentLevel).coerceIn(0, 3)` formula
     * `RealCaptureService.tierFromShedLevel()`/`CaptureStatusViewState`'s `tierFacts` already use —
     * duplicated, not imported, since both of those are `private` in their own files/packages. */
    private const val MAX_TIER: Int = 3

    private fun currentTier(): Int = (MAX_TIER - ShedStatus.currentLevel).coerceIn(0, MAX_TIER)

    public suspend fun currentThreadListState(context: Context, sessionId: String): ThreadListViewState {
        val details = ReaderPolling.currentTransmissionDetails(context, sessionId)
        val firstHeardIds = firstHeardTransmissionIds(context)
        return ThreadListMapper.listState(details, firstHeardIds, currentTier())
    }

    public suspend fun threadDetail(context: Context, sessionId: String, threadId: String): ThreadDetailViewState? {
        val details = attachModes(context, sessionId, ReaderPolling.currentTransmissionDetails(context, sessionId))
        return ThreadListMapper.detailState(threadId, details)
    }

    /**
     * R-161: `ui/data/ReaderPolling.kt`'s `detailFrom` (WP4's file) does not set
     * [TransmissionDetail.mode] — re-attached here from a direct `entity.mode` read rather than
     * either fabricating it or editing a file this package does not own.
     */
    private suspend fun attachModes(
        context: Context,
        sessionId: String,
        details: List<TransmissionDetail>,
    ): List<TransmissionDetail> {
        val db = OrtDatabase.create(context.applicationContext)
        val modeById = db.transmissionDao().listBySession(sessionId).associate { it.id to it.mode }
        return details.map { detail -> detail.copy(mode = modeById[detail.id]) }
    }

    private suspend fun firstHeardTransmissionIds(context: Context): Set<String> {
        val db = OrtDatabase.create(context.applicationContext)
        val all = db.transmissionDao().listAll()
        return all
            .filter { it.stationId != null }
            .groupBy { it.stationId }
            .mapNotNull { (_, group) -> group.minByOrNull { it.startedAtUtc }?.id }
            .toSet()
    }
}
