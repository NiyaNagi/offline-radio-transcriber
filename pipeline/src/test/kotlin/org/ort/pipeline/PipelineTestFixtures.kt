package org.ort.pipeline

import org.ort.core.AttributionState
import org.ort.core.TransmissionState
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.TransmissionEntity

/** Minimal, valid rows — this module's own copy; `:data`'s `TestFixtures` is `internal`. */
internal object PipelineTestFixtures {

    fun session(id: String = "SESSION01"): SessionEntity = SessionEntity(
        id = id,
        startedAt = 0L,
        endedAt = null,
        profileId = null,
        deviceTier = null,
        appVersion = "test",
        terminationReason = null,
        sourceId = null,
        schemaVersion = org.ort.data.OrtDatabase.SCHEMA_VERSION,
    )

    fun transmission(id: String, sessionId: String = "SESSION01"): TransmissionEntity = TransmissionEntity(
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
        processingState = TransmissionState.CAPTURED,
        rejectionReason = null,
        samplePosition = 0L,
        monotonicStartNanos = 0L,
        utcOffsetMinutes = 0,
        calibrationId = null,
        executionProvider = null,
    )
}
