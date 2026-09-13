package org.ort.app.ui.recordings

import org.ort.app.ui.improve.Plurals
import org.ort.pipeline.archive.ArchiveState
import org.ort.pipeline.archive.RecordingSessionSummary
import org.ort.pipeline.capture.ArchiveWriteRateForecast
import org.ort.pipeline.capture.OverAudioBudgetState
import org.ort.pipeline.capture.StorageAccounting
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * `Recordings.dc.html` (RC01): the pure decision behind [RecordingsPolling.state] — pulled out to a
 * plain function (the same "pull the decision out of the composable/poller" shape
 * `org.ort.app.ui.navigation.resolveTransportBarState` already established) so every rule here is
 * directly unit-testable with constructed fixtures, no database or Android context required.
 *
 * D39/D40, FR-STO-3d/3e/3f, AC-156..160, register R-1036/R-1037: the two budget cards are read as
 * the policies they are, never silently derived — [ArchiveWriteRateForecast.State.NotYetMeasured]
 * is always rendered as the D39 estimate labelled "estimated", never confused with a real
 * measurement, and the over-audio card's `exceeded` flag is recomputed fresh from
 * [org.ort.pipeline.capture.overAudioBudgetState] every call — nothing here caches it.
 */
public object RecordingsViewStateMapper {

    /** D39's own static figure — an estimate, not a measurement, until
     * [org.ort.pipeline.capture.ArchiveWriteRateForecast] has real data for this session. */
    private const val ARCHIVE_ESTIMATED_MONTHLY_GB: Int = 15

    private const val BYTES_PER_GB: Double = 1_000_000_000.0

    private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM").withZone(ZoneOffset.UTC)
    private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC)
    private val BADGE_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM").withZone(ZoneOffset.UTC)

    public fun map(
        summaries: List<RecordingSessionSummary>,
        accounting: StorageAccounting,
        budgetInputs: RecordingsBudgetInputs,
        selectedFilter: RecordingsFilter,
        liveSessionId: String?,
        nowMillis: Long,
    ): RecordingsViewState {
        val rows = summaries
            .sortedByDescending { it.startedAtMillis }
            .map { sessionRow(it, isLive = it.sessionId == liveSessionId, nowMillis = nowMillis) }

        val filters = buildFilters(summaries)
        val filtered = rows.filter { matchesFilter(it, summaries, selectedFilter) }

        return RecordingsViewState(
            headline = headline(summaries),
            budgets = RecordingsBudgetsViewState(
                overAudio = overAudioCard(accounting.audioBytes, budgetInputs.overAudioBudget),
                archive = archiveCard(
                    usedBytes = accounting.archiveBytes,
                    enabled = budgetInputs.archiveEnabled,
                    budgetGb = budgetInputs.archiveBudgetGb,
                    rateState = budgetInputs.archiveRateState,
                ),
            ),
            filters = filters,
            selectedFilter = selectedFilter,
            sessions = filtered,
        )
    }

    private fun headline(summaries: List<RecordingSessionSummary>): String {
        if (summaries.isEmpty()) return "No sessions recorded yet"
        val overs = summaries.sumOf { it.overCount }
        val earliest = summaries.minOf { it.startedAtMillis }
        val sinceLabel = DateTimeFormatter.ofPattern("d MMM", Locale.US)
            .withZone(ZoneOffset.UTC)
            .format(Instant.ofEpochMilli(earliest))
        return "${Plurals.count(summaries.size, "session")} · ${Plurals.count(overs, "over")} · since $sinceLabel"
    }

    /** Counts are computed against the full, unfiltered [summaries] regardless of which chip is
     * currently active (a chip must never make its own or a sibling's count disappear once
     * selected) — [RecordingsViewState.selectedFilter] is what the screen reads to decide which
     * chip renders as selected. */
    private fun buildFilters(summaries: List<RecordingSessionSummary>): List<RecordingsFilterChipViewState> = listOf(
        RecordingsFilterChipViewState(RecordingsFilter.ALL, "All", count = null),
        RecordingsFilterChipViewState(
            RecordingsFilter.HAS_FAILURES,
            "Has failures",
            summaries.count { it.failedCount > 0 },
        ),
        RecordingsFilterChipViewState(
            RecordingsFilter.LABELLED,
            "Labelled",
            summaries.count { it.labelledCount > 0 },
        ),
        RecordingsFilterChipViewState(
            RecordingsFilter.ARCHIVE_REMOVED,
            "Archive removed",
            summaries.count { it.archiveState == ArchiveState.REMOVED },
        ),
    )

    private fun matchesFilter(
        row: RecordingsSessionRowViewState,
        summaries: List<RecordingSessionSummary>,
        filter: RecordingsFilter,
    ): Boolean {
        val summary = summaries.firstOrNull { it.sessionId == row.id } ?: return filter == RecordingsFilter.ALL
        return when (filter) {
            RecordingsFilter.ALL -> true
            RecordingsFilter.HAS_FAILURES -> summary.failedCount > 0
            RecordingsFilter.LABELLED -> summary.labelledCount > 0
            RecordingsFilter.ARCHIVE_REMOVED -> summary.archiveState == ArchiveState.REMOVED
        }
    }

    private fun sessionRow(
        summary: RecordingSessionSummary,
        isLive: Boolean,
        nowMillis: Long,
    ): RecordingsSessionRowViewState {
        val end = summary.endedAtMillis ?: nowMillis
        return RecordingsSessionRowViewState(
            id = summary.sessionId,
            label = if (isLive) "Tonight" else DAY_FORMAT.format(Instant.ofEpochMilli(summary.startedAtMillis)),
            timeRangeLabel = timeRangeLabel(summary.startedAtMillis, summary.endedAtMillis, isLive),
            durationLabel = if (isLive) {
                stopwatchLabel(end - summary.startedAtMillis)
            } else {
                hoursMinutesLabel(end - summary.startedAtMillis)
            },
            overCount = summary.overCount,
            stationCount = summary.stationCount,
            gapCount = summary.gapCount,
            failedCount = summary.failedCount,
            labelledCount = summary.labelledCount,
            isLive = isLive,
            badges = badgesFor(summary, isLive),
            startedAtUtc = summary.startedAtMillis,
        )
    }

    private fun timeRangeLabel(startedAt: Long, endedAt: Long?, isLive: Boolean): String {
        val start = CLOCK_FORMAT.format(Instant.ofEpochMilli(startedAt))
        val end = if (isLive) "now" else endedAt?.let { CLOCK_FORMAT.format(Instant.ofEpochMilli(it)) } ?: "–"
        return "$start – $end"
    }

    private fun stopwatchLabel(elapsedMillis: Long): String {
        val totalSeconds = (elapsedMillis / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, seconds)
    }

    private fun hoursMinutesLabel(elapsedMillis: Long): String {
        val minutes = (elapsedMillis / 60_000).coerceAtLeast(0)
        return "${minutes / 60} h ${minutes % 60} m"
    }

    /**
     * Priority order for the row's own status badge, matching `Recordings.dc.html`'s own example
     * rows exactly: `capturing` (live) beats a failure count, which beats a labelled count — never
     * more than one status badge per row. The archive/over-audio facts are each their own,
     * independent badge, appended after the status badge whenever real (constitution I: a real
     * removal is never omitted for lack of room).
     */
    private fun badgesFor(summary: RecordingSessionSummary, isLive: Boolean): List<RecordingBadgeViewState> =
        buildList {
            when {
                isLive -> add(RecordingBadgeViewState(RecordingBadgeKind.CAPTURING, "capturing"))
                summary.failedCount > 0 ->
                    add(RecordingBadgeViewState(RecordingBadgeKind.FAILED, "${summary.failedCount} failed"))
                summary.labelledCount > 0 ->
                    add(RecordingBadgeViewState(RecordingBadgeKind.LABELLED, "labelled ${summary.labelledCount}"))
            }
            when (summary.archiveState) {
                ArchiveState.KEPT -> add(RecordingBadgeViewState(RecordingBadgeKind.ARCHIVE_KEPT, "raw kept"))
                ArchiveState.REMOVED -> {
                    val date = summary.archiveRemovedAtMillis?.let {
                        BADGE_DATE_FORMAT.format(Instant.ofEpochMilli(it))
                    }
                    add(RecordingBadgeViewState(RecordingBadgeKind.ARCHIVE_REMOVED, "raw removed $date"))
                }
                ArchiveState.NONE -> Unit
            }
            summary.overAudioRemovedAtMillis?.let { removedAt ->
                val date = BADGE_DATE_FORMAT.format(Instant.ofEpochMilli(removedAt))
                add(RecordingBadgeViewState(RecordingBadgeKind.OVER_AUDIO_REMOVED, "over audio removed $date"))
            }
        }

    private fun overAudioCard(usedBytes: Long, budget: OverAudioBudgetState): OverAudioCardViewState {
        val budgetBytes = budget.budgetBytes
        return OverAudioCardViewState(
            usedBytes = usedBytes,
            budgetGb = budgetBytes?.let { (it / BYTES_PER_GB).toInt() },
            fractionUsed = budgetBytes?.let { (usedBytes.toFloat() / it.toFloat()).coerceIn(0f, 1f) },
            exceeded = budget.exceeded,
        )
    }

    private fun archiveCard(
        usedBytes: Long,
        enabled: Boolean,
        budgetGb: Int,
        rateState: ArchiveWriteRateForecast.State,
    ): ArchiveCardViewState {
        val budgetBytes = budgetGb * BYTES_PER_GB
        val rateLabel = when (rateState) {
            ArchiveWriteRateForecast.State.NotYetMeasured ->
                "about $ARCHIVE_ESTIMATED_MONTHLY_GB GB a month (estimated)"
            is ArchiveWriteRateForecast.State.Measured ->
                "%.1f GB a month (measured)".format(Locale.ROOT, rateState.bytesPerMonth / BYTES_PER_GB)
        }
        return ArchiveCardViewState(
            enabled = enabled,
            usedBytes = usedBytes,
            budgetGb = budgetGb,
            fractionUsed = if (budgetBytes > 0) (usedBytes / budgetBytes).toFloat().coerceIn(0f, 1f) else 0f,
            monthlyRateLabel = rateLabel,
            isMeasuredRate = rateState is ArchiveWriteRateForecast.State.Measured,
        )
    }
}
