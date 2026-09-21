package org.ort.net.analytics.real

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.core.analytics.AnalyticsUploadFailureReason
import org.ort.core.analytics.AnalyticsUploadRequest
import org.ort.core.analytics.AnalyticsUploadResult
import org.ort.net.real.LoopbackRequest
import org.ort.net.real.LoopbackResponse
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

/**
 * R-1101: `RealAnalyticsUploadClient` was implemented and unit-tested only against
 * [org.ort.net.real.LoopbackHttpFixture] (plain HTTP), so nothing ever exercised its TLS path —
 * `URI(url).toURL().openConnection()` returning a real `HttpsURLConnection` and negotiating a
 * genuine handshake — before the first real upload would have. [TlsLoopbackHttpFixture] is a real
 * `SSLServerSocket`, not a fake: every case below performs an actual TLS handshake over
 * `127.0.0.1`.
 *
 * **Why this is a JVM-only, hermetic fixture rather than `tools/analytics`' own Python reference
 * server.** The two share the identical self-signed certificate
 * ([net/src/test/resources/analytics-tls]/`server.p12`, exported to
 * `tools/analytics/tests/fixtures/tls/cert.pem`+`key.pem`) precisely so this suite is a faithful
 * stand-in for it — but shelling out to a Python subprocess from `:net:test` would make this
 * module's gate depend on a Python interpreter and `tools/analytics`' own dependencies being
 * present wherever `:net:test` runs (this repo's hosted CI/Release runners are not known to carry
 * that), which is exactly the kind of environment-fragile test constitution II warns against. The
 * reference server itself was proven separately, locally, against this exact certificate — see
 * this unit's own report for the transcript (a real `POST /ingest` succeeding, a client with no
 * trust override being refused, and a killed-mid-request server producing a partial-write
 * failure) — the two together are R-1101's full proof: automated coverage that survives every
 * future push, plus one real end-to-end run against the actual reference server before any
 * endpoint is ever deployed.
 *
 * [restoreDefaultSslSocketFactory] undoes the one bit of process-global JVM state the "the client
 * trusts this certificate" case below has to touch ([HttpsURLConnection.setDefaultSSLSocketFactory]
 * — `RealAnalyticsUploadClient` builds its connection from `URI(...).toURL().openConnection()`
 * with no constructor seam for injecting an `SSLSocketFactory`, so there is no other way to make a
 * *specific* certificate trusted for *one* test without either changing that class's public API or
 * touching the JVM-wide default) — every other test in this module keeps seeing the ordinary
 * platform default, the same guarantee [AfterEach] gives every other stateful fixture in this
 * codebase.
 */
class RealAnalyticsUploadClientTlsTest {

    private val originalSslSocketFactory: SSLSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory()

    @AfterEach
    fun restoreDefaultSslSocketFactory() {
        HttpsURLConnection.setDefaultSSLSocketFactory(originalSslSocketFactory)
    }

    private fun sampleRequest() = AnalyticsUploadRequest(
        ndjson = "{\"tier\":\"TIER_1\"}",
        eventCount = 1,
        captureActive = false,
    )

    @Test
    fun `R_1101 a trusted certificate completes a real TLS handshake and the upload succeeds`() = runTest {
        HttpsURLConnection.setDefaultSSLSocketFactory(TestKeyMaterial.trustingSslContext().socketFactory)
        var receivedRequest: LoopbackRequest? = null
        TlsLoopbackHttpFixture.respond { request ->
            receivedRequest = request
            LoopbackResponse(200, "OK", ByteArray(0))
        }.use { fixture ->
            val client = RealAnalyticsUploadClient(endpoint = "https://127.0.0.1:${fixture.port}")

            val result = client.upload(sampleRequest())

            assertEquals(AnalyticsUploadResult.Success, result)
            assertEquals("POST", receivedRequest?.method)
            assertEquals("/ingest", receivedRequest?.path)
        }
    }

    @Test
    fun `R_1101 a refused certificate — default trust manager, no override — fails safely, never crashes`() = runTest {
        // Deliberately no `setDefaultSSLSocketFactory` override here: this is the ordinary JVM
        // default trust manager, which does not (and must not) know about this fixture's
        // self-signed certificate — exactly the shape a real, unconfigured phone would see if
        // `ORT_ANALYTICS_ENDPOINT` were ever pointed at a destination presenting the wrong, or a
        // self-signed, certificate.
        TlsLoopbackHttpFixture.respond { LoopbackResponse(200, "OK", ByteArray(0)) }.use { fixture ->
            val client = RealAnalyticsUploadClient(endpoint = "https://127.0.0.1:${fixture.port}")

            val result = client.upload(sampleRequest()) as AnalyticsUploadResult.Failure

            assertEquals(AnalyticsUploadFailureReason.PARTIAL_WRITE, result.reason)
        }
    }

    @Test
    fun `R_1101 a connection that dies right after a genuine handshake is a mid-upload failure`() = runTest {
        HttpsURLConnection.setDefaultSSLSocketFactory(TestKeyMaterial.trustingSslContext().socketFactory)
        TlsLoopbackHttpFixture.abortAfterHandshake().use { fixture ->
            val client = RealAnalyticsUploadClient(endpoint = "https://127.0.0.1:${fixture.port}")

            val result = client.upload(sampleRequest()) as AnalyticsUploadResult.Failure

            assertEquals(AnalyticsUploadFailureReason.PARTIAL_WRITE, result.reason)
        }
    }

    @Test
    fun `R_1101 purge also completes a real TLS handshake when the certificate is trusted`() = runTest {
        HttpsURLConnection.setDefaultSSLSocketFactory(TestKeyMaterial.trustingSslContext().socketFactory)
        var receivedRequest: LoopbackRequest? = null
        TlsLoopbackHttpFixture.respond { request ->
            receivedRequest = request
            LoopbackResponse(204, "No Content", ByteArray(0))
        }.use { fixture ->
            val client = RealAnalyticsUploadClient(endpoint = "https://127.0.0.1:${fixture.port}")

            val result = client.purge("install-tls-1")

            assertTrue(result is org.ort.core.analytics.AnalyticsPurgeResult.Success)
            assertEquals("DELETE", receivedRequest?.method)
            assertTrue(receivedRequest?.path?.contains("install-tls-1") == true)
        }
    }
}
