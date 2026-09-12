package org.ort.gradle

/**
 * Pure logic behind the `platformGuards` task (audit F-027). Kept separate from Gradle types so
 * it can be unit-tested, same pattern as [ModuleGraph] and [CoverageMatrix].
 *
 * Every check here is a **declared-artifact** proxy — a dependency coordinate or a manifest
 * string — never a runtime observation. None of them proves a build made no network call, or
 * that no telemetry SDK actually reported anything; they prove only that the *building blocks*
 * for doing so are (or are not) present in the source tree. Say so wherever a result is reported
 * (constitution VI — never claim more than a number's provenance supports).
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
}
