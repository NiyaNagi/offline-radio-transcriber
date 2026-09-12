package org.ort.app.fieldreport.upload

import org.ort.app.fieldreport.bundle.FieldReportGatedCategory

/** FR-OBS-9's "destination and destination repository's visibility". [isPublic] is the one fact
 * FR-OBS-10's guard ([org.ort.app.fieldreport.consent.FieldReportGuard]) reads. */
public data class FieldReportDestination(val label: String, val isPublic: Boolean)

/** One field-report upload attempt. [categoriesIncluded] is exactly what the operator had toggled
 * on for *this* send (FR-OBS-9: "per-upload, never inferred from a previous upload's choice") —
 * always a subset of the three [FieldReportGatedCategory] values; the ungated set is not named
 * here because it is never conditional (FR-OBS-8). */
public data class FieldReportUploadRequest(
    val bundle: ByteArray,
    val fileName: String,
    val categoriesIncluded: Set<FieldReportGatedCategory>,
)

public sealed interface FieldReportUploadResult {
    public data class Success(val issueUrl: String) : FieldReportUploadResult
    public data class Failure(val reason: String) : FieldReportUploadResult
}

/**
 * FR-OBS-11/FR-OBS-12: the seam WPR3's real client implements — this round builds the consent
 * screen and the bundle, never the upload itself.
 *
 * **Flagged rather than guessed past: this interface is declared here, in `:app`'s own
 * `fieldreport` package, not in `:net`.** FR-OBS-11 says the field-report client "SHALL live
 * entirely in the network module", and this round's own file-ownership map puts the `:net` module
 * out of reach for this builder. `:app` already depends on `:net` (never the reverse, and `:net` may not
 * gain a dependency on `:app` without inverting that direction), so a `:net`-hosted class cannot
 * implement an interface declared in `:app`. Two resolutions for whoever routes this next: (a)
 * move this interface's declaration into `:net`'s own public API (mirroring
 * [org.ort.net.HttpRangeClient]'s home) once a builder owns that file, leaving a thin re-export
 * here so today's callers (`SettingsContent.kt`) do not move twice; or (b) declare it in `:core`
 * instead — already free of both `:app` and `:net` (Boundaries principle VII) — and have `:net`
 * depend on `:core` the way it already must for `Clock`. Nothing in this round depends on which:
 * [FakeFieldReportUploadClient] and every caller here are written entirely against this interface,
 * wherever it ultimately lives.
 *
 * [destination] is asked fresh before every upload — never cached, matching FR-OBS-9's "per-
 * upload, never inferred from a previous upload's choice" — and returns `null` exactly when no
 * real destination is configured in this build: the same honest "not available" state
 * `SettingsContributeScreen.kt` already renders for its own not-yet-built upload client
 * ("No contribution upload client exists in :net yet"), never a fabricated placeholder repository.
 */
public interface FieldReportUploadClient {
    public suspend fun destination(): FieldReportDestination?
    public suspend fun upload(request: FieldReportUploadRequest): FieldReportUploadResult
}

/**
 * Constitution II's behavioural fake: can be told which destination to report (including `null`,
 * "not configured", and a public one — the shape [org.ort.app.fieldreport.consent.FieldReportGuard]'s
 * own tests exercise) and which result an upload returns, and records every call for assertions.
 */
public class FakeFieldReportUploadClient(
    @Volatile public var destinationToReturn: FieldReportDestination? = null,
    @Volatile public var resultToReturn: FieldReportUploadResult =
        FieldReportUploadResult.Success("https://example.invalid/issues/1"),
) : FieldReportUploadClient {

    public var uploadCallCount: Int = 0
        private set
    public var lastRequest: FieldReportUploadRequest? = null
        private set

    override suspend fun destination(): FieldReportDestination? = destinationToReturn

    override suspend fun upload(request: FieldReportUploadRequest): FieldReportUploadResult {
        uploadCallCount++
        lastRequest = request
        return resultToReturn
    }
}
