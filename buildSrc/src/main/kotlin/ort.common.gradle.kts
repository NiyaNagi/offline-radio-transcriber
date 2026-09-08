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
//
// 3. (Root-caused a third time — the defensive exclude from (1) above was itself wrong, and it
//    was not defensive.) `isUnderClaudeDirectory` originally matched a bare absolute-path
//    substring: any file whose *whole* path contained a `.claude` segment, anywhere. But every
//    worktree-isolated builder's own checkout root **is itself** `.../.claude/worktrees/<name>/`
//    — `spec/ui-conformance-plan.md`'s own "Concurrency and safety" section, quoted at the top of
//    this file, says so directly. An absolute-path substring match against `.claude` therefore
//    matched *every* real source file in *every* checkout doing the building, not only a sibling
//    worktree's files nested inside it — `:app:detekt` and every `:app:*ktlint*` task silently
//    saw zero input files for every builder (`NO-SOURCE`, confirmed with a fresh daemon and
//    `--rerun-tasks`, ruling out a stale daemon or the build cache), and a task with no inputs
//    reports success vacuously — not because there was nothing to flag. Every "clean" ktlint/
//    detekt result from any worktree since (1) landed was this, not a real check.
//    Fixed by relativizing each file against **this build's own** `rootProject.projectDir` before
//    testing for a `.claude` segment: a file under this checkout's own module directories
//    (`app/src/main/kotlin/...`) relativizes to a path with no `.claude` segment in it at all —
//    the `.claude/worktrees/<name>/` prefix is exactly the part `relativeTo` strips away, since
//    it is the base being relativized against, not part of the result — while a file actually
//    nested *inside* this checkout under a `.claude` directory (a stray sibling-worktree artifact,
//    or the throwaway proof file (1)'s own comment describes) still relativizes to a path that
//    does contain one, and is still excluded. Proven both ways as part of landing this fix — see
//    this package's own `CHANGELOG.md` entry for the exact commands and file counts.
private fun isUnderClaudeDirectory(file: File): Boolean {
    val relativePath = file.relativeToOrNull(rootProject.projectDir)?.path ?: return false
    return relativePath.contains("${File.separator}.claude${File.separator}") ||
        relativePath.startsWith(".claude${File.separator}")
}

// Matched against each `FileTreeElement`'s own file, relativized against **this build's own**
// root project directory (not a `PatternFilterable` glob, and not the file's bare absolute path —
// see (3) above for why either of those gets this wrong) precisely so the same predicate reads
// correctly regardless of where on disk *this* checkout happens to sit, including when — as every
// worktree-isolated builder's checkout does — that location is itself nested under
// `.claude/worktrees/<name>/`.
private fun excludeClaudeDirectory(element: FileTreeElement): Boolean = isUnderClaudeDirectory(element.file)

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
