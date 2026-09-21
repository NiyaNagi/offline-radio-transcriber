package org.ort.app.backup

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.AttributionState
import org.ort.core.Tier
import org.ort.core.TransmissionState
import org.ort.core.capture.VadDetectorKind
import org.ort.data.entity.CorrectionEntity
import org.ort.data.entity.OverCountsByAttributionState
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.StationEntity
import org.ort.data.entity.TerminationReason
import org.ort.data.entity.ThreadEntity
import org.ort.data.entity.ThreadKind
import org.ort.data.entity.ThreadKindSource
import org.ort.data.entity.TranscriptEntity
import org.ort.data.entity.TranscriptPass
import org.ort.data.entity.TransmissionEntity
import org.ort.data.entity.VoiceprintBindingSource
import org.ort.data.entity.VoiceprintEntity
import org.robolectric.RobolectricTestRunner

/**
 * FR-STO-6/FR-STO-9. `org.json` (Android platform, no new dependency — the same library
 * `org.ort.app.export.DebugDumpBuilder` already uses) round-trips every field these four
 * entities carry — including every nullable field set to `null` *and* to a real value, since
 * [BackupRecordCodecs]'s own `putNullable` exists specifically because a naive `JSONObject.put`
 * silently drops a `null` value instead of writing one.
 */
@RunWith(RobolectricTestRunner::class)
class BackupRecordCodecsTest {

    @Test
    fun `FR_STO_6 SessionCodec round-trips every field, fully populated`() {
        val entity = SessionEntity(
            id = "S1",
            startedAt = 1_000L,
            endedAt = 2_000L,
            profileId = "profile-1",
            deviceTier = "mid",
            appVersion = "0.1.1",
            terminationReason = TerminationReason.USER,
            sourceId = "src-1",
            schemaVersion = 14,
            gapCount = 2,
            shedEvents = 1,
            captureMode = "LOCAL_MICROPHONE",
            audioRouteKind = "USB",
            audioRouteLabel = "USB Audio Adapter",
            bluetoothProfile = "SCO",
            rigTransport = "USB",
            rigDescriptorId = "kenwood-thd75a",
            audioRouteVerified = true,
            audioNativeRateHz = 48_000,
            archiveState = "KEPT",
            archiveRemovedAtMillis = 3_000L,
            overAudioRemovedAtMillis = 4_000L,
            vadDetector = VadDetectorKind.SILERO,
            vadDetectorVersion = "1.2",
        )
        assertEquals(entity, SessionCodec.fromJson(SessionCodec.toJson(entity)))
    }

    @Test
    fun `FR_STO_6 SessionCodec round-trips every nullable field as null`() {
        val entity = SessionEntity(
            id = "S2",
            startedAt = 0L,
            endedAt = null,
            profileId = null,
            deviceTier = null,
            appVersion = null,
            terminationReason = null,
            sourceId = null,
            schemaVersion = 1,
        )
        assertEquals(entity, SessionCodec.fromJson(SessionCodec.toJson(entity)))
    }

    @Test
    fun `FR_STO_6 TransmissionCodec round-trips every field, fully populated`() {
        val entity = TransmissionEntity(
            id = "T1",
            sessionId = "S1",
            threadId = "TH1",
            startedAtUtc = 10L,
            endedAtUtc = 20L,
            durationMs = 10_000L,
            audioFormat = "flac/16k/mono",
            preRollMs = 200,
            postRollMs = 200,
            frequencyHz = 146_520_000L,
            frequencyProvenance = "measured",
            mode = "FM",
            signalStrength = -80.0,
            channelName = "Repeater 1",
            voiceprintId = "VP1",
            attributionState = AttributionState.CONFIRMED,
            stationId = "KI7ABC",
            attributionConfidence = 0.94,
            attributionSourceTransmissionId = "T0",
            corrected = true,
            processingState = TransmissionState.COMPLETE,
            rejectionReason = "too short",
            samplePosition = 12345L,
            monotonicStartNanos = 999L,
            utcOffsetMinutes = -420,
            calibrationId = "CAL1",
            enhancementApplied = listOf("noise_reduction", "band_pass"),
            executionProvider = "cpu",
            isReprocessCandidate = true,
            processedTier = Tier.T2,
            rigStateChangedMidTransmission = true,
            vadDetector = VadDetectorKind.TEN_VAD,
            vadDetectorVersion = "2.0",
            rigSquelchFusionApplied = true,
        )
        assertEquals(entity, TransmissionCodec.fromJson(TransmissionCodec.toJson(entity)))
    }

    @Test
    fun `FR_STO_6 TransmissionCodec round-trips every nullable field as null, including an empty enhancement list`() {
        val entity = TransmissionEntity(
            id = "T2",
            sessionId = "S1",
            threadId = null,
            startedAtUtc = 0L,
            endedAtUtc = null,
            durationMs = 0L,
            audioFormat = "flac/16k/mono",
            preRollMs = 0,
            postRollMs = 0,
            frequencyHz = null,
            frequencyProvenance = "unknown",
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
        assertEquals(entity, TransmissionCodec.fromJson(TransmissionCodec.toJson(entity)))
    }

    @Test
    fun `FR_STO_6 TranscriptCodec round-trips a current transcript, including a null noSpeechProb`() {
        val entity = TranscriptEntity(
            id = "TR1",
            transmissionId = "T1",
            pass = TranscriptPass.B,
            text = "this is KI7ABC",
            modelId = "whisper-small",
            modelVersion = "1.2.0",
            quantization = "int8",
            decodeParams = "beam=5",
            noSpeechProb = null,
            confidence = 0.87,
            isCurrent = true,
            createdAt = 500L,
        )
        assertEquals(entity, TranscriptCodec.fromJson(TranscriptCodec.toJson(entity)))
    }

    @Test
    fun `FR_STO_6 TranscriptCodec round-trips a real noSpeechProb Float exactly`() {
        val entity = TranscriptEntity(
            id = "TR2",
            transmissionId = "T1",
            pass = TranscriptPass.A,
            text = "partial",
            modelId = "whisper-small",
            modelVersion = "1.2.0",
            quantization = null,
            decodeParams = null,
            noSpeechProb = 0.03f,
            confidence = null,
            isCurrent = false,
            createdAt = 400L,
        )
        assertEquals(entity, TranscriptCodec.fromJson(TranscriptCodec.toJson(entity)))
    }

    @Test
    fun `FR_STO_9 CorrectionCodec round-trips every field, fully populated`() {
        val entity = CorrectionEntity(
            id = "C1",
            transmissionId = "T1",
            field = "stationId",
            previousValue = "OLD1",
            newValue = "NEW1",
            correctedAt = 600L,
            propagatedToCount = 3,
            previousAttributionState = AttributionState.AMBIGUOUS,
            previousAttributionConfidence = 0.5,
            previousAttributionSourceTransmissionId = "T0",
            previousCorrected = false,
        )
        assertEquals(entity, CorrectionCodec.fromJson(CorrectionCodec.toJson(entity)))
    }

    @Test
    fun `FR_STO_9 CorrectionCodec round-trips every nullable field as null`() {
        val entity = CorrectionEntity(
            id = "C2",
            transmissionId = "T1",
            field = "stationId",
            previousValue = null,
            newValue = "NEW1",
            correctedAt = 0L,
        )
        assertEquals(entity, CorrectionCodec.fromJson(CorrectionCodec.toJson(entity)))
    }

    @Test
    fun `R_1094 StationCodec round-trips every field, fully populated, except overCountsByAttributionState`() {
        val entity = StationEntity(
            id = "KI7ABC",
            callsign = "KI7ABC",
            firstHeardAt = 100L,
            lastHeardAt = 200L,
            transmissionCount = 5,
            isUserPinned = true,
            notes = "Net control most Tuesdays",
            userName = "Alex",
            frequenciesHeard = listOf(146_520_000L, 147_000_000L),
            activityByHourDow = "mon:1,tue:4",
            potaRefs = listOf("K-1234"),
            spokenGrids = listOf("CN87"),
            ituRegionFromPrefix = "3",
            // A non-EMPTY value here proves it is never trusted -- fromJson always overwrites it.
            overCountsByAttributionState = "CONFIRMED=99",
        )
        val restored = StationCodec.fromJson(StationCodec.toJson(entity))
        val expected = entity.copy(overCountsByAttributionState = OverCountsByAttributionState.EMPTY.serialize())
        assertEquals(expected, restored)
    }

    @Test
    fun `R_1094 StationCodec round-trips every nullable field as null`() {
        val entity = StationEntity(
            id = "W7NPC",
            callsign = null,
            firstHeardAt = null,
            lastHeardAt = null,
            transmissionCount = 0,
            isUserPinned = false,
            notes = null,
            userName = null,
            frequenciesHeard = null,
            activityByHourDow = null,
            potaRefs = null,
            spokenGrids = null,
            ituRegionFromPrefix = null,
            overCountsByAttributionState = OverCountsByAttributionState.EMPTY.serialize(),
        )
        assertEquals(entity, StationCodec.fromJson(StationCodec.toJson(entity)))
    }

    @Test
    fun `R_1094 StationCodec never writes overCountsByAttributionState -- the derived column stays derived`() {
        val entity = StationEntity(
            id = "KI7ABC",
            callsign = "KI7ABC",
            firstHeardAt = 100L,
            lastHeardAt = 200L,
            transmissionCount = 5,
            isUserPinned = false,
            notes = null,
            userName = null,
            frequenciesHeard = null,
            activityByHourDow = null,
            potaRefs = null,
            spokenGrids = null,
            ituRegionFromPrefix = null,
            overCountsByAttributionState = "CONFIRMED=99",
        )
        assertEquals(false, StationCodec.toJson(entity).has("overCountsByAttributionState"))
    }

    @Test
    fun `R_1094 VoiceprintCodec round-trips every field, fully populated, including the raw embedding bytes`() {
        val entity = VoiceprintEntity(
            id = "VP1",
            embedding = byteArrayOf(1, 2, 3, 4, 5, -1, 0, 127),
            memberCount = 3,
            centroidUpdatedAt = 500L,
            boundStationId = "KI7ABC",
            bindingConfidence = 0.92,
            lastConfirmedAt = 600L,
            isEnrolled = true,
            enrolmentObservationCount = 4,
            enrolmentSessionIds = listOf("S1", "S2"),
            enrolledAt = 700L,
            lastMatchedAt = 800L,
            bindingSource = VoiceprintBindingSource.MANUAL,
            embeddingModelId = "ecapa-tdnn",
            embeddingModelVersion = "1.0",
        )
        assertEquals(entity, VoiceprintCodec.fromJson(VoiceprintCodec.toJson(entity)))
    }

    @Test
    fun `R_1094 VoiceprintCodec round-trips every nullable field as null`() {
        val entity = VoiceprintEntity(
            id = "VP2",
            embedding = byteArrayOf(),
            memberCount = 0,
            centroidUpdatedAt = null,
            boundStationId = null,
            bindingConfidence = null,
            lastConfirmedAt = null,
            isEnrolled = false,
            enrolmentObservationCount = 0,
            enrolmentSessionIds = null,
            enrolledAt = null,
            lastMatchedAt = null,
            bindingSource = null,
            embeddingModelId = null,
            embeddingModelVersion = null,
        )
        assertEquals(entity, VoiceprintCodec.fromJson(VoiceprintCodec.toJson(entity)))
    }

    @Test
    fun `R_1094 ThreadCodec round-trips every field, fully populated`() {
        val entity = ThreadEntity(
            id = "TH1",
            sessionId = "S1",
            startedAt = 1_000L,
            endedAt = 2_000L,
            frequencyHz = 146_520_000L,
            transmissionCount = 6,
            participantStationIds = listOf("KI7ABC", "W7NPC"),
            digestText = "Net check-ins",
            kind = ThreadKind.NET,
            kindSource = ThreadKindSource.DETECTED,
            participantOrder = listOf("KI7ABC", "W7NPC", "KI7ABC"),
        )
        assertEquals(entity, ThreadCodec.fromJson(ThreadCodec.toJson(entity)))
    }

    @Test
    fun `R_1094 ThreadCodec round-trips every nullable field as null`() {
        val entity = ThreadEntity(
            id = "TH2",
            sessionId = "S1",
            startedAt = 0L,
            endedAt = null,
            frequencyHz = null,
            transmissionCount = 1,
            participantStationIds = null,
            digestText = null,
            kind = ThreadKind.UNKNOWN,
            kindSource = ThreadKindSource.USER,
            participantOrder = null,
        )
        assertEquals(entity, ThreadCodec.fromJson(ThreadCodec.toJson(entity)))
    }
}
