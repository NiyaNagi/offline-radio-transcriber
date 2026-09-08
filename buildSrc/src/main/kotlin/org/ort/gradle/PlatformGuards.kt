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
}
