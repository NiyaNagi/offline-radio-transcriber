package org.ort.gradle

import org.gradle.api.GradleException
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * R-1075 (register — CI run 34769228303 failed 17s in on a bare `HTTP 500` from GitHub's
 * release-asset CDN while the Release workflow on the identical commit fetched the same asset
 * fine): the one retry/backoff policy shared by [FetchSherpaNativeTask]'s and
 * [FetchBundledAssetsTask]'s download logic, previously two independent single-attempt
 * `downloadTo` functions that turned any transient server hiccup into a red commit.
 *
 * **Policy, chosen and justified:**
 * - **4 attempts total** (1 initial + up to 3 retries). A transient 5xx from a CDN or an
 *   overloaded release-asset host is typically resolved within tens of seconds; 4 attempts is
 *   enough to ride out a single bad edge node or a momentary rate limit without turning a real,
 *   sustained outage into a build that hangs for many minutes on every push.
 * - **Backoff 2s, 5s, 15s** between attempts 1→2, 2→3, 3→4: a short first retry (most 500s are a
 *   single bad edge node that recovers almost immediately), growing to ride out a slightly longer
 *   blip, capped at 15s so all 4 attempts finish in well under a minute even in the worst case.
 * - **A `Retry-After` response header wins when present and reasonable** (a non-negative integer
 *   number of seconds, capped at [MAX_RETRY_AFTER_SECONDS]): the server is telling us exactly how
 *   long to wait, and honouring that (bounded, so a misconfigured or hostile server cannot stall
 *   the build for an hour) is more correct than guessing with the fixed backoff.
 * - **Retried:** HTTP 5xx, HTTP 429, and any [IOException] raised while connecting or reading
 *   (timeouts, resets, premature EOF) — exactly the set a transient network or server hiccup
 *   produces.
 * - **Never retried:** any other 4xx (401/403/404/…) — a wrong URL, a missing licence acceptance
 *   or a bad credential does not fix itself by asking again — and, structurally, this helper never
 *   sees a checksum/sha256 or size mismatch at all: that check runs one layer up, on the completed
 *   download, in each fetcher — never inside this retry loop. Constitution I: a wrong asset is
 *   never quietly retried into acceptance, and that applies equally to a request that will never
 *   succeed.
 * - **Atomic by construction.** Every attempt writes to a sibling `<dest-name>.part` file, deleted
 *   before each attempt starts and again on any failure; [dest] itself is only ever written once,
 *   by copying the completed `.part` file over it after a fully successful transfer. A partial
 *   file from a failed attempt is therefore never left at, or reused from, the final path.
 * - **The token is never in the retry log.** Callers pass request headers (e.g. an `Authorization`
 *   bearer token) via [requestProperties]; only the failure *reason* (an HTTP status or an
 *   exception class name/message) and the wait are ever passed to [onRetry] or included in the
 *   final failure message — headers are never read back out of the connection for logging.
 */
object RetryingDownload {

    /** 1 initial attempt + 3 retries. */
    const val MAX_ATTEMPTS = 4

    private val BACKOFF_MS = longArrayOf(2_000L, 5_000L, 15_000L)
    private const val MAX_RETRY_AFTER_SECONDS = 120L
    private val HTTP_OK_RANGE = 200..299

    /**
     * Downloads [url] to [dest], retrying transient failures per this object's own policy (see
     * class KDoc). [taskLabel] (e.g. `"fetchBundledAssets"`) prefixes every message, matching the
     * existing per-task message format. [sleeper] is invoked with each backoff duration in
     * milliseconds instead of a bare `Thread.sleep` so tests can inject a no-op and run instantly.
     * [onRetry] is called once per retry (never on the final, unrecovered failure) with the
     * 1-based attempt number that just failed, the failure reason, and the wait before the next
     * attempt — callers wire this to their own lifecycle-level logger.
     *
     * Throws [GradleException] — naming every attempt's reason when attempts were exhausted, or
     * just the one reason for a non-retryable failure — and never leaves a partial file at [dest].
     */
    fun download(
        url: String,
        dest: File,
        taskLabel: String,
        requestProperties: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 30_000,
        readTimeoutMs: Int = 180_000,
        sleeper: (Long) -> Unit = Thread::sleep,
        onRetry: (attempt: Int, reason: String, waitMs: Long) -> Unit = { _, _, _ -> },
    ) {
        dest.parentFile?.mkdirs()
        val partFile = File(dest.parentFile, "${dest.name}.part")
        val attemptFailures = mutableListOf<String>()

        var attempt = 1
        while (true) {
            partFile.delete()
            when (val outcome = attemptOnce(url, partFile, requestProperties, connectTimeoutMs, readTimeoutMs)) {
                is AttemptOutcome.Success -> {
                    partFile.copyTo(dest, overwrite = true)
                    partFile.delete()
                    return
                }
                is AttemptOutcome.NonRetryable -> {
                    partFile.delete()
                    throw GradleException("$taskLabel: download failed for $url — ${outcome.reason}")
                }
                is AttemptOutcome.Retryable -> {
                    partFile.delete()
                    attemptFailures += "attempt $attempt: ${outcome.reason}"
                    if (attempt >= MAX_ATTEMPTS) {
                        throw GradleException(
                            "$taskLabel: download failed for $url after $MAX_ATTEMPTS attempts — " +
                                attemptFailures.joinToString("; "),
                        )
                    }
                    val wait = outcome.retryAfterMs ?: BACKOFF_MS[attempt - 1]
                    onRetry(attempt, outcome.reason, wait)
                    sleeper(wait)
                    attempt++
                }
            }
        }
    }

    private sealed interface AttemptOutcome {
        object Success : AttemptOutcome
        data class Retryable(val reason: String, val retryAfterMs: Long?) : AttemptOutcome
        data class NonRetryable(val reason: String) : AttemptOutcome
    }

    private fun attemptOnce(
        url: String,
        partFile: File,
        requestProperties: Map<String, String>,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): AttemptOutcome {
        try {
            val connection = URI(url).toURL().openConnection()
            if (connection is HttpURLConnection) {
                connection.instanceFollowRedirects = true
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                requestProperties.forEach { (key, value) -> connection.setRequestProperty(key, value) }
                val code = connection.responseCode
                if (code !in HTTP_OK_RANGE) {
                    return if (isRetryableStatus(code)) {
                        AttemptOutcome.Retryable("HTTP $code", retryAfterMillisOf(connection))
                    } else {
                        AttemptOutcome.NonRetryable("HTTP $code")
                    }
                }
            }
            connection.getInputStream().use { input ->
                partFile.outputStream().use { output -> input.copyTo(output) }
            }
            return AttemptOutcome.Success
        } catch (e: IOException) {
            return AttemptOutcome.Retryable(reasonOf(e), null)
        }
    }

    private fun reasonOf(e: IOException): String =
        e::class.java.simpleName + (e.message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")

    private fun isRetryableStatus(code: Int): Boolean = code >= 500 || code == 429

    private fun retryAfterMillisOf(connection: HttpURLConnection): Long? {
        val header = connection.getHeaderField("Retry-After") ?: return null
        val seconds = header.trim().toLongOrNull() ?: return null
        if (seconds < 0 || seconds > MAX_RETRY_AFTER_SECONDS) return null
        return seconds * 1_000
    }
}
