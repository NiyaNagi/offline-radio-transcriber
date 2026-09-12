package org.ort.core.fieldreport

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Constitution II: [FakeFieldReportUploadClient] can report no destination at all (the honest
 * "not configured" state), a destination of any [FieldReportDestinationVisibility] (including
 * [FieldReportDestinationVisibility.UNKNOWN]), any [FieldReportUploadResult] (including a scripted
 * failure naming any [FieldReportUploadFailureReason]), can hang indefinitely, and records exactly
 * what it was called with — the seam every real caller and every future `:net` test builds on.
 */
class FieldReportUploadClientTest {

    private fun sampleRequest(captureActive: Boolean = false) = FieldReportUploadRequest(
        bundle = byteArrayOf(1, 2, 3),
        fileName = "field-report-2026-09-12.zip",
        categoriesIncluded = setOf(FieldReportUploadCategory.SCREEN_FRAMES),
        captureActive = captureActive,
        deviceLabel = "Pixel 8",
        buildLabel = "0.1.1",
        commitLabel = "abcdef1",
    )

    @Test
    fun `defaults to no destination configured, matching the honest not-available state`() = runTest {
        val client = FakeFieldReportUploadClient()
        assertNull(client.destination())
    }

    @Test
    fun `reports whichever destination it is configured with, including unknown visibility`() = runTest {
        val client = FakeFieldReportUploadClient(
            destinationToReturn = FieldReportDestination("org/repo", FieldReportDestinationVisibility.UNKNOWN),
        )
        assertEquals(
            FieldReportDestination("org/repo", FieldReportDestinationVisibility.UNKNOWN),
            client.destination(),
        )
    }

    @Test
    fun `upload records the exact request and returns the configured result`() = runTest {
        val client = FakeFieldReportUploadClient(
            resultToReturn = FieldReportUploadResult.Success("https://example.invalid/issues/9"),
        )
        val request = sampleRequest()

        val result = client.upload(request)

        assertEquals(1, client.uploadCallCount)
        assertEquals(request, client.lastRequest)
        assertEquals(FieldReportUploadResult.Success("https://example.invalid/issues/9"), result)
    }

    @Test
    fun `AC_147 upload can be scripted to report a partial write without exposing prose to assert on`() = runTest {
        val client = FakeFieldReportUploadClient(
            resultToReturn = FieldReportUploadResult.Failure(
                FieldReportUploadFailureReason.PARTIAL_WRITE,
                "connection dropped mid-upload",
            ),
        )
        val result = client.upload(sampleRequest()) as FieldReportUploadResult.Failure
        assertEquals(FieldReportUploadFailureReason.PARTIAL_WRITE, result.reason)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `FR_OBS_11 upload can be scripted to hang, and only completes once released`() = runTest {
        val client = FakeFieldReportUploadClient(hangOnUpload = true)
        var observed: FieldReportUploadResult? = null
        val job = launch { observed = client.upload(sampleRequest()) }

        advanceUntilIdle()
        assertTrue(job.isActive, "a hung upload must not complete on its own")
        assertNull(observed)

        client.releaseHang()
        advanceUntilIdle()

        assertTrue(job.isCompleted, "releaseHang() must let a hung upload complete")
        assertEquals(client.resultToReturn, observed)
    }
}
