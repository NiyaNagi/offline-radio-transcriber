package org.ort.app.fieldreport.upload

import org.ort.app.BuildConfig
import org.ort.core.fieldreport.FieldReportUploadClient
import org.ort.net.fieldreport.real.RealFieldReportUploadClient

/**
 * WPR3 (FR-OBS-11/FR-OBS-12), reworked for D49/D55: the one place `:app` decides whether a real
 * field-report upload channel exists in this build. `null` — never a fabricated client — whenever
 * either half of the destination is not configured: no `ORT_FIELD_REPORT_TOKEN` in the environment
 * at build time, or no destination repository configured (`ORT_FIELD_REPORT_REPOSITORY`/
 * `-PortFieldReportRepository`, `buildSrc/.../ort.android-app.gradle.kts`) — the same "not
 * configured, never a silent no-op" contract `FetchBundledAssetsTask`'s own `HF_TOKEN` pattern
 * uses. `SettingsContent.kt`'s `FieldReportHost` treats a `null` client exactly as WPR2 left it:
 * `Send` stays unreachable and `canSend = false`.
 *
 * **No `debugBuild` gate any more, and no hardcoded destination.** D37's original design assumed
 * the channel would only ever exist in a debug build and named its one destination as a source
 * literal (a public GitHub repository, "not itself secret"); D55 replaces both assumptions — the
 * channel ships in every build now, and D49 requires the destination itself to be a private,
 * build-time configuration rather than a literal this file commits to. A build that has not
 * configured a destination refuses to upload (this function returns `null`); it never falls back
 * to any default repository, named or otherwise.
 *
 * [shouldCreate] is pulled out as a pure, package-visible function purely so the decision itself is
 * unit-testable without needing a real `BuildConfig` (a compile-time constant per variant, not
 * something a test can override) — [create] is the one place it is called against the real values.
 */
public object FieldReportUploadClientFactory {

    internal fun shouldCreate(token: String?, repository: String?): Boolean =
        !token.isNullOrBlank() && !repository.isNullOrBlank()

    public fun create(): FieldReportUploadClient? {
        val token = BuildConfig.FIELD_REPORT_TOKEN
        val repository = BuildConfig.FIELD_REPORT_REPOSITORY
        if (!shouldCreate(token, repository)) return null
        return RealFieldReportUploadClient(repository = repository, token = token)
    }
}
