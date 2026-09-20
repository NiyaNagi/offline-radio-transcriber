package org.ort.telemetry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.testing.Requirement

class InMemoryAnalyticsEventQueueTest {

    private fun event(action: String) = AnalyticsEventFactory.tier1(
        AnalyticsProvenance(
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
        ),
        AnalyticsTier1Payload.Usage("SETTINGS", action),
    )

    @Test
    @Requirement("AC-183", "FR-ANL-13")
    fun `AC_183_enqueue beyond the count bound drops the oldest, never throws`() {
        val queue = InMemoryAnalyticsEventQueue(maxCount = 3, maxBytes = Long.MAX_VALUE)

        queue.enqueue(event("A"))
        queue.enqueue(event("B"))
        queue.enqueue(event("C"))
        val outcome = queue.enqueue(event("D"))

        assertEquals(EnqueueOutcome.DROPPED_OLDEST, outcome)
        assertEquals(3, queue.size())
        assertEquals(1L, queue.droppedCount())
        val remainingActions = queue.peekAll().map { (it.payload as AnalyticsTier1Payload.Usage).action }
        assertEquals(listOf("B", "C", "D"), remainingActions)
    }

    @Test
    @Requirement("AC-183")
    fun `AC_183_enqueue beyond the byte bound drops the oldest`() {
        val single = AnalyticsEventCodec.byteSize(event("A"))
        val queue = InMemoryAnalyticsEventQueue(maxCount = Int.MAX_VALUE, maxBytes = single * 2)

        queue.enqueue(event("A"))
        queue.enqueue(event("B"))
        val outcome = queue.enqueue(event("C"))

        assertEquals(EnqueueOutcome.DROPPED_OLDEST, outcome)
        assertTrue(queue.byteSize() <= single * 2)
        assertEquals(listOf("B", "C"), queue.peekAll().map { (it.payload as AnalyticsTier1Payload.Usage).action })
    }

    @Test
    @Requirement("AC-183")
    fun `AC_183_a queue under its bound reports QUEUED and never drops`() {
        val queue = InMemoryAnalyticsEventQueue(maxCount = 10, maxBytes = Long.MAX_VALUE)

        val outcome = queue.enqueue(event("A"))

        assertEquals(EnqueueOutcome.QUEUED, outcome)
        assertEquals(0L, queue.droppedCount())
    }

    @Test
    fun `drain removes and returns events in FIFO order, up to the requested max`() {
        val queue = InMemoryAnalyticsEventQueue(maxCount = 10, maxBytes = Long.MAX_VALUE)
        queue.enqueue(event("A"))
        queue.enqueue(event("B"))
        queue.enqueue(event("C"))

        val drained = queue.drain(max = 2)

        assertEquals(listOf("A", "B"), drained.map { (it.payload as AnalyticsTier1Payload.Usage).action })
        assertEquals(listOf("C"), queue.peekAll().map { (it.payload as AnalyticsTier1Payload.Usage).action })
    }

    @Test
    @Requirement("AC-179", "FR-ANL-9")
    fun `AC_179_removeAll purges every event matching the predicate immediately`() {
        val queue = InMemoryAnalyticsEventQueue(maxCount = 10, maxBytes = Long.MAX_VALUE)
        val tier2Event = AnalyticsEventFactory.tier2(
            event("A").provenance,
            AnalyticsTier2Payload.Transcript("hello", null),
        )
        queue.enqueue(event("A"))
        queue.enqueue(tier2Event)

        queue.removeAll { it.tier == AnalyticsTier.TIER_2 }

        assertEquals(1, queue.size())
        assertFalse(queue.peekAll().any { it.tier == AnalyticsTier.TIER_2 })
    }
}
