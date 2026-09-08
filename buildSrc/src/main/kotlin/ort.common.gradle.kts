import io.gitlab.arturbosch.detekt.Detekt
import org.gradle.api.tasks.testing.Test
import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
}

// Concurrency and safety (spec/ui-conformance-plan.md): every builder runs in `isolation:
// worktree`, and worktrees live under `.claude/worktrees/<name>/` — a plain directory on disk
// under this project's own root, not somewhere git hides it from a filesystem walk. ktlint's and
// detekt's Gradle plugins both, by default, resolve their file set for a module as *every* `.kt`
// file the JVM finds walking down from that module's source directories, not the precise set the
// Kotlin plugin's `SourceDirectorySet` declares — so a worktree mid-edit under `.claude/` (another
// agent's uncommitted, possibly unparseable file) fails the *main checkout's* lint/analysis, not
// just that agent's own. Found in practice: `:app:runKtlintCheckOverMainSourceSet` failed on
// `…\.claude\worktrees\agent-…\app\src\main\kotlin\org\ort\app\ui\ReaderActivity.kt` — a different
// worktree's copy of a file this module already lints from its own, real source directory.
// Excluded once here, in the one convention plugin every module applies, rather than per module.
private val CLAUDE_WORKTREES_EXCLUDE = "**/.claude/**"

extensions.configure<KtlintExtension> {
    version.set("1.3.1")
    android.set(true)
    filter {
        exclude(CLAUDE_WORKTREES_EXCLUDE)
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    ignoreFailures = false
}

tasks.withType<Detekt>().configureEach {
    exclude(CLAUDE_WORKTREES_EXCLUDE)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
