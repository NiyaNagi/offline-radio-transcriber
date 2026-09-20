package org.ort.net.analytics.real

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.core.analytics.AnalyticsPurgeResult
import org.ort.core.analytics.AnalyticsUploadFailureReason
import org.ort.core.analytics.AnalyticsUploadRequest
import org.ort.core.analytics.AnalyticsUploadResult
import org.ort.net.real.LoopbackHttpFixture
import org.ort.net.real.LoopbackRequest
import org.ort.net.real.LoopbackResponse

/**
 * D48: a self-hosted HTTPS ingest endpoint configured at build time by `ORT_ANALYTICS_ENDPOINT`;
 * unset, events queue locally and nothing is ever sent. This is the one `:net` class that ever
 * attempts that upload — `LoopbackHttpFixture` (already proven against `RealHttpRangeClient` and
 * `RealFieldReportUploadClient`) stands in for the destination so no test here makes a real
 * network call (`net/README.md`'s own reasoning for plain `java.net.HttpURLConnection`).
 */
class RealAnalyticsUploadClientTest {

    private fun sampleRequest(captureActive: Boolean = false) = AnalyticsUploadRequest(
        ndjson = "{\"tier\":\"TIER_1\"}",
        eventCount = 1,
        captureActive = captureActive,
    )

    @Test
    fun `AC_D48_no endpoint configured means not configured, and neither call ever opens a socket`() = runTest {
        val client = RealAnalyticsUploadClient(endpoint = null)

        assertFalse(client.isConfigured())
        assertEquals(
            AnalyticsUploadResult.Failure(
                AnalyticsUploadFailureReason.NOT_CONFIGURED,
                "no analytics endpoint is configured in this build",
            ),
            client.upload(sampleRequest()),
        )
        assertEquals(
            AnalyticsPurgeResult.Failure(
                AnalyticsUploadFailureReason.NOT_CONFIGURED,
                "no analytics endpoint is configured in this build",
            ),
            client.purge("install-1"),
        )
    }

    @Test
    fun `a blank endpoint is treated the same as no endpoint`() = runTest {
        val client = RealAnalyticsUploadClient(endpoint = "   ")
        assertFalse(client.isConfigured())
    }

    @Test
    fun `AC_177_upload refuses whenever capture is active, before ever reaching the destination`() = runTest {
        val client = RealAnalyticsUploadClient(endpoint = "http://127.0.0.1:1")

        val result = client.upload(sampleRequest(captureActive = true))

        assertEquals(
            AnalyticsUploadResult.Failure(
                AnalyticsUploadFailureReason.CAPTURE_ACTIVE,
                "upload refused: capture is active (FR-ANL-7)",
            ),
            result,
        )
    }

    @Test
    fun `a successful POST to the ingest endpoint returns Success`() = runTest {
        var receivedRequest: LoopbackRequest? = null
        LoopbackHttpFixture { request ->
            receivedRequest = request
            LoopbackResponse(200, "OK", ByteArray(0))
        }.use { fixture ->
            val client = RealAnalyticsUploadClient(endpoint = "http://127.0.0.1:${fixture.port}")

            val result = client.upload(sampleRequest())

            assertEquals(AnalyticsUploadResult.Success, result)
            assertEquals("POST", receivedRequest?.method)
            assertEquals("/ingest", receivedRequest?.path)
        }
    }

    @Test
    fun `a non-2xx response from the ingest endpoint is UPLOAD_FAILED`() = runTest {
        LoopbackHttpFixture { LoopbackResponse(500, "Internal Server Error", ByteArray(0)) }.use { fixture ->
            val client = RealAnalyticsUploadClient(endpoint = "http://127.0.0.1:${fixture.port}")

            val result = client.upload(sampleRequest()) as AnalyticsUploadResult.Failure

            assertEquals(AnalyticsUploadFailureReason.UPLOAD_FAILED, result.reason)
        }
    }

    @Test
    fun `an unreachable endpoint is PARTIAL_WRITE for upload`() = runTest {
        val client =
            RealAnalyticsUploadClient(endpoint = "http://127.0.0.1:1", connectTimeoutMs = 200, readTimeoutMs = 200)

        val result = client.upload(sampleRequest()) as AnalyticsUploadResult.Failure

        assertEquals(AnalyticsUploadFailureReason.PARTIAL_WRITE, result.reason)
    }

    @Test
    fun `AC_181_purge issues a DELETE naming the install id and returns Success on 2xx`() = runTest {
        var receivedRequest: LoopbackRequest? = null
        LoopbackHttpFixture { request ->
            receivedRequest = request
            LoopbackResponse(204, "No Content", ByteArray(0))
        }.use { fixture ->
            val client = RealAnalyticsUploadClient(endpoint = "http://127.0.0.1:${fixture.port}")

            val result = client.purge("install-123")

            assertEquals(AnalyticsPurgeResult.Success, result)
            assertEquals("DELETE", receivedRequest?.method)
            assertTrue(receivedRequest?.path?.contains("install-123") == true)
        }
    }

    @Test
    fun `AC_181_an unreachable endpoint is DESTINATION_UNREACHABLE for purge`() = runTest {
        val client =
            RealAnalyticsUploadClient(endpoint = "http://127.0.0.1:1", connectTimeoutMs = 200, readTimeoutMs = 200)

        val result = client.purge("install-123") as AnalyticsPurgeResult.Failure

        assertEquals(AnalyticsUploadFailureReason.DESTINATION_UNREACHABLE, result.reason)
    }
}
