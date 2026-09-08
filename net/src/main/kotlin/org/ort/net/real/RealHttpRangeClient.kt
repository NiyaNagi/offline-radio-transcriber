package org.ort.net.real

import org.ort.net.HttpRangeClient
import org.ort.net.HttpRangeResult
import java.io.IOException
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
            when (code) {
                HttpURLConnection.HTTP_OK -> HttpRangeResult.Success(connection.inputStream, servedFromStart = true)
                HttpURLConnection.HTTP_PARTIAL ->
                    HttpRangeResult.Success(connection.inputStream, servedFromStart = false)
                else -> {
                    val detail = connection.errorStream?.use { it.readBytes() }?.decodeToString().orEmpty()
                    HttpRangeResult.Failure("HTTP $code from $url${if (detail.isNotBlank()) ": $detail" else ""}")
                }
            }
        } catch (e: IOException) {
            HttpRangeResult.Failure("request to $url failed: ${e.message}", e)
        }
    }
}
