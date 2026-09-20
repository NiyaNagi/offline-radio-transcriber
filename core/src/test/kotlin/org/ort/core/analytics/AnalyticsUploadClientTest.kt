package org.ort.core.analytics

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Constitution II: [FakeAnalyticsUploadClient] mirrors
 * [org.ort.core.fieldreport.FakeFieldReportUploadClient] exactly — the honest "not configured"
 * default (D48: no `ORT_ANALYTICS_ENDPOINT` means nothing is ever sent), a scriptable result
 * (including any [AnalyticsUploadFailureReason]), a scriptable hang, and exact call recording.
 */
class AnalyticsUploadClientTest {

    private fun sampleRequest(captureActive: Boolean = false) = AnalyticsUploadRequest(
        ndjson = "{\"tier\":\"TIER_1\"}",
        eventCount = 1,
        captureActive = captureActive,
    )

    @Test
    fun `defaults to not configured, matching D48's default state (no endpoint set)`() = runTest {
        val client = FakeAnalyticsUploadClient()
        assertFalse(client.isConfigured())
    }

    @Test
    fun `AC_177_upload refuses whenever the caller states capture is active, never attempting the network`() = runTest {
        val client = FakeAnalyticsUploadClient(configured = true)

        val result = client.upload(sampleRequest(captureActive = true))

        assertEquals(
            AnalyticsUploadResult.Failure(AnalyticsUploadFailureReason.CAPTURE_ACTIVE, "capture active"),
            result,
        )
    }

    @Test
    fun `upload records the exact request and returns the configured result`() = runTest {
        val client = FakeAnalyticsUploadClient(configured = true, uploadResultToReturn = AnalyticsUploadResult.Success)
        val request = sampleRequest()

        val result = client.upload(request)

        assertEquals(1, client.uploadCallCount)
        assertEquals(request, client.lastRequest)
        assertEquals(AnalyticsUploadResult.Success, result)
    }

    @Test
    fun `AC_181_purge records the install id and returns the configured result`() = runTest {
        val client = FakeAnalyticsUploadClient(purgeResultToReturn = AnalyticsPurgeResult.Success)

        val result = client.purge("install-123")

        assertEquals(1, client.purgeCallCount)
        assertEquals("install-123", client.lastPurgedInstallId)
        assertEquals(AnalyticsPurgeResult.Success, result)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `upload can be scripted to hang, and only completes once released`() = runTest {
        val client = FakeAnalyticsUploadClient(configured = true, hangOnUpload = true)
        var observed: AnalyticsUploadResult? = null
        val job = launch { observed = client.upload(sampleRequest()) }

        advanceUntilIdle()
        assertTrue(job.isActive, "a hung upload must not complete on its own")

        client.releaseHang()
        advanceUntilIdle()

        assertTrue(job.isCompleted)
        assertEquals(client.uploadResultToReturn, observed)
    }
}
