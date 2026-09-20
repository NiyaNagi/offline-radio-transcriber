package org.ort.gradle

/**
 * Pure logic behind the `platformGuards` task (audit F-027). Kept separate from Gradle types so
 * it can be unit-tested, same pattern as [ModuleGraph] and [CoverageMatrix].
 *
 * Most checks here are a **declared-artifact** proxy — a dependency coordinate or a manifest
 * string — never a runtime observation. None of them proves a build made no network call, or
 * that no telemetry SDK actually reported anything; they prove only that the *building blocks*
 * for doing so are (or are not) present in the source tree. Say so wherever a result is reported
 * (constitution VI — never claim more than a number's provenance supports). Two exceptions read a
 * real build artifact instead of a declared one: [missingNativeLibraryViolations] (R-1001, the
 * packaged APK's own zip entries) and [signingStabilityViolations] (debug-fix session 2026-09-19,
 * the packaged APK's own signer certificate) — see each one's own KDoc for why a declared
 * coordinate cannot catch what they catch.
 */
object PlatformGuards {

    /** Substrings of a `group:artifact` coordinate that mark a telemetry/analytics/crash SDK (FR-OBS-5). */
    val TELEMETRY_COORDINATE_MARKERS: List<String> = listOf(
        "firebase", "crashlytics", "sentry", "bugsnag", "amplitude", "mixpanel",
    )

    /** Substrings of a `group:artifact` coordinate that mark an HTTP client library (constitution V, NFR-6). */
    val HTTP_CLIENT_COORDINATE_MARKERS: List<String> = listOf(
        "okhttp", "retrofit", "ktor-client", "volley", "httpclient", "httpcomponents", "cronet",
    )

    data class DependencyViolation(val module: String, val coordinate: String, val reason: String)
    data class ManifestViolation(val module: String, val reason: String)
    data class NativeLibraryViolation(val path: String, val reason: String)
    data class SigningViolation(val reason: String)

    /** R-1001's own required set — kept as defaults here so [NativeLibraryPackagingGuardTask] and
     * this file's tests share one definition of "what WPJ ships" rather than each hardcoding it. */
    val REQUIRED_NATIVE_LIBRARY_ABIS: List<String> = listOf("arm64-v8a", "x86_64")
    val REQUIRED_NATIVE_LIBRARY_FILES: List<String> = listOf("libsherpa-onnx-jni.so", "libonnxruntime.so")

    /** FR-OBS-5 — no analytics/telemetry/crash-reporting dependency in any module, ever. */
    fun telemetryViolations(dependenciesByModule: Map<String, Set<String>>): List<DependencyViolation> =
        dependenciesByModule.flatMap { (module, coordinates) ->
            coordinates.filter { coordinate -> TELEMETRY_COORDINATE_MARKERS.any { coordinate.contains(it, ignoreCase = true) } }
                .map { coordinate ->
                    DependencyViolation(module, coordinate, "telemetry/analytics/crash-reporting dependency (FR-OBS-5)")
                }
        }.sortedWith(compareBy({ it.module }, { it.coordinate }))

    /** Constitution V / NFR-6 — only [allowedModule] may link an HTTP client. */
    fun httpClientViolations(
        dependenciesByModule: Map<String, Set<String>>,
        allowedModule: String = ":net",
    ): List<DependencyViolation> =
        dependenciesByModule.filterKeys { it != allowedModule }.flatMap { (module, coordinates) ->
            coordinates.filter { coordinate -> HTTP_CLIENT_COORDINATE_MARKERS.any { coordinate.contains(it, ignoreCase = true) } }
                .map { coordinate ->
                    DependencyViolation(module, coordinate, "HTTP client dependency outside $allowedModule (NFR-6, constitution V)")
                }
        }.sortedWith(compareBy({ it.module }, { it.coordinate }))

    /** AC-59 / NFR-6 — only [allowedModule]'s manifest may declare `android.permission.INTERNET`. */
    fun internetPermissionViolations(
        manifestTextByModule: Map<String, String>,
        allowedModule: String = ":net",
    ): List<ManifestViolation> =
        manifestTextByModule.filterKeys { it != allowedModule }
            .filterValues { it.contains("android.permission.INTERNET") }
            .map { (module, _) ->
                ManifestViolation(module, "declares android.permission.INTERNET outside $allowedModule (AC-59, NFR-6)")
            }
            .sortedBy { it.module }

    /**
     * Audit F-008 — exclusivity is not the whole requirement: constitution V names a
     * user-initiated download as one of exactly two declared outbound channels, so the channel
     * MUST exist, not merely be the only one that could. [internetPermissionViolations] alone is
     * satisfied vacuously if nobody, including `:net`, declares the permission; this catches
     * that case, which would otherwise leave `ModelAcquisition.fetch()` failing at runtime with
     * no build-time signal at all.
     */
    fun missingInternetPermissionViolations(
        manifestTextByModule: Map<String, String>,
        requiredModule: String = ":net",
    ): List<ManifestViolation> {
        val declares = manifestTextByModule[requiredModule]?.contains("android.permission.INTERNET") ?: false
        return if (declares) {
            emptyList()
        } else {
            listOf(
                ManifestViolation(
                    requiredModule,
                    "does not declare android.permission.INTERNET — the declared channel must exist (constitution V, F-008)",
                ),
            )
        }
    }

    /**
     * R-1001 (register — every real transmission failed Pass B with `dlopen failed: library
     * "libsherpa-onnx-jni.so" not found`): **the one check in this file that reads a real build
     * artifact rather than a declared coordinate or manifest string.** Every other guard here is,
     * by this file's own class-level KDoc, a declared-artifact proxy — that is precisely why R-1001
     * was invisible to all of them: `sherpa-onnx-jvm` was declared, resolved and dexed correctly;
     * only the native `.so` files that JAR's `LibraryUtils.load()` calls
     * `System.loadLibrary("sherpa-onnx-jni")` for at runtime were never packaged into the APK at
     * all. A declared-coordinate check cannot catch a missing *build-time-fetched* artifact — there
     * is no coordinate to inspect — so this one is stronger: it takes the actual set of entry paths
     * a real packaged APK contains (`NativeLibraryPackagingGuardTask` reads them with
     * `java.util.zip.ZipFile`, wired from `ort.android-app.gradle.kts` after `assembleDebug`, since
     * this project's `dependencyRules`/`platformGuards` root tasks run before any APK exists to
     * inspect — see that task's own KDoc) and reports every `lib/<abi>/<file>` this project ships
     * (`REQUIRED_NATIVE_LIBRARY_ABIS` x `REQUIRED_NATIVE_LIBRARY_FILES`, kept in sync with
     * `sherpa-native.json`) that the APK does not actually contain.
     */
    fun missingNativeLibraryViolations(
        apkEntryPaths: Set<String>,
        requiredAbis: List<String> = REQUIRED_NATIVE_LIBRARY_ABIS,
        requiredFiles: List<String> = REQUIRED_NATIVE_LIBRARY_FILES,
    ): List<NativeLibraryViolation> =
        requiredAbis.flatMap { abi ->
            requiredFiles.map { file -> "lib/$abi/$file" }
        }.filter { path -> path !in apkEntryPaths }
            .map { path ->
                NativeLibraryViolation(
                    path,
                    "packaged APK does not contain $path — sherpa-onnx's Android JNI binding will " +
                        "fail to load at runtime (register R-1001)",
                )
            }
            .sortedBy { it.path }

    /**
     * Debug-fix session (2026-09-19) — operator report on the currently released build: install
     * fails on-device with "App not installed. Package appears to be invalid." Reproduction (see
     * this constant's own KDoc and [SigningStabilityGuardTask]'s) ruled out the artifact being
     * literally malformed — the published bytes, hash-verified against the release asset, install
     * cleanly via both `adb install` and `pm install` on three real Android package-manager
     * instances, including genuine Android 16 (API 36) at 16 KB page size. What *is* real: `.github/
     * workflows/release.yml` never pins a signing key, so every CI run signs with AGP's own
     * freshly auto-generated `~/.android/debug.keystore` — confirmed directly by downloading two
     * different published releases (`v0.1.1` and the current `latest-build`) and finding two
     * different certificate SHA-256 digests for the same `org.ort.app` package, and by resigning
     * the published APK's own bytes with a second key and reproducing
     * `INSTALL_FAILED_UPDATE_INCOMPATIBLE` installing it over the first on a real device. Stock
     * Android's own Package Installer does not give that failure a distinct message on every OS/
     * OEM build; it is well documented to fall back to the same generic "Package appears to be
     * invalid" text INSTALL_FAILED_INVALID_APK/INSTALL_PARSE_FAILED_* produce — indistinguishable
     * to an operator from a genuinely corrupt APK, which is why this was reported and investigated
     * as one.
     *
     * The value below is this project's chosen fix: a stable, checked-in keystore
     * (`buildSrc/signing/ort-rolling-release.keystore`, wired as the `debug` build type's
     * `signingConfig` in `ort.android-app.gradle.kts`) that every rolling and tagged build signs
     * with from now on, so an operator can always update in place. This constant pins the
     * certificate that keystore produces so a future accidental change (the keystore file deleted,
     * replaced, or the signing config pointed elsewhere) fails the build loudly — see
     * [SigningStabilityGuardTask] — rather than silently shipping an update-incompatible artifact
     * again. Computed with `apksigner sign` against that keystore then `apksigner verify
     * --print-certs` (its "Signer #1 certificate SHA-256 digest" line), the same figure
     * [SigningStabilityGuardTask] computes from the real assembled APK via apksig.
     */
    const val PINNED_ROLLING_RELEASE_CERTIFICATE_SHA256: String =
        "16a71e4ef0ed98ff4a594fbfef149798b03451d5a241d66053cc7a88bb42bdc7"

    /**
     * The decision logic behind [SigningStabilityGuardTask] — kept dependency-free (no apksig, no
     * Gradle types) so it is testable with plain booleans/strings, the same split this file's
     * every other check already uses between "read a real artifact" (the Task) and "decide what
     * that reading means" (this object).
     */
    fun signingStabilityViolations(
        verified: Boolean,
        hasV2OrV3Scheme: Boolean,
        certificateSha256: String?,
        expectedCertificateSha256: String = PINNED_ROLLING_RELEASE_CERTIFICATE_SHA256,
    ): List<SigningViolation> {
        val violations = mutableListOf<SigningViolation>()
        if (!verified) {
            violations += SigningViolation(
                "apksig could not verify the packaged APK's signature — it is not installably signed",
            )
        }
        if (!hasV2OrV3Scheme) {
            violations += SigningViolation(
                "no v2/v3 signature scheme present — minSdk 26 and Android 11+ require at least v2 " +
                    "to install",
            )
        }
        when {
            certificateSha256 == null ->
                violations += SigningViolation("no signer certificate could be read from the packaged APK")
            !certificateSha256.equals(expectedCertificateSha256, ignoreCase = true) ->
                violations += SigningViolation(
                    "signing certificate is $certificateSha256, pinned is $expectedCertificateSha256 — every " +
                        "rolling/tagged build must share one certificate (RELEASING.md) or an operator " +
                        "updating from a previously installed build hits INSTALL_FAILED_UPDATE_INCOMPATIBLE, " +
                        "which the on-device installer shows as \"Package appears to be invalid\" rather than " +
                        "as a signature-mismatch message (register, debug-fix session 2026-09-19)",
                )
        }
        return violations
    }
}
