package org.ort.data

import org.ort.core.AttributionState
import org.ort.core.TransmissionState
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.StationEntity
import org.ort.data.entity.TransmissionEntity

/** Minimal, valid rows for tests that only care about one or two columns. */
internal object TestFixtures {

    fun session(id: String = "SESSION01", startedAt: Long = 0L): SessionEntity = SessionEntity(
        id = id,
        startedAt = startedAt,
        endedAt = null,
        profileId = null,
        deviceTier = null,
        appVersion = "test",
        terminationReason = null,
        sourceId = null,
        schemaVersion = OrtDatabase.SCHEMA_VERSION,
    )

    fun transmission(
        id: String,
        sessionId: String = "SESSION01",
        processingState: TransmissionState = TransmissionState.CAPTURED,
        samplePosition: Long = 0L,
        startedAtUtc: Long = 0L,
        frequencyHz: Long? = null,
        stationId: String? = null,
        attributionState: AttributionState = if (stationId != null) {
            AttributionState.CONFIRMED
        } else {
            AttributionState.UNKNOWN
        },
    ): TransmissionEntity = TransmissionEntity(
        id = id,
        sessionId = sessionId,
        threadId = null,
        startedAtUtc = startedAtUtc,
        endedAtUtc = startedAtUtc + 1_000L,
        durationMs = 1_000L,
        audioFormat = "flac/16k/mono",
        preRollMs = 200,
        postRollMs = 200,
        frequencyHz = frequencyHz,
        frequencyProvenance = "measured",
        mode = null,
        signalStrength = null,
        channelName = null,
        voiceprintId = null,
        attributionState = attributionState,
        stationId = stationId,
        attributionConfidence = if (stationId != null) 0.9 else null,
        attributionSourceTransmissionId = null,
        processingState = processingState,
        rejectionReason = null,
        samplePosition = samplePosition,
        monotonicStartNanos = 0L,
        utcOffsetMinutes = 0,
        calibrationId = null,
        executionProvider = null,
    )

    /** A minimal, valid [StationEntity] row — build-plan P17's `ActivityDao` tests. */
    fun station(id: String, lastHeardAt: Long? = 0L, transmissionCount: Int = 0): StationEntity = StationEntity(
        id = id,
        callsign = id,
        firstHeardAt = 0L,
        lastHeardAt = lastHeardAt,
        transmissionCount = transmissionCount,
        isUserPinned = false,
        notes = null,
        userName = null,
        frequenciesHeard = null,
        activityByHourDow = null,
        potaRefs = null,
        spokenGrids = null,
        ituRegionFromPrefix = null,
        overCountsByAttributionState = null,
    )
}
