package org.ort.app.ui.data

import org.ort.data.entity.StationEntity
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
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
    val transmissions: List<TransmissionListEntryViewState>,
)

/** Everything heard on one frequency, across every session (FR-UI-10), plus its activity pattern (FR-UI-11). */
public data class FrequencyDetailViewState(
    val frequencyHz: Long,
    val label: String,
    val transmissionCount: Int,
    val activityPattern: List<HourActivityBucket>,
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
    ): StationDetailViewState = StationDetailViewState(
        stationId = stationId,
        label = label,
        transmissionCount = transmissions.size,
        activityPattern = activityPattern,
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
    ): FrequencyDetailViewState = FrequencyDetailViewState(
        frequencyHz = frequencyHz,
        label = frequencyLabel(frequencyHz),
        transmissionCount = transmissions.size,
        activityPattern = activityPattern,
        transmissions = transmissions.map { ReaderTransmissionViewStateMapper.listEntry(it) },
    )

    private fun frequencyLabel(frequencyHz: Long): String = "%.3f MHz".format(Locale.ROOT, frequencyHz / 1_000_000.0)
}

private val LABEL_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT)
    .withZone(ZoneOffset.UTC)

private fun dateTimeLabel(utcMillis: Long?): String? = utcMillis?.let { LABEL_FORMAT.format(Instant.ofEpochMilli(it)) }
