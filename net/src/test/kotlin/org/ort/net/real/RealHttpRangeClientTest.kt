package org.ort.net.real

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.net.HttpRangeResult
import org.ort.testing.Requirement
import java.io.IOException

/**
 * Exercises the real `HttpURLConnection`/`Range`-header wiring (F-024) against a loopback
 * `java.net.ServerSocket` fixture rather than `com.sun.net.httpserver.HttpServer` — see
 * `net/README.md` for why that JDK server was rejected (`jdk.httpserver` is not on this
 * Android-library module's unit-test classpath). No test here opens a socket beyond
 * `127.0.0.1` on an ephemeral port; nothing reaches an external network.
 *
 * Assertions mirror [org.ort.net.fake.FakeHttpRangeClient]'s documented semantics exactly, so
 * [org.ort.net.ModelAcquisition] can trust either implementation identically.
 */
class RealHttpRangeClientTest {

    private val body = ByteArray(4096) { (it % 251).toByte() }

    @Test
    @Requirement("FR-AST-1")
    fun `P18_a full fetch with no range returns the exact bytes and is not marked as a resume`() {
        LoopbackHttpFixture { request ->
            assertNull(request.headers["range"], "a fresh fetch must not send a Range header")
            LoopbackResponse(200, "OK", body)
        }.use { server ->
            val client = RealHttpRangeClient()

            val result = client.get("http://127.0.0.1:${server.port}/model.bin", rangeStart = 0L)

            val success = result as HttpRangeResult.Success
            assertTrue(success.servedFromStart)
            assertEquals(body.toList(), success.body.use { it.readBytes() }.toList())
        }
    }

    @Test
    @Requirement("FR-AST-3")
    fun `P18_resume a range request receives only the tail`() {
        val offset = 1000
        LoopbackHttpFixture { request ->
            val range = request.headers["range"]
            assertEquals("bytes=$offset-", range, "a resumed fetch must send Range: bytes=<offset>-")
            val tail = body.copyOfRange(offset, body.size)
            LoopbackResponse(
                206,
                "Partial Content",
                tail,
                extraHeaders = mapOf("Content-Range" to "bytes $offset-${body.size - 1}/${body.size}"),
            )
        }.use { server ->
            val client = RealHttpRangeClient()

            val result = client.get("http://127.0.0.1:${server.port}/model.bin", rangeStart = offset.toLong())

            val success = result as HttpRangeResult.Success
            assertTrue(!success.servedFromStart, "a 206 response must not be reported as served from the start")
            assertEquals(
                body.copyOfRange(offset, body.size).toList(),
                success.body.use { it.readBytes() }.toList(),
            )
        }
    }

    @Test
    @Requirement("FR-AST-1")
    fun `P18_a non-2xx status surfaces as a documented Failure, not a thrown exception`() {
        LoopbackHttpFixture { _ ->
            LoopbackResponse(404, "Not Found", "no such model".toByteArray())
        }.use { server ->
            val client = RealHttpRangeClient()

            val result = client.get("http://127.0.0.1:${server.port}/missing.bin", rangeStart = 0L)

            val failure = result as HttpRangeResult.Failure
            assertTrue(
                failure.reason.contains("404"),
                "the failure reason must name the status code: ${failure.reason}",
            )
            assertTrue(
                failure.reason.contains("no such model"),
                "the failure reason should surface the server's error body: ${failure.reason}",
            )
        }
    }

    @Test
    @Requirement("FR-AST-3")
    fun `P18_a connection that closes mid-body surfaces as a resumable partial, not a silently truncated success`() {
        val delivered = 1500
        LoopbackHttpFixture { _ ->
            // Promises the full body's Content-Length but the fixture's writeTo only emits
            // `delivered` bytes before the caller closes the socket underneath it — a server
            // dying mid-transfer, mirroring FakeHttpRangeClient.dropAfterBytes.
            LoopbackResponse(200, "OK", body, truncateBodyTo = delivered)
        }.use { server ->
            val client = RealHttpRangeClient()

            val result = client.get("http://127.0.0.1:${server.port}/model.bin", rangeStart = 0L)

            // Headers arrive fine, so the client reports Success optimistically, exactly like
            // FakeHttpRangeClient's dropAfterBytes: the caller (ModelAcquisition) discovers the
            // drop only when it actually reads the body, which is what makes the partial bytes
            // already written to disk resumable rather than lost.
            val success = result as HttpRangeResult.Success
            assertThrows(IOException::class.java) { success.body.use { it.readBytes() } }
        }
    }
}
