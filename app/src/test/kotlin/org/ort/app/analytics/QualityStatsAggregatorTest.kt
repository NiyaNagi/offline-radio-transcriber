package org.ort.app.analytics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.ort.core.AttributionState
import org.ort.core.TransmissionState
import org.ort.core.capture.VadDetectorKind
import org.ort.data.entity.TransmissionEntity
import org.ort.testing.Requirement

/**
 * FR-ANL-2's aggregate transcript-quality statistics — pure arithmetic over plain rows, tested
 * with no Room/Robolectric dependency at all.
 */
class QualityStatsAggregatorTest {

    private fun transmission(
        id: String,
        attributionState: AttributionState = AttributionState.CONFIRMED,
        attributionConfidence: Double? = null,
        vadDetector: VadDetectorKind = VadDetectorKind.SILERO,
    ) = TransmissionEntity(
        id = id,
        sessionId = "S1",
        threadId = null,
        startedAtUtc = 0L,
        endedAtUtc = 1_000L,
        durationMs = 1_000L,
        audioFormat = "flac/16k/mono",
        preRollMs = 200,
        postRollMs = 200,
        frequencyHz = 146_960_000L,
        frequencyProvenance = "measured",
        mode = "FM",
        signalStrength = 7.0,
        channelName = null,
        voiceprintId = null,
        attributionState = attributionState,
        stationId = if (attributionState == AttributionState.UNKNOWN) null else "WA7HJR",
        attributionConfidence = attributionConfidence,
        attributionSourceTransmissionId = null,
        corrected = false,
        processingState = TransmissionState.CAPTURED,
        rejectionReason = null,
        samplePosition = 0L,
        monotonicStartNanos = 0L,
        utcOffsetMinutes = 0,
        calibrationId = null,
        executionProvider = null,
        vadDetector = vadDetector,
    )

    @Test
    @Requirement("FR-ANL-2")
    fun `FR_ANL_2_aggregate is null when there is nothing to aggregate`() {
        assertNull(QualityStatsAggregator.aggregate(emptyList(), emptyList()))
    }

    @Test
    @Requirement("FR-ANL-2")
    fun `FR_ANL_2_attribution state mix is the fraction of transmissions in each state`() {
        val transmissions = listOf(
            transmission("T1", AttributionState.CONFIRMED),
            transmission("T2", AttributionState.CONFIRMED),
            transmission("T3", AttributionState.UNKNOWN),
            transmission("T4", AttributionState.AMBIGUOUS),
        )

        val stats = QualityStatsAggregator.aggregate(transmissions, emptyList())!!

        assertEquals(0.5, stats.attributionStateMix["CONFIRMED"])
        assertEquals(0.25, stats.attributionStateMix["UNKNOWN"])
        assertEquals(0.25, stats.attributionStateMix["AMBIGUOUS"])
    }

    @Test
    @Requirement("FR-ANL-2")
    fun `FR_ANL_2_unresolved callsign rate counts only UNKNOWN attributions`() {
        val transmissions = listOf(
            transmission("T1", AttributionState.CONFIRMED),
            transmission("T2", AttributionState.UNKNOWN),
            transmission("T3", AttributionState.UNKNOWN),
            transmission("T4", AttributionState.INFERRED),
        )

        val stats = QualityStatsAggregator.aggregate(transmissions, emptyList())!!

        assertEquals(0.5, stats.unresolvedCallsignRate)
    }

    @Test
    @Requirement("FR-ANL-2", "FR-SEG-10")
    fun `FR_ANL_2_vad fallback rate counts only transmissions whose boundary does not conform to FR-SEG-1`() {
        val transmissions = listOf(
            transmission("T1", vadDetector = VadDetectorKind.SILERO),
            transmission("T2", vadDetector = VadDetectorKind.TEN_VAD),
            transmission("T3", vadDetector = VadDetectorKind.ENERGY),
            transmission("T4", vadDetector = VadDetectorKind.UNKNOWN),
        )

        val stats = QualityStatsAggregator.aggregate(transmissions, emptyList())!!

        // ENERGY and UNKNOWN both fail conformsToFrSeg1 (VadDetectorKind's own extension) -- 2 of 4.
        assertEquals(0.5, stats.vadFallbackRate)
    }

    @Test
    @Requirement("FR-ANL-2")
    fun `FR_ANL_2_confidence mix buckets only transmissions with a real confidence value`() {
        val transmissions = listOf(
            transmission("T1", attributionConfidence = 0.95),
            transmission("T2", attributionConfidence = 0.6),
            transmission("T3", attributionConfidence = 0.2),
            transmission("T4", attributionConfidence = null),
        )

        val stats = QualityStatsAggregator.aggregate(transmissions, emptyList())!!

        assertEquals(1.0 / 3.0, stats.confidenceMix["high"])
        assertEquals(1.0 / 3.0, stats.confidenceMix["medium"])
        assertEquals(1.0 / 3.0, stats.confidenceMix["low"])
    }

    @Test
    @Requirement("FR-ANL-2")
    fun `FR_ANL_2_confidence mix is empty when no transmission has a measured confidence`() {
        val transmissions = listOf(transmission("T1", attributionConfidence = null))

        val stats = QualityStatsAggregator.aggregate(transmissions, emptyList())!!

        assertEquals(emptyMap<String, Double>(), stats.confidenceMix)
    }

    @Test
    @Requirement("FR-ANL-2")
    fun `FR_ANL_2_correction rate by field divides each field's count by the total transmission count`() {
        val transmissions = listOf(transmission("T1"), transmission("T2"), transmission("T3"), transmission("T4"))
        val correctionFields = listOf("stationId", "stationId", "stationId_unverified")

        val stats = QualityStatsAggregator.aggregate(transmissions, correctionFields)!!

        assertEquals(0.5, stats.correctionRateByField["stationId"])
        assertEquals(0.25, stats.correctionRateByField["stationId_unverified"])
    }

    @Test
    @Requirement("FR-ANL-2", "AC-172")
    fun `FR_ANL_2_never contains a transcript, a callsign, a name or a location field`() {
        // Structural proof this aggregator cannot leak forbidden content: it only ever returns
        // AnalyticsTier1Payload.QualityStats, whose own field-vocabulary test
        // (AnalyticsFieldVocabularyTest.AC_172) already fixes the exact allowed shape — this test
        // exists so a reviewer of *this* file sees the guarantee stated here too, not only in
        // :telemetry.
        val stats = QualityStatsAggregator.aggregate(listOf(transmission("T1")), emptyList())!!
        val fieldNames = stats.javaClass.declaredFields.map { it.name.lowercase() }
        val forbidden = setOf("transcript", "callsign", "name", "location", "audio")
        assertEquals(emptyList<String>(), fieldNames.filter { it in forbidden })
    }
}
