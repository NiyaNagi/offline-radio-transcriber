package org.ort.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * Fails the build if any module declares a compile dependency the design (technical design §2)
 * does not permit. Wired up in the root build script from the evaluated project graph.
 */
abstract class DependencyRulesTask : DefaultTask() {

    @get:Input
    abstract val actualGraph: MapProperty<String, List<String>>

    @TaskAction
    fun check() {
        val actual = actualGraph.get().mapValues { it.value.toSet() }
        val violations = ModuleGraph.violations(actual)

        val checked = actual.entries.sortedBy { it.key }
            .joinToString("\n") { (m, deps) ->
                "  $m -> ${if (deps.isEmpty()) "(none)" else deps.sorted().joinToString(", ")}"
            }
        logger.lifecycle("dependencyRules: checked ${actual.size} modules\n$checked")

        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Forbidden module dependency edge(s) — see technical design §2:")
                    violations.forEach { appendLine(it.toString()) }
                    appendLine()
                    appendLine("The dependency graph is enforced by the build, not by review (constitution VII).")
                },
            )
        }
        logger.lifecycle("dependencyRules: OK — every edge is permitted by the design graph.")
    }
}
