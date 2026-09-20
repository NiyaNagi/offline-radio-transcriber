package org.ort.app.analytics

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.core.analytics.AnalyticsUploadFailureReason
import org.ort.core.analytics.AnalyticsUploadResult
import org.ort.core.analytics.FakeAnalyticsUploadClient
import org.ort.telemetry.ANALYTICS_SCHEMA_VERSION
import org.ort.telemetry.AnalyticsEventFactory
import org.ort.telemetry.AnalyticsProvenance
import org.ort.telemetry.AnalyticsTier1Payload
import org.ort.telemetry.InMemoryAnalyticsEventQueue

/**
 * AC-177/FR-ANL-7: the queue drains to `:net` only when the caller-supplied `isCapturing` reads
 * false at the moment [AnalyticsUploadRunner.run] is invoked — mirrors the discipline
 * `org.ort.pipeline.digest.ProseDigestWorkRunner` already applies to its own gate.
 */
class AnalyticsUploadRunnerTest {

    private fun provenance() = AnalyticsProvenance(
        installId = "install-1", sessionId = null, overId = null, appVersion = "0.1.1",
        buildHash = "abc123", modelIds = emptyList(), modelShas = emptyList(),
        executionProvider = "cpu", deviceModel = "test", soc = "test", detectedTier = "T2",
        captureMode = null, rigModule = null, band = null, schemaVersion = ANALYTICS_SCHEMA_VERSION,
    )

    private fun event(action: String) =
        AnalyticsEventFactory.tier1(provenance(), AnalyticsTier1Payload.Usage("SETTINGS", action))

    @Test
    fun `AC_177_never uploads while capture is active`() = runTest {
        val queue = InMemoryAnalyticsEventQueue(maxCount = 10, maxBytes = Long.MAX_VALUE)
        queue.enqueue(event("A"))
        val uploader = FakeAnalyticsUploadClient(configured = true)
        val runner = AnalyticsUploadRunner(queue, uploader, isCapturing = { true })

        val outcome = runner.run()

        assertEquals(AnalyticsUploadRunOutcome.NotEligible, outcome)
        assertEquals(0, uploader.uploadCallCount)
        assertEquals(1, queue.size(), "the event must stay queued, not be lost")
    }

    @Test
    fun `an empty queue does nothing`() = runTest {
        val queue = InMemoryAnalyticsEventQueue(maxCount = 10, maxBytes = Long.MAX_VALUE)
        val uploader = FakeAnalyticsUploadClient(configured = true)
        val runner = AnalyticsUploadRunner(queue, uploader, isCapturing = { false })

        val outcome = runner.run()

        assertEquals(AnalyticsUploadRunOutcome.NothingQueued, outcome)
        assertEquals(0, uploader.uploadCallCount)
    }

    @Test
    fun `D48_an unconfigured uploader never drains the queue`() = runTest {
        val queue = InMemoryAnalyticsEventQueue(maxCount = 10, maxBytes = Long.MAX_VALUE)
        queue.enqueue(event("A"))
        val uploader = FakeAnalyticsUploadClient(configured = false)
        val runner = AnalyticsUploadRunner(queue, uploader, isCapturing = { false })

        val outcome = runner.run()

        assertEquals(AnalyticsUploadRunOutcome.NotConfigured, outcome)
        assertEquals(0, uploader.uploadCallCount)
        assertEquals(1, queue.size(), "nothing configured means nothing is ever sent (D48)")
    }

    @Test
    fun `a successful upload drains the queue and reports the count`() = runTest {
        val queue = InMemoryAnalyticsEventQueue(maxCount = 10, maxBytes = Long.MAX_VALUE)
        queue.enqueue(event("A"))
        queue.enqueue(event("B"))
        val uploader =
            FakeAnalyticsUploadClient(configured = true, uploadResultToReturn = AnalyticsUploadResult.Success)
        val runner = AnalyticsUploadRunner(queue, uploader, isCapturing = { false })

        val outcome = runner.run()

        assertEquals(AnalyticsUploadRunOutcome.Uploaded(2), outcome)
        assertEquals(0, queue.size())
        assertEquals(2, uploader.lastRequest?.eventCount)
        assertTrue(uploader.lastRequest?.ndjson?.contains("\"action\":\"A\"") == true)
    }

    @Test
    fun `a failed upload re-queues the drained events rather than losing them`() = runTest {
        val queue = InMemoryAnalyticsEventQueue(maxCount = 10, maxBytes = Long.MAX_VALUE)
        queue.enqueue(event("A"))
        val uploader = FakeAnalyticsUploadClient(
            configured = true,
            uploadResultToReturn = AnalyticsUploadResult.Failure(AnalyticsUploadFailureReason.UPLOAD_FAILED, "500"),
        )
        val runner = AnalyticsUploadRunner(queue, uploader, isCapturing = { false })

        val outcome = runner.run()

        assertEquals(AnalyticsUploadRunOutcome.Failed(AnalyticsUploadFailureReason.UPLOAD_FAILED), outcome)
        assertEquals(1, queue.size(), "a failed upload must not lose the event")
    }
}
