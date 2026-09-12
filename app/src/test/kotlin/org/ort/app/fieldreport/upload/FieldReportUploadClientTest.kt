package org.ort.app.fieldreport.upload

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.ort.app.fieldreport.bundle.FieldReportGatedCategory

/**
 * Constitution II: [FakeFieldReportUploadClient] can report no destination at all (the honest
 * "not configured" state this round's wiring uses until WPR3 lands a real `:net` client), a named
 * destination of either visibility, and records exactly what it was called with — the seam
 * `SettingsContent.kt`'s own field-report wiring and a future WPR3 test both build on.
 */
class FieldReportUploadClientTest {

    @Test
    fun `defaults to no destination configured, matching the honest not-available state`() = runTest {
        val client = FakeFieldReportUploadClient()
        assertNull(client.destination())
    }

    @Test
    fun `reports whichever destination it is configured with`() = runTest {
        val client = FakeFieldReportUploadClient(
            destinationToReturn = FieldReportDestination(label = "org/repo", isPublic = true),
        )
        assertEquals(FieldReportDestination("org/repo", true), client.destination())
    }

    @Test
    fun `upload records the exact request and returns the configured result`() = runTest {
        val client = FakeFieldReportUploadClient(
            resultToReturn = FieldReportUploadResult.Success("https://example.invalid/issues/9"),
        )
        val request = FieldReportUploadRequest(
            bundle = byteArrayOf(1, 2, 3),
            fileName = "field-report-2026-09-12.zip",
            categoriesIncluded = setOf(FieldReportGatedCategory.SCREEN_FRAMES),
        )

        val result = client.upload(request)

        assertEquals(1, client.uploadCallCount)
        assertEquals(request, client.lastRequest)
        assertEquals(FieldReportUploadResult.Success("https://example.invalid/issues/9"), result)
    }
}
