package org.ort.app.ui.data

import org.ort.data.entity.StationEntity
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * One row of the "Stations" list (FR-UI-9) — `ReaderPolling.listStationSummaries` supplies real
 * [org.ort.data.entity.StationEntity] rows.
 */
public data class StationListEntryViewState(
    val stationId: String,
    val label: String,
    val transmissionCount: Int,
    val lastHeardLabel: String?,
)

/** One row of the "Frequencies" list (FR-UI-10). */
public data class FrequencyListEntryViewState(val frequencyHz: Long, val label: String, val transmissionCount: Int)

/**
 * Everything heard from one station, across every session (FR-UI-9), plus its activity pattern
 * (FR-UI-11, [activityPattern] — see [ActivityPatternMapper] for FR-UI-12's not-heard/not-
 * listening distinction, which this view state renders rather than re-derives).
 */
public data class StationDetailViewState(
    val stationId: String,
    val label: String,
    val transmissionCount: Int,
    val activityPattern: List<HourActivityBucket>,
    /** FR-UI-11's "by day of week" half (audit F-019). */
    val dayOfWeekPattern: List<DayOfWeekActivityBucket> = emptyList(),
    /** FR-UI-11's "and how that has changed" half (audit F-019) — one line per weekday, honest text only. */
    val weekOverWeekSummary: List<String> = emptyList(),
    val transmissions: List<TransmissionListEntryViewState>,
)

/** Everything heard on one frequency, across every session (FR-UI-10), plus its activity pattern (FR-UI-11). */
public data class FrequencyDetailViewState(
    val frequencyHz: Long,
    val label: String,
    val transmissionCount: Int,
    val activityPattern: List<HourActivityBucket>,
    /** FR-UI-11's "by day of week" half (audit F-019). */
    val dayOfWeekPattern: List<DayOfWeekActivityBucket> = emptyList(),
    /** FR-UI-11's "and how that has changed" half (audit F-019) — one line per weekday, honest text only. */
    val weekOverWeekSummary: List<String> = emptyList(),
    val transmissions: List<TransmissionListEntryViewState>,
)

public object StationViewMapper {

    public fun listEntry(station: StationEntity): StationListEntryViewState = StationListEntryViewState(
        stationId = station.id,
        label = station.callsign ?: station.id,
        transmissionCount = station.transmissionCount,
        lastHeardLabel = dateTimeLabel(station.lastHeardAt),
    )

    public fun detail(
        stationId: String,
        label: String,
        transmissions: List<TransmissionDetail>,
        activityPattern: List<HourActivityBucket>,
        dayOfWeekPattern: List<DayOfWeekActivityBucket> = emptyList(),
        weekOverWeekComparison: List<WeekOverWeekBucket> = emptyList(),
    ): StationDetailViewState = StationDetailViewState(
        stationId = stationId,
        label = label,
        transmissionCount = transmissions.size,
        activityPattern = activityPattern,
        dayOfWeekPattern = dayOfWeekPattern,
        weekOverWeekSummary = weekOverWeekSummaryText(weekOverWeekComparison),
        transmissions = transmissions.map { ReaderTransmissionViewStateMapper.listEntry(it) },
    )
}

public object FrequencyViewMapper {

    public fun listEntry(frequencyHz: Long, transmissionCount: Int): FrequencyListEntryViewState =
        FrequencyListEntryViewState(
            frequencyHz = frequencyHz,
            label = frequencyLabel(frequencyHz),
            transmissionCount = transmissionCount,
        )

    public fun detail(
        frequencyHz: Long,
        transmissions: List<TransmissionDetail>,
        activityPattern: List<HourActivityBucket>,
        dayOfWeekPattern: List<DayOfWeekActivityBucket> = emptyList(),
        weekOverWeekComparison: List<WeekOverWeekBucket> = emptyList(),
    ): FrequencyDetailViewState = FrequencyDetailViewState(
        frequencyHz = frequencyHz,
        label = frequencyLabel(frequencyHz),
        transmissionCount = transmissions.size,
        activityPattern = activityPattern,
        dayOfWeekPattern = dayOfWeekPattern,
        weekOverWeekSummary = weekOverWeekSummaryText(weekOverWeekComparison),
        transmissions = transmissions.map { ReaderTransmissionViewStateMapper.listEntry(it) },
    )

    private fun frequencyLabel(frequencyHz: Long): String = "%.3f MHz".format(Locale.ROOT, frequencyHz / 1_000_000.0)
}

private val LABEL_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT)
    .withZone(ZoneOffset.UTC)

private fun dateTimeLabel(utcMillis: Long?): String? = utcMillis?.let { LABEL_FORMAT.format(Instant.ofEpochMilli(it)) }

/**
 * Turns FR-UI-11's "how that has changed" comparison into the one honest sentence per weekday
 * (audit F-019) — text only, never a chart, since a single-week-over-week sample is too thin to
 * plot as a trend line without implying more precision than it has.
 */
public fun dayOfWeekShortLabel(dayOfWeek: java.time.DayOfWeek): String =
    dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ROOT)

internal fun weekOverWeekSummaryText(buckets: List<WeekOverWeekBucket>): List<String> = buckets.map { bucket ->
    val day = bucket.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ROOT)
    when (bucket.trend) {
        WeekTrend.NO_DATA -> "$day: no data"
        WeekTrend.UP -> "$day: up (${bucket.currentHeardCount} vs ${bucket.previousHeardCount} last week)"
        WeekTrend.DOWN -> "$day: down (${bucket.currentHeardCount} vs ${bucket.previousHeardCount} last week)"
        WeekTrend.FLAT -> "$day: flat (${bucket.currentHeardCount})"
    }
}
