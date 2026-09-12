package org.ort.net.fieldreport.real

import org.ort.core.fieldreport.FieldReportDestination
import org.ort.core.fieldreport.FieldReportDestinationVisibility
import org.ort.core.fieldreport.FieldReportUploadClient
import org.ort.core.fieldreport.FieldReportUploadFailureReason
import org.ort.core.fieldreport.FieldReportUploadRequest
import org.ort.core.fieldreport.FieldReportUploadResult
import org.ort.net.fieldreport.GitHubJson
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/**
 * FR-OBS-11/FR-OBS-12: the real, `:net`-hosted field-report upload channel. Plain
 * `java.net.HttpURLConnection` — the same choice [org.ort.net.real.RealHttpRangeClient] makes and
 * for the same reason (`net/build.gradle.kts`'s own comment: no third-party HTTP client dependency
 * anywhere in this codebase, `PlatformGuards.httpClientViolations`/constitution V).
 *
 * Mechanism (WPR3 brief, since the Contents API's 100 MB ceiling is too low once retained audio is
 * included): a release asset on a dedicated tag ([releaseTag], created once and reused after), then
 * an issue linking it. Three GitHub REST calls make up a successful [upload]: verify-or-create the
 * release, upload the asset to it, then create the issue naming the device/build/commit/categories
 * and linking the asset's `browser_download_url`.
 *
 * [token] is `:app`'s `BuildConfig.FIELD_REPORT_TOKEN` — injected at build time from the
 * `ORT_FIELD_REPORT_TOKEN` environment variable exactly as `HF_TOKEN` is
 * (`FetchBundledAssetsTask`), present only in a debug build (`ort.android-app.gradle.kts`'s own
 * `buildTypes { debug { ... } }` block). This class never reads an environment variable itself and
 * never logs, or otherwise surfaces, [token]'s value: every [FieldReportUploadResult.Failure.detail]
 * this class produces is composed only from HTTP status codes, this class's own literal text, and
 * the *destination's* response bodies — never from [token], never from a request header dump.
 * [RealFieldReportUploadClientTest]'s own `AC_147_...` tests script a full failing run with a known
 * token value and scan every produced string for it.
 *
 * `null` or blank [token] means "not configured" (FR-OBS-12): [destination] returns `null` and
 * [upload] returns [FieldReportUploadFailureReason.NOT_CONFIGURED] — an honest state, never a
 * fabricated success, matching [FieldReportUploadClient]'s own contract.
 */
public class RealFieldReportUploadClient(
    private val repository: String,
    private val token: String?,
    private val releaseTag: String = "field-reports",
    private val apiBaseUrl: String = "https://api.github.com",
    private val uploadBaseUrl: String = "https://uploads.github.com",
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
) : FieldReportUploadClient {

    override suspend fun destination(): FieldReportDestination? {
        val activeToken = token?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val response = request("GET", "$apiBaseUrl/repos/$repository", activeToken)
            if (response.code == HttpURLConnection.HTTP_OK) {
                FieldReportDestination(
                    label = repository,
                    visibility = when (GitHubJson.extractBoolean(response.body, "private")) {
                        true -> FieldReportDestinationVisibility.PRIVATE
                        false -> FieldReportDestinationVisibility.PUBLIC
                        null -> FieldReportDestinationVisibility.UNKNOWN
                    },
                )
            } else {
                // FR-OBS-10 / constitution I: the destination answered, but not usefully (a bad
                // status, or a body this parser could not read a "private" field from). This is
                // UNKNOWN, never a guessed PRIVATE — guessing "private" here is exactly the failure
                // this product exists to avoid.
                FieldReportDestination(repository, FieldReportDestinationVisibility.UNKNOWN)
            }
        } catch (e: IOException) {
            // The destination could not be reached at all — still UNKNOWN, not PRIVATE, for the
            // identical reason.
            FieldReportDestination(repository, FieldReportDestinationVisibility.UNKNOWN)
        }
    }

    // detekt's ReturnCount (config/detekt/detekt.yml: max 5) is why this is continuation-passing
    // rather than one long function of sequential early returns: each `with*` helper below owns
    // exactly one phase's own try/catch and null check, so this function itself only ever has to
    // decide two things before handing off — capture-active, and configured-or-not.
    override suspend fun upload(request: FieldReportUploadRequest): FieldReportUploadResult {
        if (request.captureActive) {
            return FieldReportUploadResult.Failure(
                FieldReportUploadFailureReason.CAPTURE_ACTIVE,
                "upload refused: capture is active (FR-OBS-11)",
            )
        }
        val activeToken = token?.takeIf { it.isNotBlank() }
            ?: return FieldReportUploadResult.Failure(
                FieldReportUploadFailureReason.NOT_CONFIGURED,
                "no field-report token is configured in this build",
            )
        return withRelease(activeToken) { releaseId ->
            withUploadedAsset(activeToken, releaseId, request) { downloadUrl ->
                withCreatedIssue(activeToken, request, downloadUrl)
            }
        }
    }

    private fun withRelease(
        activeToken: String,
        then: (releaseId: Long) -> FieldReportUploadResult,
    ): FieldReportUploadResult {
        val releaseId = try {
            resolveOrCreateRelease(activeToken)
        } catch (e: IOException) {
            return FieldReportUploadResult.Failure(
                FieldReportUploadFailureReason.DESTINATION_UNREACHABLE,
                "could not reach $repository to prepare the field-report release (${e.javaClass.simpleName})",
            )
        }
        return if (releaseId != null) {
            then(releaseId)
        } else {
            FieldReportUploadResult.Failure(
                FieldReportUploadFailureReason.DESTINATION_UNREACHABLE,
                "could not verify or create the field-report release on $repository",
            )
        }
    }

    private fun withUploadedAsset(
        activeToken: String,
        releaseId: Long,
        request: FieldReportUploadRequest,
        then: (downloadUrl: String) -> FieldReportUploadResult,
    ): FieldReportUploadResult {
        val downloadUrl = try {
            uploadAsset(activeToken, releaseId, request.fileName, request.bundle)
        } catch (e: IOException) {
            return FieldReportUploadResult.Failure(
                FieldReportUploadFailureReason.PARTIAL_WRITE,
                "the bundle upload did not complete (${e.javaClass.simpleName})",
            )
        }
        return if (downloadUrl != null) {
            then(downloadUrl)
        } else {
            FieldReportUploadResult.Failure(
                FieldReportUploadFailureReason.UPLOAD_FAILED,
                "the destination rejected the bundle upload",
            )
        }
    }

    private fun withCreatedIssue(
        activeToken: String,
        request: FieldReportUploadRequest,
        downloadUrl: String,
    ): FieldReportUploadResult = try {
        val issueUrl = createIssue(activeToken, request, downloadUrl)
        if (issueUrl != null) {
            FieldReportUploadResult.Success(issueUrl)
        } else {
            FieldReportUploadResult.Failure(
                FieldReportUploadFailureReason.ISSUE_CREATE_FAILED,
                "the bundle uploaded to $downloadUrl but the destination rejected the issue",
            )
        }
    } catch (e: IOException) {
        FieldReportUploadResult.Failure(
            FieldReportUploadFailureReason.ISSUE_CREATE_FAILED,
            "the bundle uploaded to $downloadUrl but the issue could not be created (${e.javaClass.simpleName})",
        )
    }

    /** Idempotent: a release already tagged [releaseTag] is reused, never recreated. */
    private fun resolveOrCreateRelease(activeToken: String): Long? {
        val existing = request("GET", "$apiBaseUrl/repos/$repository/releases/tags/$releaseTag", activeToken)
        if (existing.code == HttpURLConnection.HTTP_OK) {
            return GitHubJson.extractLong(existing.body, "id")
        }
        val body = "{" +
            "\"tag_name\":\"${GitHubJson.jsonEscape(releaseTag)}\"," +
            "\"name\":\"${GitHubJson.jsonEscape(releaseTag)}\"," +
            "\"body\":\"${GitHubJson.jsonEscape(RELEASE_BODY)}\"," +
            "\"draft\":false," +
            "\"prerelease\":true" +
            "}"
        val created = request(
            "POST",
            "$apiBaseUrl/repos/$repository/releases",
            activeToken,
            contentType = "application/json; charset=utf-8",
            body = body.toByteArray(Charsets.UTF_8),
        )
        return if (created.code == HttpURLConnection.HTTP_CREATED) GitHubJson.extractLong(created.body, "id") else null
    }

    private fun uploadAsset(activeToken: String, releaseId: Long, fileName: String, bundle: ByteArray): String? {
        val encodedName = URLEncoder.encode(fileName, "UTF-8")
        val response = request(
            "POST",
            "$uploadBaseUrl/repos/$repository/releases/$releaseId/assets?name=$encodedName",
            activeToken,
            contentType = "application/zip",
            body = bundle,
        )
        return if (response.code == HttpURLConnection.HTTP_CREATED) {
            GitHubJson.extractString(response.body, "browser_download_url")
        } else {
            null
        }
    }

    private fun createIssue(activeToken: String, req: FieldReportUploadRequest, downloadUrl: String): String? {
        val title = "Field report: ${req.fileName}"
        val body = issueBody(req, downloadUrl)
        val json = "{\"title\":\"${GitHubJson.jsonEscape(title)}\",\"body\":\"${GitHubJson.jsonEscape(body)}\"}"
        val response = request(
            "POST",
            "$apiBaseUrl/repos/$repository/issues",
            activeToken,
            contentType = "application/json; charset=utf-8",
            body = json.toByteArray(Charsets.UTF_8),
        )
        return if (response.code == HttpURLConnection.HTTP_CREATED) {
            GitHubJson.extractString(response.body, "html_url")
        } else {
            null
        }
    }

    /** WPR3 brief: "The issue body names the device, the build, the commit, and which categories
     * were included." Every field here is plain data [req] already carries — this class never
     * reaches into `android.os.Build`/`BuildConfig` itself (see this class's own top doc comment). */
    private fun issueBody(req: FieldReportUploadRequest, downloadUrl: String): String = buildString {
        appendLine("Automated field-report upload (FR-OBS-6..12, D37/D38).")
        appendLine()
        appendLine("- Device: ${req.deviceLabel}")
        appendLine("- Build: ${req.buildLabel}")
        appendLine("- Commit: ${req.commitLabel}")
        val categoryLabel = req.categoriesIncluded.takeIf { it.isNotEmpty() }
            ?.joinToString()
            ?: "none (ungated set only)"
        appendLine("- Categories included: $categoryLabel")
        appendLine("- Bundle: $downloadUrl")
    }

    private fun request(
        method: String,
        url: String,
        token: String,
        contentType: String? = null,
        body: ByteArray? = null,
    ): HttpResponse {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.requestMethod = method
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        connection.setRequestProperty("Authorization", "Bearer $token")
        if (contentType != null) connection.setRequestProperty("Content-Type", contentType)
        if (body != null) {
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
        }
        val code = connection.responseCode
        val bytes = if (code in HTTP_SUCCESS_RANGE) {
            connection.inputStream.use { it.readBytes() }
        } else {
            connection.errorStream?.use { it.readBytes() } ?: ByteArray(0)
        }
        return HttpResponse(code, bytes.decodeToString())
    }

    private data class HttpResponse(val code: Int, val body: String)

    private companion object {
        const val RELEASE_BODY =
            "Field reports uploaded by the offline-radio-transcriber field-report channel (D37/D38)."
        val HTTP_SUCCESS_RANGE = 200..299
    }
}
