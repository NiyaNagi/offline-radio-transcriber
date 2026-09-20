package org.ort.net.analytics.real

import org.ort.core.analytics.AnalyticsPurgeResult
import org.ort.core.analytics.AnalyticsUploadClient
import org.ort.core.analytics.AnalyticsUploadFailureReason
import org.ort.core.analytics.AnalyticsUploadRequest
import org.ort.core.analytics.AnalyticsUploadResult
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/**
 * D48: the real, `:net`-hosted analytics upload channel. Plain `java.net.HttpURLConnection` — the
 * same choice [org.ort.net.fieldreport.real.RealFieldReportUploadClient] and
 * [org.ort.net.real.RealHttpRangeClient] make and for the same reason (no third-party HTTP client
 * dependency anywhere in this codebase, constitution V / `PlatformGuards`).
 *
 * [endpoint] is `:app`'s `BuildConfig.ANALYTICS_ENDPOINT`, injected at build time from the
 * `ORT_ANALYTICS_ENDPOINT` Gradle property/environment variable (D48). `null` or blank means "not
 * configured" — [isConfigured] reports `false`, [upload]/[purge] return
 * [AnalyticsUploadFailureReason.NOT_CONFIGURED] — the default state today, since no endpoint has
 * been deployed: events queue locally and nothing is ever sent. This class never fabricates a
 * success.
 *
 * The wire contract with `tools/analytics`' reference ingest server: `POST {endpoint}/ingest`,
 * body [AnalyticsUploadRequest.ndjson] verbatim (already the closed-field-list wire form,
 * `org.ort.telemetry.AnalyticsEventCodec`), `Content-Type: application/x-ndjson`, an
 * `X-Event-Count` header for the server's own accounting; `DELETE {endpoint}/install/{id}` for
 * [purge] (FR-ANL-11's erasure-by-install-id, D48).
 */
public class RealAnalyticsUploadClient(
    private val endpoint: String?,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
) : AnalyticsUploadClient {

    private val activeEndpoint: String? get() = endpoint?.trim()?.takeIf { it.isNotBlank() }?.trimEnd('/')

    override suspend fun isConfigured(): Boolean = activeEndpoint != null

    override suspend fun upload(request: AnalyticsUploadRequest): AnalyticsUploadResult {
        if (request.captureActive) {
            return AnalyticsUploadResult.Failure(
                AnalyticsUploadFailureReason.CAPTURE_ACTIVE,
                "upload refused: capture is active (FR-ANL-7)",
            )
        }
        val base = activeEndpoint
            ?: return AnalyticsUploadResult.Failure(
                AnalyticsUploadFailureReason.NOT_CONFIGURED,
                "no analytics endpoint is configured in this build",
            )
        return try {
            val code = post(
                "$base/ingest",
                body = request.ndjson.toByteArray(Charsets.UTF_8),
                contentType = "application/x-ndjson",
                extraHeaders = mapOf("X-Event-Count" to request.eventCount.toString()),
            )
            if (code in HTTP_SUCCESS_RANGE) {
                AnalyticsUploadResult.Success
            } else {
                AnalyticsUploadResult.Failure(
                    AnalyticsUploadFailureReason.UPLOAD_FAILED,
                    "the destination rejected the upload (HTTP $code)",
                )
            }
        } catch (e: IOException) {
            AnalyticsUploadResult.Failure(
                AnalyticsUploadFailureReason.PARTIAL_WRITE,
                "the upload did not complete (${e.javaClass.simpleName})",
            )
        }
    }

    override suspend fun purge(installId: String): AnalyticsPurgeResult {
        val base = activeEndpoint
            ?: return AnalyticsPurgeResult.Failure(
                AnalyticsUploadFailureReason.NOT_CONFIGURED,
                "no analytics endpoint is configured in this build",
            )
        return try {
            val encoded = URLEncoder.encode(installId, "UTF-8")
            val code = delete("$base/install/$encoded")
            if (code in HTTP_SUCCESS_RANGE) {
                AnalyticsPurgeResult.Success
            } else {
                AnalyticsPurgeResult.Failure(
                    AnalyticsUploadFailureReason.UPLOAD_FAILED,
                    "the destination rejected the purge (HTTP $code)",
                )
            }
        } catch (e: IOException) {
            AnalyticsPurgeResult.Failure(
                AnalyticsUploadFailureReason.DESTINATION_UNREACHABLE,
                "could not reach $base to purge $installId (${e.javaClass.simpleName})",
            )
        }
    }

    private fun post(url: String, body: ByteArray, contentType: String, extraHeaders: Map<String, String>): Int {
        val connection = open(url, "POST")
        connection.setRequestProperty("Content-Type", contentType)
        for ((name, value) in extraHeaders) connection.setRequestProperty(name, value)
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(body.size)
        connection.outputStream.use { it.write(body) }
        return connection.responseCode
    }

    private fun delete(url: String): Int = open(url, "DELETE").responseCode

    private fun open(url: String, method: String): HttpURLConnection {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.requestMethod = method
        return connection
    }

    private companion object {
        val HTTP_SUCCESS_RANGE = 200..299
    }
}
