package org.ort.telemetry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.ort.testing.Requirement
import java.nio.file.Files

/**
 * The real on-device queue: [FileBackedAnalyticsEventQueue] wraps [InMemoryAnalyticsEventQueue]'s
 * bounding/drop-oldest logic (already proven by `InMemoryAnalyticsEventQueueTest`) with an
 * NDJSON-on-disk backing store, so a queued-but-not-yet-sent event survives the process dying —
 * the "on-device queue" FR-ANL-7/FR-ANL-13 describe, not merely an in-memory buffer that an OS
 * kill silently empties.
 */
class FileBackedAnalyticsEventQueueTest {

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

    private fun event(action: String) =
        AnalyticsEventFactory.tier1(provenance(), AnalyticsTier1Payload.Usage("SETTINGS", action))

    @Test
    @Requirement("FR-ANL-13")
    fun `a queued event survives a fresh instance over the same directory (process-death durability)`() {
        val dir = Files.createTempDirectory("analytics-queue").toFile()
        val first = FileBackedAnalyticsEventQueue(dir, maxCount = 10, maxBytes = Long.MAX_VALUE)
        first.enqueue(event("A"))

        val reopened = FileBackedAnalyticsEventQueue(dir, maxCount = 10, maxBytes = Long.MAX_VALUE)

        assertEquals(1, reopened.size())
        assertEquals("A", (reopened.peekAll().single().payload as AnalyticsTier1Payload.Usage).action)
    }

    @Test
    fun `drain removes events from disk, not just memory`() {
        val dir = Files.createTempDirectory("analytics-queue").toFile()
        val queue = FileBackedAnalyticsEventQueue(dir, maxCount = 10, maxBytes = Long.MAX_VALUE)
        queue.enqueue(event("A"))
        queue.drain()

        val reopened = FileBackedAnalyticsEventQueue(dir, maxCount = 10, maxBytes = Long.MAX_VALUE)

        assertEquals(0, reopened.size())
    }

    @Test
    @Requirement("AC-183", "FR-ANL-13")
    fun `AC_183_bounding and drop-oldest hold across the file-backed queue too`() {
        val dir = Files.createTempDirectory("analytics-queue").toFile()
        val queue = FileBackedAnalyticsEventQueue(dir, maxCount = 2, maxBytes = Long.MAX_VALUE)

        queue.enqueue(event("A"))
        queue.enqueue(event("B"))
        val outcome = queue.enqueue(event("C"))

        assertEquals(EnqueueOutcome.DROPPED_OLDEST, outcome)
        assertEquals(listOf("B", "C"), queue.peekAll().map { (it.payload as AnalyticsTier1Payload.Usage).action })
    }

    @Test
    @Requirement("AC-179", "FR-ANL-9")
    fun `AC_179_removeAll rewrites the file so a purge is durable too`() {
        val dir = Files.createTempDirectory("analytics-queue").toFile()
        val queue = FileBackedAnalyticsEventQueue(dir, maxCount = 10, maxBytes = Long.MAX_VALUE)
        queue.enqueue(event("A"))
        val tier2Event = AnalyticsEventFactory.tier2(provenance(), AnalyticsTier2Payload.Transcript("hi", null))
        queue.enqueue(tier2Event)

        queue.removeAll { it.tier == AnalyticsTier.TIER_2 }

        val reopened = FileBackedAnalyticsEventQueue(dir, maxCount = 10, maxBytes = Long.MAX_VALUE)
        assertEquals(1, reopened.size())
        assertEquals(AnalyticsTier.TIER_1, reopened.peekAll().single().tier)
    }
}
