package org.ort.telemetry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.ort.testing.Requirement

class AnalyticsControllerTest {

    private fun provenance() = AnalyticsProvenance(
        installId = "install-1",
        sessionId = null,
        overId = null,
        appVersion = "0.1.1",
        buildHash = "abc123",
        modelIds = emptyList(),
        modelShas = emptyList(),
        executionProvider = "cpu",
        deviceModel = "test",
        soc = "test",
        detectedTier = "T2",
        captureMode = null,
        rigModule = null,
        band = null,
        schemaVersion = ANALYTICS_SCHEMA_VERSION,
    )

    private fun controller(
        preferences: AnalyticsTierPreferences = InMemoryAnalyticsTierPreferences(),
        queue: AnalyticsEventQueue = InMemoryAnalyticsEventQueue(maxCount = 100, maxBytes = Long.MAX_VALUE),
    ) = AnalyticsController(queue, preferences) to queue

    @Test
    @Requirement("AC-172", "FR-ANL-1")
    fun `AC_172_tier 1 submits by default`() {
        val (controller, queue) = controller()

        controller.submit(AnalyticsEventFactory.tier1(provenance(), AnalyticsTier1Payload.Usage("SETTINGS", "OPEN")))

        assertEquals(1, queue.size())
    }

    @Test
    @Requirement("AC-173", "FR-ANL-3", "FR-ANL-9")
    fun `AC_173_tier 2 is never queued while disabled`() {
        val (controller, queue) = controller()

        controller.submit(
            AnalyticsEventFactory.tier2(provenance(), AnalyticsTier2Payload.Transcript("hello", "N9ABC")),
        )

        assertEquals(0, queue.size())
    }

    @Test
    @Requirement("AC-173", "FR-ANL-3")
    fun `AC_173_tier 2 queues once enabled`() {
        val (controller, queue) = controller()
        controller.setTierEnabled(AnalyticsTier.TIER_2, true)

        controller.submit(
            AnalyticsEventFactory.tier2(provenance(), AnalyticsTier2Payload.Transcript("hello", "N9ABC")),
        )

        assertEquals(1, queue.size())
    }

    @Test
    @Requirement("AC-174", "FR-ANL-4")
    fun `AC_174_tier 3 is never queued while disabled`() {
        val (controller, queue) = controller()

        controller.submit(
            AnalyticsEventFactory.tier3(provenance(), AnalyticsTier3Payload("YWJj", "hello")),
        )

        assertEquals(0, queue.size())
    }

    @Test
    @Requirement("AC-179", "FR-ANL-9")
    fun `AC_179_disabling a tier purges its already-queued, not-yet-sent events immediately`() {
        val (controller, queue) = controller()
        controller.setTierEnabled(AnalyticsTier.TIER_2, true)
        controller.submit(
            AnalyticsEventFactory.tier2(provenance(), AnalyticsTier2Payload.Transcript("hello", "N9ABC")),
        )
        assertEquals(1, queue.size())

        controller.setTierEnabled(AnalyticsTier.TIER_2, false)

        assertEquals(0, queue.size())
    }

    @Test
    @Requirement("AC-179")
    fun `AC_179_disabling one tier never purges another tier's queued events`() {
        val (controller, queue) = controller()
        controller.submit(AnalyticsEventFactory.tier1(provenance(), AnalyticsTier1Payload.Usage("SETTINGS", "OPEN")))
        controller.setTierEnabled(AnalyticsTier.TIER_2, true)
        controller.submit(
            AnalyticsEventFactory.tier2(provenance(), AnalyticsTier2Payload.Transcript("hello", "N9ABC")),
        )

        controller.setTierEnabled(AnalyticsTier.TIER_2, false)

        assertEquals(1, queue.size())
        assertEquals(AnalyticsTier.TIER_1, queue.peekAll().single().tier)
    }

    @Test
    fun `submit returns null when the tier is disabled, the enqueue outcome otherwise`() {
        val (controller, _) = controller()

        val result = controller.submit(
            AnalyticsEventFactory.tier2(provenance(), AnalyticsTier2Payload.Transcript("hello", null)),
        )

        assertNull(result)
    }
}
