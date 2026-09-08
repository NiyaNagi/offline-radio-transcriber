package org.ort.app.ui.data

import android.content.Context
import org.ort.core.Attribution
import org.ort.core.AttributionState
import org.ort.data.OrtDatabase

/**
 * FR-UI-2: explains an attribution — "which transmission confirmed a callsign, and which
 * inherited it" (functional spec's own wording). Pure and DB-free. [ThreadListMapper.detailState]
 * is the caller that turns this into each over's reasoning line on `Thread-Detail.dc.html`; the
 * grouping half this object used to own (`from`, returning the now-removed `ThreadGroupViewState`)
 * was `Threads`'s pre-R-044 flat-list rendering — superseded by [ThreadListMapper]/[ThreadScreen]'s
 * cards and removed once nothing referenced it (see this package's CHANGELOG for the commit).
 */
public object ThreadGroupingMapper {

    public fun reasoningFor(detail: TransmissionDetail, timeLabelById: Map<String, String>): String =
        when (detail.attribution.state) {
            AttributionState.CONFIRMED -> "callsign confirmed in this transmission"
            AttributionState.INFERRED -> {
                val sourceId = detail.attribution.sourceTransmissionId?.toString()
                when {
                    sourceId == null -> "inherited (source transmission not recorded)"
                    timeLabelById.containsKey(sourceId) ->
                        "inherited from transmission $sourceId at ${timeLabelById.getValue(sourceId)}"
                    else -> "inherited from transmission $sourceId"
                }
            }
            AttributionState.AMBIGUOUS -> "more than one candidate; the system will not choose"
            AttributionState.UNKNOWN -> "no callsign resolved"
        }
}

// -------------------------------------------------------------------------------------------
// R-044 (ui-conformance WP5): `Threads.dc.html`/`Thread-Detail.dc.html`/`Threads-Ungrouped.dc.html`.
// -------------------------------------------------------------------------------------------

/** One card in the grouped Threads list (`Threads.dc.html`). [kindLabel] is `null` until a real
 * classification column exists — never guessed from the entry count or attribution mix (guide §9,
 * "never fabricate"). */
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

    public data class Ungrouped(val totalOvers: Int, val byFrequency: List<FrequencyMeanwhileEntry>) :
        ThreadListViewState

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
)

public data class ThreadDetailViewState(
    val threadId: String,
    val kindLabel: String?,
    val frequencyLabel: String,
    val titleText: String,
    val metaText: String,
    val howAttributed: List<ThreadAttributionExplanationLine>,
    val overs: List<ThreadDetailOverViewState>,
)

/** Builds [ThreadListViewState] and [ThreadDetailViewState] from real [TransmissionDetail]s — pure, DB-free. */
public object ThreadListMapper {

    public fun listState(details: List<TransmissionDetail>, firstHeardIds: Set<String>): ThreadListViewState {
        if (details.isEmpty()) return ThreadListViewState.Empty
        val (grouped, ungrouped) = details.partition { it.threadId != null }
        if (grouped.isEmpty()) {
            return ThreadListViewState.Ungrouped(totalOvers = details.size, byFrequency = byFrequency(details))
        }
        val cards = grouped.groupBy { it.threadId }
            .map { (threadId, group) -> cardFor(threadId!!, group, firstHeardIds) }
            .sortedByDescending { it.timeLabel }
        val conversationWord = if (cards.size == 1) "conversation" else "conversations"
        val summary = "${cards.size} $conversationWord · ${grouped.size} overs · newest first"
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
            else -> "${sorted.size} unidentified voice${if (sorted.size == 1) "" else "s"}"
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
            kindLabel = null,
            frequencyLabel = ReaderTransmissionViewStateMapper.frequencyLabel(first.frequencyHz),
            overCount = sorted.size,
            titleText = title,
            metaText = meta,
            ambiguous = ambiguousCount > 0,
            isNew = sorted.any { it.id in firstHeardIds },
        )
    }

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
            )
        }
        return ThreadDetailViewState(
            threadId = threadId,
            kindLabel = card.kindLabel,
            frequencyLabel = card.frequencyLabel,
            titleText = card.titleText,
            metaText = "${sorted.size} overs · " +
                "${ReaderTransmissionViewStateMapper.timeLabelFor(sorted.first())} – " +
                ReaderTransmissionViewStateMapper.timeLabelFor(sorted.last()),
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
 * `ThreadViewData.kt` to a direct `:data` read the way it does `LogViewData.kt`), plus one direct
 * [OrtDatabase] query of its own for the `NEW`-badge cross-session "first ever heard" fact
 * `ReaderPolling` has no query for.
 */
public object ThreadPolling {

    public suspend fun currentThreadListState(context: Context, sessionId: String): ThreadListViewState {
        val details = ReaderPolling.currentTransmissionDetails(context, sessionId)
        val firstHeardIds = firstHeardTransmissionIds(context)
        return ThreadListMapper.listState(details, firstHeardIds)
    }

    public suspend fun threadDetail(context: Context, sessionId: String, threadId: String): ThreadDetailViewState? {
        val details = ReaderPolling.currentTransmissionDetails(context, sessionId)
        return ThreadListMapper.detailState(threadId, details)
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
