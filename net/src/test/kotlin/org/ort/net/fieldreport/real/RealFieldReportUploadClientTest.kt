package org.ort.net.fieldreport.real

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.core.fieldreport.FieldReportDestination
import org.ort.core.fieldreport.FieldReportDestinationVisibility
import org.ort.core.fieldreport.FieldReportUploadCategory
import org.ort.core.fieldreport.FieldReportUploadFailureReason
import org.ort.core.fieldreport.FieldReportUploadRequest
import org.ort.core.fieldreport.FieldReportUploadResult
import org.ort.net.real.LoopbackHttpFixture
import org.ort.net.real.LoopbackRequest
import org.ort.net.real.LoopbackResponse
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket

/**
 * Exercises [RealFieldReportUploadClient] against a loopback fixture — never a real network call
 * (constitution II) — reusing [LoopbackHttpFixture], `:net`'s own precedent from
 * [org.ort.net.real.RealHttpRangeClientTest]. Every scripted failure is asserted by
 * [FieldReportUploadFailureReason], never by prose (constitution II).
 */
class RealFieldReportUploadClientTest {

    private fun sampleRequest(captureActive: Boolean = false, fileName: String = "field-report-2026-09-12.zip") =
        FieldReportUploadRequest(
            bundle = "a small test bundle".toByteArray(),
            fileName = fileName,
            categoriesIncluded = setOf(FieldReportUploadCategory.SCREEN_FRAMES),
            captureActive = captureActive,
            deviceLabel = "Pixel 8",
            buildLabel = "0.1.1",
            commitLabel = "abcdef1",
        )

    /** A tiny router over [LoopbackHttpFixture]'s single `respond` callback, since one upload
     * touches several distinct GitHub endpoints in sequence over separate connections. */
    private class RoutedFixture {
        val requests = mutableListOf<LoopbackRequest>()
        private val routes = mutableListOf<Pair<(LoopbackRequest) -> Boolean, () -> LoopbackResponse>>()

        fun on(match: (LoopbackRequest) -> Boolean, respond: () -> LoopbackResponse) {
            routes += match to respond
        }

        fun respond(request: LoopbackRequest): LoopbackResponse {
            requests += request
            val route = routes.firstOrNull { it.first(request) }
            return route?.second?.invoke() ?: LoopbackResponse(404, "Not Found", "no route scripted".toByteArray())
        }
    }

    private fun path(request: LoopbackRequest): String = request.path.substringBefore('?')

    // ---- destination() -------------------------------------------------------------------

    @Test
    fun `FR_OBS_10 destination reports PUBLIC from a real-shaped repo response`() = runTest {
        val fixture = RoutedFixture()
        fixture.on({ path(it) == "/repos/o/r" }) {
            LoopbackResponse(200, "OK", """{"private": false}""".toByteArray())
        }
        LoopbackHttpFixture(fixture::respond).use { server ->
            val client = clientAt(server.port, token = "t")
            assertEquals(FieldReportDestination("o/r", FieldReportDestinationVisibility.PUBLIC), client.destination())
        }
    }

    @Test
    fun `FR_OBS_10 destination reports PRIVATE from a real-shaped repo response`() = runTest {
        val fixture = RoutedFixture()
        fixture.on({ path(it) == "/repos/o/r" }) {
            LoopbackResponse(200, "OK", """{"private": true}""".toByteArray())
        }
        LoopbackHttpFixture(fixture::respond).use { server ->
            val client = clientAt(server.port, token = "t")
            assertEquals(FieldReportDestination("o/r", FieldReportDestinationVisibility.PRIVATE), client.destination())
        }
    }

    @Test
    fun `FR_OBS_10 an unreadable response reports UNKNOWN, never a guessed PRIVATE`() = runTest {
        val fixture = RoutedFixture()
        fixture.on({ path(it) == "/repos/o/r" }) { LoopbackResponse(500, "Internal Server Error", ByteArray(0)) }
        LoopbackHttpFixture(fixture::respond).use { server ->
            val client = clientAt(server.port, token = "t")
            assertEquals(FieldReportDestinationVisibility.UNKNOWN, client.destination()?.visibility)
        }
    }

    @Test
    fun `FR_OBS_10 an unreachable destination reports UNKNOWN, never a guessed PRIVATE`() = runTest {
        // A closed port: opened then immediately closed, so nothing answers.
        val closedPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val client = clientAt(closedPort, token = "t")
        assertEquals(FieldReportDestinationVisibility.UNKNOWN, client.destination()?.visibility)
    }

    @Test
    fun `FR_OBS_12 destination is null when no token is configured, and no request is ever attempted`() = runTest {
        // A syntactically-invalid base URL: if this were ever touched, the call would throw
        // instead of returning null — proving the null-token check short-circuits first.
        val client = RealFieldReportUploadClient(repository = "o/r", token = null, apiBaseUrl = "not a url")
        assertNull(client.destination())
    }

    @Test
    fun `FR_OBS_12 destination is null when the token is blank, matching the null-token state`() = runTest {
        val client = RealFieldReportUploadClient(repository = "o/r", token = "   ", apiBaseUrl = "not a url")
        assertNull(client.destination())
    }

    // ---- upload(): refusals that must never touch the network -----------------------------

    @Test
    fun `FR_OBS_11 upload refuses while capture is active, without ever attempting a request`() = runTest {
        val client = RealFieldReportUploadClient(repository = "o/r", token = "t", apiBaseUrl = "not a url")
        val result = client.upload(sampleRequest(captureActive = true)) as FieldReportUploadResult.Failure
        assertEquals(FieldReportUploadFailureReason.CAPTURE_ACTIVE, result.reason)
    }

    @Test
    fun `FR_OBS_12 upload reports NOT_CONFIGURED when no token is present, without attempting a request`() = runTest {
        val client = RealFieldReportUploadClient(repository = "o/r", token = null, apiBaseUrl = "not a url")
        val result = client.upload(sampleRequest()) as FieldReportUploadResult.Failure
        assertEquals(FieldReportUploadFailureReason.NOT_CONFIGURED, result.reason)
    }

    // ---- upload(): the real GitHub flow ----------------------------------------------------

    @Test
    fun `AC_145 upload creates a release, uploads the asset and opens an issue, end to end`() = runTest {
        val fixture = RoutedFixture()
        fixture.on({ it.method == "GET" && path(it) == "/repos/o/r/releases/tags/field-reports" }) {
            LoopbackResponse(404, "Not Found", ByteArray(0))
        }
        fixture.on({ it.method == "POST" && path(it) == "/repos/o/r/releases" }) {
            LoopbackResponse(201, "Created", """{"id": 555}""".toByteArray())
        }
        fixture.on({ it.method == "POST" && path(it) == "/repos/o/r/releases/555/assets" }) {
            LoopbackResponse(
                201,
                "Created",
                """{"browser_download_url": "https://example.invalid/assets/1"}""".toByteArray(),
            )
        }
        fixture.on({ it.method == "POST" && path(it) == "/repos/o/r/issues" }) {
            LoopbackResponse(201, "Created", """{"html_url": "https://example.invalid/issues/42"}""".toByteArray())
        }
        LoopbackHttpFixture(fixture::respond).use { server ->
            val client = clientAt(server.port, token = "sekrit-token-value-123")

            val result = client.upload(sampleRequest())

            assertEquals(FieldReportUploadResult.Success("https://example.invalid/issues/42"), result)
            // Sent, but only where it belongs (the Authorization header) — never in a path.
            assertTrue(fixture.requests.all { it.headers["authorization"] == "Bearer sekrit-token-value-123" })
            assertTrue(fixture.requests.none { it.path.contains("sekrit-token-value-123") })
        }
    }

    @Test
    fun `an existing release is reused rather than recreated`() = runTest {
        val fixture = RoutedFixture()
        var releaseCreatePosts = 0
        fixture.on({ it.method == "GET" && path(it) == "/repos/o/r/releases/tags/field-reports" }) {
            LoopbackResponse(200, "OK", """{"id": 777}""".toByteArray())
        }
        fixture.on({ it.method == "POST" && path(it) == "/repos/o/r/releases" }) {
            releaseCreatePosts++
            LoopbackResponse(201, "Created", """{"id": 999}""".toByteArray())
        }
        fixture.on({ it.method == "POST" && path(it) == "/repos/o/r/releases/777/assets" }) {
            LoopbackResponse(
                201,
                "Created",
                """{"browser_download_url": "https://example.invalid/assets/2"}""".toByteArray(),
            )
        }
        fixture.on({ it.method == "POST" && path(it) == "/repos/o/r/issues" }) {
            LoopbackResponse(201, "Created", """{"html_url": "https://example.invalid/issues/43"}""".toByteArray())
        }
        LoopbackHttpFixture(fixture::respond).use { server ->
            val client = clientAt(server.port, token = "t")
            val result = client.upload(sampleRequest())
            assertEquals(FieldReportUploadResult.Success("https://example.invalid/issues/43"), result)
            assertEquals(0, releaseCreatePosts, "an already-existing release must not be recreated")
        }
    }

    @Test
    fun `DESTINATION_UNREACHABLE when the release cannot be verified or created`() = runTest {
        val fixture = RoutedFixture()
        fixture.on({ path(it) == "/repos/o/r/releases/tags/field-reports" }) {
            LoopbackResponse(404, "Not Found", ByteArray(0))
        }
        fixture.on({ it.method == "POST" && path(it) == "/repos/o/r/releases" }) {
            LoopbackResponse(500, "Internal Server Error", ByteArray(0))
        }
        LoopbackHttpFixture(fixture::respond).use { server ->
            val client = clientAt(server.port, token = "t")
            val result = client.upload(sampleRequest()) as FieldReportUploadResult.Failure
            assertEquals(FieldReportUploadFailureReason.DESTINATION_UNREACHABLE, result.reason)
        }
    }

    @Test
    fun `UPLOAD_FAILED when the destination rejects the asset outright`() = runTest {
        val fixture = RoutedFixture()
        fixture.on({ path(it) == "/repos/o/r/releases/tags/field-reports" }) {
            LoopbackResponse(200, "OK", """{"id": 1}""".toByteArray())
        }
        fixture.on({ it.method == "POST" && path(it) == "/repos/o/r/releases/1/assets" }) {
            LoopbackResponse(422, "Unprocessable Entity", "asset name conflict".toByteArray())
        }
        LoopbackHttpFixture(fixture::respond).use { server ->
            val client = clientAt(server.port, token = "t")
            val result = client.upload(sampleRequest()) as FieldReportUploadResult.Failure
            assertEquals(FieldReportUploadFailureReason.UPLOAD_FAILED, result.reason)
        }
    }

    @Test
    fun `ISSUE_CREATE_FAILED when the bundle uploaded but the issue could not be created`() = runTest {
        val fixture = RoutedFixture()
        fixture.on({ path(it) == "/repos/o/r/releases/tags/field-reports" }) {
            LoopbackResponse(200, "OK", """{"id": 1}""".toByteArray())
        }
        fixture.on({ it.method == "POST" && path(it) == "/repos/o/r/releases/1/assets" }) {
            LoopbackResponse(201, "Created", """{"browser_download_url": "https://example.invalid/a"}""".toByteArray())
        }
        fixture.on({ it.method == "POST" && path(it) == "/repos/o/r/issues" }) {
            LoopbackResponse(500, "Internal Server Error", ByteArray(0))
        }
        LoopbackHttpFixture(fixture::respond).use { server ->
            val client = clientAt(server.port, token = "t")
            val result = client.upload(sampleRequest()) as FieldReportUploadResult.Failure
            assertEquals(FieldReportUploadFailureReason.ISSUE_CREATE_FAILED, result.reason)
        }
    }

    @Test
    fun `PARTIAL_WRITE when the connection drops while the asset body is being sent`() = runTest {
        val apiFixture = RoutedFixture()
        apiFixture.on({ path(it) == "/repos/o/r/releases/tags/field-reports" }) {
            LoopbackResponse(200, "OK", """{"id": 1}""".toByteArray())
        }
        LoopbackHttpFixture(apiFixture::respond).use { apiServer ->
            ImmediateCloseServer().use { uploadServer ->
                val client = RealFieldReportUploadClient(
                    repository = "o/r",
                    token = "t",
                    apiBaseUrl = "http://127.0.0.1:${apiServer.port}",
                    uploadBaseUrl = "http://127.0.0.1:${uploadServer.port}",
                )
                // Large enough that the client's write cannot complete inside the kernel's send
                // buffer alone before the server has already closed its end.
                val bigBundle = ByteArray(20_000_000) { (it % 253).toByte() }
                val result = client.upload(sampleRequest().copy(bundle = bigBundle)) as FieldReportUploadResult.Failure
                assertEquals(FieldReportUploadFailureReason.PARTIAL_WRITE, result.reason)
            }
        }
    }

    // ---- AC-147: the token never leaks into anything this class produces ------------------

    @Test
    fun `AC_147 the token never appears in a failure detail across a full failing run`() = runTest {
        val token = "sekrit-token-value-456"
        val fixture = RoutedFixture()
        fixture.on({ path(it) == "/repos/o/r/releases/tags/field-reports" }) {
            LoopbackResponse(500, "Internal Server Error", "server exploded, token=$token would be a bug".toByteArray())
        }
        LoopbackHttpFixture(fixture::respond).use { server ->
            val client = clientAt(server.port, token = token)
            val result = client.upload(sampleRequest()) as FieldReportUploadResult.Failure
            assertFalse(result.detail.contains(token), "leaked token into a failure detail: ${result.detail}")
        }
    }

    private fun clientAt(port: Int, token: String?) = RealFieldReportUploadClient(
        repository = "o/r",
        token = token,
        apiBaseUrl = "http://127.0.0.1:$port",
        uploadBaseUrl = "http://127.0.0.1:$port",
    )

    /** Accepts one connection and closes it immediately, before reading anything — simulates a
     * destination that drops the connection mid-upload, for the `PARTIAL_WRITE` test above. */
    private class ImmediateCloseServer : AutoCloseable {
        private val serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = serverSocket.localPort

        @Volatile
        private var stopped = false

        private val thread = Thread({
            while (!stopped) {
                val socket = try {
                    serverSocket.accept()
                } catch (e: IOException) {
                    return@Thread
                }
                try {
                    socket.close()
                } catch (e: IOException) {
                    // already gone
                }
            }
        }, "immediate-close-server").apply {
            isDaemon = true
            start()
        }

        override fun close() {
            stopped = true
            serverSocket.close()
            thread.join(1_000)
        }
    }
}
