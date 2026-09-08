import org.ort.gradle.CoverageMatrixCheckTask
import org.ort.gradle.CoverageMatrixTask
import org.ort.gradle.DependencyRulesTask
import org.ort.gradle.PlatformGuardsTask
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.artifacts.ProjectDependency

// Plugin versions come from buildSrc/build.gradle.kts, which puts AGP and the Kotlin plugin
// on every project's classpath. Modules apply them by id (no version) via the `ort.*`
// convention plugins, so there is deliberately no `plugins {}` block here.

/**
 * `dependencyRules` — the structural enforcement of technical design §2. Wired from the
 * evaluated project graph so the task input is a plain map (config-cache friendly).
 */
val dependencyRules = tasks.register<DependencyRulesTask>("dependencyRules") {
    group = "verification"
    description = "Fails the build on any forbidden module dependency edge (technical design §2)."
}

val checkedConfigurations = setOf("api", "implementation", "compileOnly")

/**
 * `platformGuards` (audit F-027) — no analytics/telemetry/crash-reporting dependency anywhere
 * (FR-OBS-5), no HTTP client dependency outside `:net` (NFR-6, constitution V), no
 * `android.permission.INTERNET` declared outside `:net`'s manifest (AC-59, NFR-6). A declared-
 * artifact check, not a runtime traffic capture — see PlatformGuards's KDoc.
 */
val platformGuards = tasks.register<PlatformGuardsTask>("platformGuards") {
    group = "verification"
    description = "Fails the build on a disallowed dependency coordinate or manifest permission (audit F-027)."
}

gradle.projectsEvaluated {
    val actual: Map<String, List<String>> = subprojects.associate { sp ->
        val deps: List<String> = sp.configurations
            .filter { it.name in checkedConfigurations }
            .flatMap { cfg -> cfg.dependencies.filterIsInstance<ProjectDependency>() }
            .map { dep -> dep.dependencyProject.path }
            .toSortedSet()
            .toList()
        sp.path to deps
    }
    dependencyRules.configure { actualGraph.set(actual) }

    val externalDeps: Map<String, List<String>> = subprojects.associate { sp ->
        val coords: List<String> = sp.configurations
            .filter { it.name in checkedConfigurations }
            .flatMap { cfg -> cfg.dependencies.filterIsInstance<ExternalDependency>() }
            .map { dep -> "${dep.group}:${dep.name}:${dep.version}" }
            .toSortedSet()
            .toList()
        sp.path to coords
    }
    val manifests: Map<String, String> = subprojects.associate { sp ->
        val manifest = sp.projectDir.resolve("src/main/AndroidManifest.xml")
        sp.path to (if (manifest.isFile) manifest.readText() else "")
    }
    platformGuards.configure {
        externalDependencies.set(externalDeps)
        manifestTexts.set(manifests)
    }
}

/**
 * Test source roots the coverage matrix and its staleness check both scan (test-plan §9).
 * `corpus/tests` is Python (`corpus/` has its own `pyproject.toml` and CI job — AGENTS.md), not a
 * Gradle subproject, so it is added alongside the per-module Kotlin roots rather than discovered
 * from `subprojects`. A `val` shared by every task that needs the same roots (F-027).
 */
val coverageMatrixTestRoots =
    subprojects.flatMap {
        listOf(
            it.layout.projectDirectory.dir("src/test/kotlin"),
            it.layout.projectDirectory.dir("src/androidTest/kotlin"),
        )
    } + listOf(
        // audit F-027: buildSrc's own meta-guard tests (PlatformGuardsTest, ModuleGraphTest,
        // CoverageMatrixTest) establish requirement ids too (e.g. AC-59, FR-OBS-5, NFR-6) but
        // buildSrc is a separate included build, not a member of `subprojects` — without this
        // it is silently invisible to the matrix regardless of what its tests actually prove.
        layout.projectDirectory.dir("buildSrc/src/test/kotlin"),
        layout.projectDirectory.dir("corpus/tests"),
    )

/** `coverageMatrix` — regenerates results/coverage-matrix.md (test-plan §9). */
tasks.register<CoverageMatrixTask>("coverageMatrix") {
    group = "documentation"
    description = "Regenerates results/coverage-matrix.md from spec ids and test sources."
    specDir.set(layout.projectDirectory.dir("spec"))
    testRoots.setFrom(coverageMatrixTestRoots)
    output.set(layout.projectDirectory.file("results/coverage-matrix.md"))
}

/**
 * `coverageMatrixCheck` — F-014: fails if the committed results/coverage-matrix.md differs
 * from what `coverageMatrix` would generate right now, ignoring trailing-newline/line-ending
 * differences. CI runs this as a gate; `coverageMatrix` itself only regenerates.
 */
tasks.register<CoverageMatrixCheckTask>("coverageMatrixCheck") {
    group = "verification"
    description = "Fails if results/coverage-matrix.md is stale (run coverageMatrix to fix)."
    specDir.set(layout.projectDirectory.dir("spec"))
    testRoots.setFrom(coverageMatrixTestRoots)
    committed.set(layout.projectDirectory.file("results/coverage-matrix.md"))
}

/** A root `check` that also runs the meta-guards, so CI's lint job is one invocation. */
tasks.register("check") {
    group = "verification"
    dependsOn(dependencyRules)
    dependsOn(platformGuards)
    dependsOn(subprojects.map { "${it.path}:check" })
}
