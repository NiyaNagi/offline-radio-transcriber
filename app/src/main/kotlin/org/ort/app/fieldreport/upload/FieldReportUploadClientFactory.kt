package org.ort.app.fieldreport.upload

import org.ort.app.BuildConfig
import org.ort.core.fieldreport.FieldReportUploadClient
import org.ort.net.fieldreport.real.RealFieldReportUploadClient

/**
 * WPR3 (FR-OBS-11/FR-OBS-12): the one place `:app` decides whether a real field-report upload
 * channel exists in this build. `null` — never a fabricated client — whenever no real destination
 * is configured: a release build (`BuildConfig.FIELD_REPORT_TOKEN` is only ever non-blank in a
 * debug build, `buildSrc/.../ort.android-app.gradle.kts`) or a debug build with no
 * `ORT_FIELD_REPORT_TOKEN` set in the environment (`FetchBundledAssetsTask`'s own `HF_TOKEN`
 * pattern). `SettingsContent.kt`'s `FieldReportHost` treats a `null` client exactly as WPR2 left
 * it: `Send` stays unreachable and `canSend = false`.
 *
 * The one destination repository is not itself secret (D37: an operator-triggered path to a named
 * GitHub repository) so it is a plain literal here, unlike the token.
 *
 * [shouldCreate] is pulled out as a pure, package-visible function purely so the decision itself is
 * unit-testable without needing a real `BuildConfig` (a compile-time constant per variant, not
 * something a test can override) — [create] is the one place it is called against the real values.
 */
public object FieldReportUploadClientFactory {

    private const val DESTINATION_REPOSITORY = "NiyaNagi/offline-radio-transcriber"

    internal fun shouldCreate(debugBuild: Boolean, token: String?): Boolean = debugBuild && !token.isNullOrBlank()

    public fun create(): FieldReportUploadClient? {
        val token = BuildConfig.FIELD_REPORT_TOKEN
        if (!shouldCreate(BuildConfig.DEBUG, token)) return null
        return RealFieldReportUploadClient(repository = DESTINATION_REPOSITORY, token = token)
    }
}
