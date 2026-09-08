package org.ort.net.real

import org.ort.net.HttpRangeClient
import org.ort.net.HttpRangeResult
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

/**
 * The only concrete network call in this codebase (technical design §16.1). Plain
 * `java.net.HttpURLConnection` — no third-party HTTP client dependency; see `:net`'s README for
 * why one was not added. A resumable ranged GET: sends `Range: bytes=<rangeStart>-` when
 * `rangeStart > 0`, and reports whether the server actually honoured it (HTTP 206) or served the
 * whole body anyway (HTTP 200), which the caller (`ModelAcquisition`) must not treat as a resume.
 */
public class RealHttpRangeClient(private val connectTimeoutMs: Int = 15_000, private val readTimeoutMs: Int = 15_000) :
    HttpRangeClient {

    override fun get(url: String, rangeStart: Long): HttpRangeResult {
        val connection = try {
            (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                requestMethod = "GET"
                if (rangeStart > 0) setRequestProperty("Range", "bytes=$rangeStart-")
            }
        } catch (e: IOException) {
            return HttpRangeResult.Failure("could not open connection to $url: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            return HttpRangeResult.Failure("malformed URL $url: ${e.message}", e)
        }

        return try {
            val code = connection.responseCode
            // `HttpURLConnection` does NOT throw when the peer closes the socket before
            // delivering as many bytes as its own Content-Length promised — a caller reading to
            // EOF gets a silently truncated body instead of an exception. That would make a
            // server dying mid-transfer indistinguishable from a genuinely complete (but
            // corrupt) download, which is exactly the distinction FR-AST-3's resumability
            // depends on: a truncated transfer must surface as a thrown IOException so the
            // caller (ModelAcquisition) keeps the partial file for resume, matching
            // FakeHttpRangeClient.dropAfterBytes. So the body is wrapped to enforce it.
            val expectedLength = connection.contentLengthLong.takeIf { it >= 0 }
            when (code) {
                HttpURLConnection.HTTP_OK -> {
                    val body = guardTruncation(connection.inputStream, expectedLength)
                    HttpRangeResult.Success(body, servedFromStart = true)
                }
                HttpURLConnection.HTTP_PARTIAL -> {
                    val body = guardTruncation(connection.inputStream, expectedLength)
                    HttpRangeResult.Success(body, servedFromStart = false)
                }
                else -> {
                    val detail = connection.errorStream?.use { it.readBytes() }?.decodeToString().orEmpty()
                    HttpRangeResult.Failure("HTTP $code from $url${if (detail.isNotBlank()) ": $detail" else ""}")
                }
            }
        } catch (e: IOException) {
            HttpRangeResult.Failure("request to $url failed: ${e.message}", e)
        }
    }

    private fun guardTruncation(body: InputStream, expectedLength: Long?): InputStream =
        if (expectedLength == null) body else LengthValidatingInputStream(body, expectedLength)

    /**
     * Throws [IOException] on EOF if fewer than [expectedLength] bytes were ever delivered,
     * instead of silently returning a short read as a clean end of stream.
     */
    private class LengthValidatingInputStream(delegate: InputStream, private val expectedLength: Long) :
        FilterInputStream(delegate) {
        private var received = 0L

        override fun read(): Int {
            val b = super.read()
            if (b == -1) {
                failIfShort()
            } else {
                received += 1
            }
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n == -1) {
                failIfShort()
            } else {
                received += n
            }
            return n
        }

        private fun failIfShort() {
            if (received < expectedLength) {
                throw IOException(
                    "premature end of stream: expected $expectedLength bytes, got $received",
                )
            }
        }
    }
}
