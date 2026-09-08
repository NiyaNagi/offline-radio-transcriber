import io.gitlab.arturbosch.detekt.Detekt
import org.gradle.api.file.FileTreeElement
import org.gradle.api.tasks.testing.Test
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import java.io.File

plugins {
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
}

// Concurrency and safety (spec/ui-conformance-plan.md): every builder runs in `isolation:
// worktree`, and worktrees live under `.claude/worktrees/<name>/` — a plain directory on disk
// under this project's own root, not somewhere git hides it from a filesystem walk.
//
// **Root-caused twice now — the two failure modes are different, and only one of them is a file-
// resolution bug:**
//
// 1. (Fixed earlier, and still real.) A worktree mid-edit under `.claude/` can contain another
//    agent's uncommitted, possibly unparseable file. `AGENT_DIRECTORY_EXCLUDE` below belt-and-
//    braces both plugins against ever reading one, in case a future plugin version (or a source
//    set this project adds later) resolves its file set more broadly than the Kotlin plugin's own
//    `SourceDirectorySet` — proven *not* to be today's behaviour: `:app`'s `runKtlintCheckOver*`
//    tasks build their `source` `FileCollection` from the declared per-module source directories
//    only (verified directly — a throwaway `.kt` planted at
//    `.claude/worktrees/zz-test/app/src/{main,test}/kotlin/**` never appears in that collection,
//    for either source set), so this exclude is defensive, not the fix for what follows.
//
// 2. (The actual cause of "`:app:ktlintTestSourceSetCheck` failed on a *different* worktree's
//    file".) `org.gradle.caching=true` (gradle.properties) turns on Gradle's **local build
//    cache**, rooted at `$GRADLE_USER_HOME` — one directory shared by every worktree on this
//    machine, not one per checkout. `KtLintCheckTask`/`Detekt` are cacheable, and their cache KEY
//    is a content hash of the (correctly, narrowly scoped) input files — so two worktrees whose
//    `app/src/test/kotlin/**` happens to hash identically (routine right after a merge, before an
//    agent has touched the files it owns) get the *same* cache key. Gradle then legitimately
//    serves one worktree's cached task OUTPUT to the other — and that output is a serialized
//    violation report whose entries carry each finding's **absolute file path as data**, not just
//    as build metadata a relocated cache entry could safely disregard. The result: build A, having
//    never touched the affected files itself, reports violations at build B's absolute paths,
//    because the cached artifact is not actually relocatable the way Gradle's cache model assumes.
//    Confirmed directly: `:app:runKtlintCheckOverTestSourceSet`'s own declared `source` was already
//    proven correctly scoped (0 foreign files) at the moment its *cached* result nonetheless
//    reported another worktree's `LevelCheckTest.kt`/`LevelScreenTest.kt`.
//
//    `settings.gradle.kts` (a per-worktree local cache directory) and `gradle.properties` (turning
//    caching off outright) are both outside this fix's file scope, so the fix lives here instead,
//    in the one convention plugin every module applies: these specific task types are told never
//    to participate in the build cache at all — `doNotCacheIf`, not `cacheIf { false }`, so the
//    *reason* is visible in `--info` output and in `taskOutcome` diagnostics, and so a future,
//    genuinely relocatable version of either plugin only has to remove this block, not rediscover
//    why it exists.
private fun isUnderClaudeDirectory(path: String): Boolean = path.contains("${File.separator}.claude${File.separator}")

// Matched against each `FileTreeElement`'s own absolute `file.path` (not a `PatternFilterable`
// glob) precisely because a glob is evaluated *relative to whichever base directory the plugin
// chose* — `.claude` is an ancestor of every module's real source directory, never a descendant of
// it, so a relative `**/.claude/**` pattern can never match there even when a plugin's file
// resolution does reach outside the module (verified: it does not, for ktlint, today — see above).
// An absolute-path predicate matches regardless of which base directory a plugin measures from.
private fun excludeClaudeDirectory(element: FileTreeElement): Boolean = isUnderClaudeDirectory(element.file.path)

extensions.configure<KtlintExtension> {
    version.set("1.3.1")
    android.set(true)
    filter {
        exclude { excludeClaudeDirectory(it) }
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    ignoreFailures = false
}

tasks.withType<Detekt>().configureEach {
    exclude { excludeClaudeDirectory(it) }
    outputs.doNotCacheIf(
        "detekt's cached report embeds each finding's absolute file path as data; a build-cache " +
            "hit from a different worktree under .claude/worktrees/ would report that worktree's " +
            "paths as this build's own findings (see ort.common.gradle.kts's own doc comment)",
    ) { true }
}

// The ktlint Gradle plugin's task types are `internal` (not part of its public API), so they are
// matched by name rather than `tasks.withType<...>()` — every "check"/"format" task it registers,
// per source set and per module, has "ktlint" or "Ktlint" somewhere in its name (confirmed against
// this build's own `:app:tasks --all` output: `ktlintMainSourceSetCheck`,
// `runKtlintCheckOverTestSourceSet`, `ktlintFormat`, ...). Both the per-source-set worker tasks
// (whose cached report is the actual carrier of the leaked absolute paths) and the aggregating
// `ktlint<Variant>SourceSetCheck` tasks are covered the same way, since either could cache and
// replay another worktree's output.
tasks.configureEach {
    if (name.contains("ktlint", ignoreCase = true)) {
        outputs.doNotCacheIf(
            "ktlint's cached report embeds each finding's absolute file path as data; a build-cache " +
                "hit from a different worktree under .claude/worktrees/ would report that worktree's " +
                "paths as this build's own findings (see ort.common.gradle.kts's own doc comment)",
        ) { true }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
