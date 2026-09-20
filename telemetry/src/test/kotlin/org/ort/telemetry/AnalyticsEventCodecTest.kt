package org.ort.telemetry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.ort.testing.Requirement

class AnalyticsEventCodecTest {

    private fun provenance(installId: String = "install-1") = AnalyticsProvenance(
        installId = installId,
        sessionId = "session-1",
        overId = "over-1",
        appVersion = "0.1.1",
        buildHash = "abc123",
        modelIds = listOf("whisper-tiny"),
        modelShas = listOf("deadbeef"),
        executionProvider = "cpu",
        deviceModel = "Pixel 7",
        soc = "Tensor G2",
        detectedTier = "T2",
        captureMode = "LOCAL_MICROPHONE",
        rigModule = null,
        band = null,
        schemaVersion = ANALYTICS_SCHEMA_VERSION,
    )

    @Test
    @Requirement("AC-176", "FR-ANL-6")
    fun `AC_176_encoding the same recomputed event twice produces the byte-identical line`() {
        val eventA = AnalyticsEventFactory.tier1(provenance(), AnalyticsTier1Payload.Usage("SETTINGS", "OPEN"))
        val eventB = AnalyticsEventFactory.tier1(provenance(), AnalyticsTier1Payload.Usage("SETTINGS", "OPEN"))

        assertEquals(AnalyticsEventCodec.encode(eventA), AnalyticsEventCodec.encode(eventB))
    }

    @Test
    @Requirement("AC-176")
    fun `AC_176_decoding a re-encoded event returns an equal event (round-trip)`() {
        val event = AnalyticsEventFactory.tier2(
            provenance(),
            AnalyticsTier2Payload.Correction("N9ABC", "N9ABD", "N9ABD"),
        )

        val decoded = AnalyticsEventCodec.decode(AnalyticsEventCodec.encode(event))

        assertEquals(event, decoded)
    }

    @Test
    @Requirement("AC-176")
    fun `AC_176_a different field value changes the encoded line (the codec is not a constant)`() {
        val eventA = AnalyticsEventFactory.tier1(provenance(), AnalyticsTier1Payload.Usage("SETTINGS", "OPEN"))
        val eventB = AnalyticsEventFactory.tier1(provenance(), AnalyticsTier1Payload.Usage("SETTINGS", "CLOSE"))

        org.junit.jupiter.api.Assertions.assertNotEquals(
            AnalyticsEventCodec.encode(eventA),
            AnalyticsEventCodec.encode(eventB),
        )
    }
}
