package org.ort.app.ui.data

import android.content.Context
import org.ort.core.AttributionState

/** One transmission within a thread group, with the reasoning behind its attribution (FR-UI-2). */
public data class ThreadEntryViewState(val listEntry: TransmissionListEntryViewState, val reasoning: String)

/**
 * One conversation. [threadId] is `null` for the honest "not yet grouped" bucket — real data
 * today, since nothing populates `transmission.threadId` before M6 (build-plan P15's own scope
 * note) — never a fabricated single conversation standing in for the absence of real grouping.
 */
public data class ThreadGroupViewState(
    val threadId: String?,
    val label: String,
    val entries: List<ThreadEntryViewState>,
)

/**
 * FR-UI-2: groups transmissions by the real `threadId` column and explains each attribution —
 * "which transmission confirmed a callsign, and which inherited it" (functional spec's own
 * wording). Pure and DB-free: [ThreadPolling] is the seam that feeds it real data.
 */
public object ThreadGroupingMapper {

    public fun from(details: List<TransmissionDetail>): List<ThreadGroupViewState> {
        if (details.isEmpty()) return emptyList()
        val timeLabelById: Map<String, String> = details.associate { detail ->
            detail.id to ReaderTransmissionViewStateMapper.timeLabelFor(detail)
        }
        val grouped: Map<String?, List<TransmissionDetail>> = details.groupBy { it.threadId }
        return grouped.entries
            // Real threads first, the ungrouped bucket last — it is a fallback, not a conversation.
            .sortedWith(compareBy({ it.key == null }, { it.key }))
            .map { (threadId, group) ->
                val sorted = group.sortedBy { it.startedAtUtcMillis }
                ThreadGroupViewState(
                    threadId = threadId,
                    label = if (threadId != null) {
                        "Thread · ${sorted.size} over(s)"
                    } else {
                        "Not yet grouped into threads"
                    },
                    entries = sorted.map { detail ->
                        ThreadEntryViewState(
                            listEntry = ReaderTransmissionViewStateMapper.listEntry(detail),
                            reasoning = reasoningFor(detail, timeLabelById),
                        )
                    },
                )
            }
    }

    private fun reasoningFor(detail: TransmissionDetail, timeLabelById: Map<String, String>): String =
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

/**
 * The real read path for the Threads destination (build-plan P15): every transmission in
 * [sessionId], grouped by the real `threadId` column via [ThreadGroupingMapper].
 */
public object ThreadPolling {
    public suspend fun currentThreadGroups(context: Context, sessionId: String): List<ThreadGroupViewState> {
        // Reuses the exact same real read path as the Log/Now screens (ReaderPolling), rather
        // than a second query, so a transmission cannot show different facts on two screens.
        val details = ReaderPolling.currentTransmissionDetails(context, sessionId)
        return ThreadGroupingMapper.from(details)
    }
}
