package org.ort.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * Audit F-027 — fails the build on a dependency coordinate or manifest declaration the design
 * does not permit, structurally, the same way [DependencyRulesTask] enforces the module graph
 * (constitution VII). See [PlatformGuards]'s KDoc for exactly what each check does and does not
 * prove: a declared coordinate or a manifest string, never observed runtime behaviour.
 */
abstract class PlatformGuardsTask : DefaultTask() {

    @get:Input
    abstract val externalDependencies: MapProperty<String, List<String>>

    @get:Input
    abstract val manifestTexts: MapProperty<String, String>

    @TaskAction
    fun check() {
        val deps = externalDependencies.get().mapValues { it.value.toSet() }
        val manifests = manifestTexts.get()

        val telemetry = PlatformGuards.telemetryViolations(deps)
        val httpClient = PlatformGuards.httpClientViolations(deps)
        val internet = PlatformGuards.internetPermissionViolations(manifests)

        logger.lifecycle(
            "platformGuards: checked ${deps.size} modules' external dependencies and " +
                "${manifests.size} manifests — no analytics/telemetry SDK, no HTTP client outside :net, " +
                "no android.permission.INTERNET declared outside :net (FR-OBS-5, NFR-6, AC-59).",
        )

        if (telemetry.isNotEmpty() || httpClient.isNotEmpty() || internet.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Platform guard violation(s) — audit F-027:")
                    telemetry.forEach { appendLine("  ${it.module} -> ${it.coordinate}   (${it.reason})") }
                    httpClient.forEach { appendLine("  ${it.module} -> ${it.coordinate}   (${it.reason})") }
                    internet.forEach { appendLine("  ${it.module}   (${it.reason})") }
                    appendLine()
                    appendLine(
                        "This is a declared-artifact check, not a runtime traffic capture — it proves " +
                            "nothing about what a build actually sent, only what it could send.",
                    )
                },
            )
        }
        logger.lifecycle("platformGuards: OK.")
    }
}
