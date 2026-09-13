package org.ort.gradle

import com.sun.net.httpserver.HttpServer
import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * R-1075 (register — CI run 34769228303 failed 17s in on a bare HTTP 500, with no retry, while
 * the Release workflow on the identical commit fetched the same asset fine): [RetryingDownload]
 * is the one retry/backoff policy [FetchSherpaNativeTask] and [FetchBundledAssetsTask] both use.
 * These tests exercise the policy itself directly — the checksum/token-specific scenarios that
 * need a real fetcher (a sha256 mismatch never retrying, a bearer token surviving a retry without
 * ever being logged) live in [FetchSherpaNativeTaskTest] and [FetchBundledAssetsTaskTest]
 * instead, next to the fetcher logic they actually exercise.
 *
 * Every test uses a tiny in-process [HttpServer] (JDK-built-in) and an injected no-op/capturing
 * [sleeper][RetryingDownload.download]'s `sleeper` parameter — never a real network call, never a
 * real wait — same discipline this package's other fetch tests already document.
 */
class RetryingDownloadTest {

    private fun server(handler: (com.sun.net.httpserver.HttpExchange) -> Unit): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/a.bin", handler)
        server.start()
        return server
    }

    @Test
    fun `a first-attempt 200 succeeds with no retry and leaves no part file`(@TempDir dir: File) {
        val body = "hello".toByteArray()
        val requestCount = AtomicInteger(0)
        val srv = server { exchange ->
            requestCount.incrementAndGet()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        try {
            val dest = File(dir, "a.bin")
            var retried = false

            RetryingDownload.download(
                url = "http://127.0.0.1:${srv.address.port}/a.bin",
                dest = dest,
                taskLabel = "test",
                sleeper = { throw AssertionError("must not sleep on a first-attempt success") },
                onRetry = { _, _, _ -> retried = true },
            )

            assertEquals(1, requestCount.get())
            assertFalse(retried)
            assertEquals(body.toList(), dest.readBytes().toList())
            assertFalse(File(dir, "a.bin.part").exists(), "no .part file must remain after success")
        } finally {
            srv.stop(0)
        }
    }

    @Test
    fun `R_1075 a 500 then a 200 succeeds after exactly one retry using the first backoff step`(
        @TempDir dir: File,
    ) {
        val body = "hello".toByteArray()
        val requestCount = AtomicInteger(0)
        val srv = server { exchange ->
            if (requestCount.incrementAndGet() == 1) {
                exchange.sendResponseHeaders(500, -1)
                exchange.close()
            } else {
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }
        try {
            val dest = File(dir, "a.bin")
            val retries = mutableListOf<Triple<Int, String, Long>>()

            RetryingDownload.download(
                url = "http://127.0.0.1:${srv.address.port}/a.bin",
                dest = dest,
                taskLabel = "test",
                sleeper = { },
                onRetry = { attempt, reason, waitMs -> retries += Triple(attempt, reason, waitMs) },
            )

            assertEquals(2, requestCount.get())
            assertEquals(1, retries.size)
            assertEquals(1, retries.single().first, "must report attempt 1 failed")
            assertEquals("HTTP 500", retries.single().second)
            assertEquals(2_000L, retries.single().third, "the first backoff step is 2s")
            assertEquals(body.toList(), dest.readBytes().toList())
        } finally {
            srv.stop(0)
        }
    }

    @Test
    fun `R_1075 four consecutive 503s exhaust retries and the message names all four reasons`(
        @TempDir dir: File,
    ) {
        val requestCount = AtomicInteger(0)
        val srv = server { exchange ->
            requestCount.incrementAndGet()
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        try {
            val dest = File(dir, "a.bin")
            val waits = mutableListOf<Long>()

            val thrown = assertThrows(GradleException::class.java) {
                RetryingDownload.download(
                    url = "http://127.0.0.1:${srv.address.port}/a.bin",
                    dest = dest,
                    taskLabel = "test",
                    sleeper = { waits += it },
                )
            }

            assertEquals(RetryingDownload.MAX_ATTEMPTS, requestCount.get())
            assertEquals(listOf(2_000L, 5_000L, 15_000L), waits, "the full escalating backoff must be used")
            assertTrue(thrown.message!!.contains("after 4 attempts"), "got: ${thrown.message}")
            assertEquals(4, Regex("HTTP 503").findAll(thrown.message!!).count(), "got: ${thrown.message}")
            assertFalse(dest.exists(), "no file at the final path after exhausted retries")
            assertFalse(File(dir, "a.bin.part").exists(), "no .part file must remain after exhausted retries")
        } finally {
            srv.stop(0)
        }
    }

    @Test
    fun `R_1075 a 404 fails on the first attempt with no retry and a clear message`(@TempDir dir: File) {
        val requestCount = AtomicInteger(0)
        val srv = server { exchange ->
            requestCount.incrementAndGet()
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        try {
            val dest = File(dir, "a.bin")

            val thrown = assertThrows(GradleException::class.java) {
                RetryingDownload.download(
                    url = "http://127.0.0.1:${srv.address.port}/a.bin",
                    dest = dest,
                    taskLabel = "test",
                    sleeper = { throw AssertionError("a 404 must never be retried") },
                    onRetry = { _, _, _ -> throw AssertionError("a 404 must never invoke onRetry") },
                )
            }

            assertEquals(1, requestCount.get())
            assertTrue(thrown.message!!.contains("HTTP 404"))
            assertFalse(
                thrown.message!!.contains("after"),
                "a non-retryable failure is not an attempts-exhausted message",
            )
        } finally {
            srv.stop(0)
        }
    }

    @Test
    fun `R_1075 a 401 and a 403 are also never retried`(@TempDir dir: File) {
        listOf(401, 403).forEach { status ->
            val requestCount = AtomicInteger(0)
            val srv = server { exchange ->
                requestCount.incrementAndGet()
                exchange.sendResponseHeaders(status, -1)
                exchange.close()
            }
            try {
                assertThrows(GradleException::class.java) {
                    RetryingDownload.download(
                        url = "http://127.0.0.1:${srv.address.port}/a.bin",
                        dest = File(dir, "a-$status.bin"),
                        taskLabel = "test",
                        sleeper = { throw AssertionError("HTTP $status must never be retried") },
                    )
                }
                assertEquals(1, requestCount.get(), "HTTP $status must not be retried")
            } finally {
                srv.stop(0)
            }
        }
    }

    @Test
    fun `R_1075 a connection reset then a 200 succeeds after one retry`(@TempDir dir: File) {
        val body = "hello".toByteArray()
        val requestCount = AtomicInteger(0)
        val srv = server { exchange ->
            if (requestCount.incrementAndGet() == 1) {
                // No response at all — the client's connect/read fails with an IOException,
                // simulating a mid-flight reset.
                exchange.close()
            } else {
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }
        try {
            val dest = File(dir, "a.bin")

            RetryingDownload.download(
                url = "http://127.0.0.1:${srv.address.port}/a.bin",
                dest = dest,
                taskLabel = "test",
                sleeper = { },
            )

            assertEquals(2, requestCount.get())
            assertEquals(body.toList(), dest.readBytes().toList())
        } finally {
            srv.stop(0)
        }
    }

    @Test
    fun `R_1075 a Retry-After header overrides the default backoff when reasonable`(@TempDir dir: File) {
        val body = "hello".toByteArray()
        val requestCount = AtomicInteger(0)
        val srv = server { exchange ->
            if (requestCount.incrementAndGet() == 1) {
                exchange.responseHeaders.add("Retry-After", "1")
                exchange.sendResponseHeaders(503, -1)
                exchange.close()
            } else {
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }
        try {
            val waits = mutableListOf<Long>()

            RetryingDownload.download(
                url = "http://127.0.0.1:${srv.address.port}/a.bin",
                dest = File(dir, "a.bin"),
                taskLabel = "test",
                sleeper = { waits += it },
            )

            assertEquals(listOf(1_000L), waits, "Retry-After: 1 (second) must override the 2s default backoff")
        } finally {
            srv.stop(0)
        }
    }

    @Test
    fun `R_1075 an unreasonable Retry-After header is ignored in favour of the default backoff`(
        @TempDir dir: File,
    ) {
        val body = "hello".toByteArray()
        val requestCount = AtomicInteger(0)
        val srv = server { exchange ->
            if (requestCount.incrementAndGet() == 1) {
                exchange.responseHeaders.add("Retry-After", "999999")
                exchange.sendResponseHeaders(503, -1)
                exchange.close()
            } else {
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }
        try {
            val waits = mutableListOf<Long>()

            RetryingDownload.download(
                url = "http://127.0.0.1:${srv.address.port}/a.bin",
                dest = File(dir, "a.bin"),
                taskLabel = "test",
                sleeper = { waits += it },
            )

            assertEquals(listOf(2_000L), waits, "an unreasonably large Retry-After must be ignored")
        } finally {
            srv.stop(0)
        }
    }

    @Test
    fun `a file URL source (no HttpURLConnection) still downloads successfully`(@TempDir dir: File) {
        val source = File(dir, "source.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val dest = File(dir, "dest.bin")

        RetryingDownload.download(
            url = source.toURI().toString(),
            dest = dest,
            taskLabel = "test",
            sleeper = { throw AssertionError("must not sleep for a file:// source") },
        )

        assertEquals(listOf<Byte>(1, 2, 3), dest.readBytes().toList())
    }
}
