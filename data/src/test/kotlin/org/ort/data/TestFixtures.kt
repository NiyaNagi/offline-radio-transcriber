package org.ort.data

import org.ort.core.AttributionState
import org.ort.core.TransmissionState
import org.ort.data.entity.SessionEntity
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
    ): TransmissionEntity = TransmissionEntity(
        id = id,
        sessionId = sessionId,
        threadId = null,
        startedAtUtc = 0L,
        endedAtUtc = 1_000L,
        durationMs = 1_000L,
        audioFormat = "flac/16k/mono",
        preRollMs = 200,
        postRollMs = 200,
        frequencyHz = null,
        frequencyProvenance = "measured",
        mode = null,
        signalStrength = null,
        channelName = null,
        voiceprintId = null,
        attributionState = AttributionState.UNKNOWN,
        stationId = null,
        attributionConfidence = null,
        attributionSourceTransmissionId = null,
        processingState = processingState,
        rejectionReason = null,
        samplePosition = samplePosition,
        monotonicStartNanos = 0L,
        utcOffsetMinutes = 0,
        calibrationId = null,
        executionProvider = null,
    )
}
