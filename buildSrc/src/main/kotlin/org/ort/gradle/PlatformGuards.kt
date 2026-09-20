package org.ort.gradle

/**
 * Pure logic behind the `platformGuards` task (audit F-027). Kept separate from Gradle types so
 * it can be unit-tested, same pattern as [ModuleGraph] and [CoverageMatrix].
 *
 * Most checks here are a **declared-artifact** proxy — a dependency coordinate or a manifest
 * string — never a runtime observation. None of them proves a build made no network call, or
 * that no telemetry SDK actually reported anything; they prove only that the *building blocks*
 * for doing so are (or are not) present in the source tree. Say so wherever a result is reported
 * (constitution VI — never claim more than a number's provenance supports). Three exceptions read
 * a real build artifact instead of a declared one: [missingNativeLibraryViolations] (R-1001, the
 * packaged APK's own zip entries), [bundledAssetPackagingViolations] (P24 fix, FR-AST-13, the same
 * zip entries checked for a different property), and [signingStabilityViolations] (debug-fix
 * session 2026-09-19, the packaged APK's own signer certificate) — see each one's own KDoc for why
 * a declared coordinate cannot catch what they catch.
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
    data class BundledAssetPackagingViolation(val path: String, val reason: String)

    /** Every packaged-APK entry this file's bundled-asset guard treats as "a bundled model asset
     * shipped" — matches where [FetchBundledAssetsTask] actually writes inside the APK
     * (`assets/<destination>`, and `assets/bundled/manifest.json` alongside it), regardless of
     * which flavor's own source set the entry came from at build time. */
    const val BUNDLED_ASSETS_APK_PREFIX: String = "assets/bundled/"

    /** R-1001's own required set — kept as defaults here so [NativeLibraryPackagingGuardTask] and
     * this file's tests share one definition of "what WPJ ships" rather than each hardcoding it. */
    val REQUIRED_NATIVE_LIBRARY_ABIS: List<String> = listOf("arm64-v8a", "x86_64")
    val REQUIRED_NATIVE_LIBRARY_FILES: List<String> = listOf("libsherpa-onnx-jni.so", "libonnxruntime.so")

    /** P23 (Play-readiness): every `FOREGROUND_SERVICE_<TYPE>` permission this project declares
     * anywhere, mapped to the `android:foregroundServiceType` value Play expects a declaring
     * `<service>` to carry — see [fgsTypeDeclarationViolations]'s own KDoc and
     * `docs/fgs-type-declaration.md`, which this map must stay in sync with. */
    val FGS_PERMISSION_TO_TYPE: Map<String, String> = mapOf(
        "android.permission.FOREGROUND_SERVICE_MICROPHONE" to "microphone",
        "android.permission.FOREGROUND_SERVICE_DATA_SYNC" to "dataSync",
    )

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
     * P23 (Play-readiness, `docs/fgs-type-declaration.md`): a manifest that declares a
     * `FOREGROUND_SERVICE_<TYPE>` permission ([FGS_PERMISSION_TO_TYPE]) SHALL also carry at least
     * one `<service>` with a matching `android:foregroundServiceType` — Play's own review reads
     * the permission and the declared type together, and a permission with no matching type is
     * exactly the kind of mismatch a store listing gets rejected for. Declared-artifact only, like
     * every other check in this file (this file's own class KDoc): it proves the manifest names
     * agree, not that the service is ever actually started with that type at runtime —
     * `:capture-android`'s own `CaptureServiceTest` already covers that narrower, real claim for
     * the one service this project ships today.
     */
    fun fgsTypeDeclarationViolations(manifestTextByModule: Map<String, String>): List<ManifestViolation> =
        manifestTextByModule.flatMap { (module, text) ->
            FGS_PERMISSION_TO_TYPE.entries.mapNotNull { (permission, type) ->
                if (!text.contains(permission)) return@mapNotNull null
                val typeDeclared = Regex(
                    "android:foregroundServiceType\\s*=\\s*\"[^\"]*\\b${Regex.escape(type)}\\b[^\"]*\"",
                ).containsMatchIn(text)
                if (typeDeclared) {
                    null
                } else {
                    ManifestViolation(
                        module,
                        "declares $permission but no <service> carries " +
                            "android:foregroundServiceType=\"$type\" (Play FGS type declaration, " +
                            "docs/fgs-type-declaration.md)",
                    )
                }
            }
        }.sortedWith(compareBy({ it.module }, { it.reason }))

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
     * [SigningStabilityGuardTask]'s own KDoc) ruled out the artifact being literally malformed —
     * the published bytes, hash-verified against the release asset, install cleanly via both
     * `adb install` and `pm install` on three real Android package-manager instances, including
     * genuine Android 16 (API 36) at 16 KB page size. What *is* real: `.github/workflows/
     * release.yml` used to sign with AGP's own freshly auto-generated `~/.android/debug.keystore`
     * on every CI run — confirmed directly by downloading two different published releases
     * (`v0.1.1` and a `latest-build`) and finding two different certificate SHA-256 digests for
     * the same `org.ort.app` package, and by resigning a published APK's own bytes with a second
     * key and reproducing `INSTALL_FAILED_UPDATE_INCOMPATIBLE` installing it over the first on a
     * real device. Stock Android's own Package Installer does not give that failure a distinct
     * message on every OS/OEM build; it is well documented to fall back to the same generic
     * "Package appears to be invalid" text INSTALL_FAILED_INVALID_APK/INSTALL_PARSE_FAILED_*
     * produce — indistinguishable to an operator from a genuinely corrupt APK, which is why this
     * was reported and investigated as one.
     *
     * **A first version of this fix pinned a constant here, computed from a keystore checked into
     * the repository.** Rejected on review: this repository is public, and a published private key
     * would let anyone sign an APK Android accepts as an update to the real one. There is
     * deliberately no constant in this file any more — [expectedCertificateSha256] is a plain
     * parameter with no source-code default, supplied by the caller
     * ([SigningStabilityGuardTask], wired in `ort.android-app.gradle.kts`) from a checked-in digest
     * file (`buildSrc/signing/release-certificate.sha256`, starting at the placeholder `UNSET`) or
     * the `ortReleaseCertificateSha256` Gradle property — see RELEASING.md's "Signing" section for
     * exactly what the operator generates and pastes in once the real key exists.
     *
     * [enforceExpectedCertificate] keeps this guard from failing a plain local `assembleFullDebug`,
     * which AGP signs with its own per-machine debug key that has no reason to match the pin: only
     * `release.yml`, right after building with the injected release-signing secrets, passes
     * `-PortEnforcePinnedReleaseSigning=true`. The `verified`/`hasV2OrV3Scheme` checks below are
     * NOT gated by it — a build that ships unsigned or v1-only is a real defect in any context.
     */
    fun signingStabilityViolations(
        verified: Boolean,
        hasV2OrV3Scheme: Boolean,
        certificateSha256: String?,
        expectedCertificateSha256: String?,
        enforceExpectedCertificate: Boolean,
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
        if (enforceExpectedCertificate) {
            when {
                expectedCertificateSha256.isNullOrBlank() ->
                    violations += SigningViolation(
                        "pinned certificate enforcement was requested (-PortEnforcePinnedReleaseSigning=true) " +
                            "but no digest is configured — paste the real release keystore's certificate " +
                            "SHA-256 digest into buildSrc/signing/release-certificate.sha256, replacing " +
                            "UNSET (RELEASING.md's \"Signing\" section has the exact steps)",
                    )
                certificateSha256 == null ->
                    violations += SigningViolation("no signer certificate could be read from the packaged APK")
                !certificateSha256.equals(expectedCertificateSha256, ignoreCase = true) ->
                    violations += SigningViolation(
                        "signing certificate is $certificateSha256, pinned is $expectedCertificateSha256 — " +
                            "every published artifact must share one certificate (RELEASING.md) or an " +
                            "operator updating from a previously installed build hits " +
                            "INSTALL_FAILED_UPDATE_INCOMPATIBLE, which the on-device installer shows as " +
                            "\"Package appears to be invalid\" rather than as a signature-mismatch message " +
                            "(register, debug-fix session 2026-09-19)",
                    )
            }
        }
        return violations
    }

    /**
     * P24 fix (register, Wave G batch gate, FR-AST-13, AC-190): the boundary check proving the
     * defect this fix closes stays closed — like [missingNativeLibraryViolations], this reads a
     * real packaged APK's entry paths rather than a declared coordinate, because the defect (the
     * `play` flavor packaging the identical 628 MB `full` does) is invisible to every
     * declared-artifact check in this file: nothing about `bundled-assets.json` or the flavor's own
     * `build.gradle.kts` config says which physical directory the fetch task actually wrote to.
     *
     * [expectBundled] is `true` for the `full` variant (FR-AST-3 — every asset ships inside the
     * installed artifact, so at least one `assets/bundled/` entry must be present; zero would mean
     * `fetchBundledAssets` never ran or its output never reached the APK) and `false` for `play`
     * (FR-AST-13 — nothing named in the manifest may ship; every model downloads during setup
     * instead, AC-190). Reports every offending entry by name so a regression is diagnosable from
     * the failure message alone, the same discipline [missingNativeLibraryViolations] follows.
     */
    fun bundledAssetPackagingViolations(
        apkEntryPaths: Set<String>,
        expectBundled: Boolean,
    ): List<BundledAssetPackagingViolation> {
        val bundledEntries = apkEntryPaths.filter { it.startsWith(BUNDLED_ASSETS_APK_PREFIX) }
        return if (expectBundled) {
            if (bundledEntries.isEmpty()) {
                listOf(
                    BundledAssetPackagingViolation(
                        BUNDLED_ASSETS_APK_PREFIX,
                        "the full variant's packaged APK contains no $BUNDLED_ASSETS_APK_PREFIX entries — " +
                            "fetchBundledAssets did not run, or its output never reached the APK (FR-AST-3)",
                    ),
                )
            } else {
                emptyList()
            }
        } else {
            bundledEntries.sorted().map { path ->
                BundledAssetPackagingViolation(
                    path,
                    "the play variant's packaged APK must not bundle any model asset, but contains $path — " +
                        "every model must download during setup instead (FR-AST-13, AC-190)",
                )
            }
        }
    }
}
