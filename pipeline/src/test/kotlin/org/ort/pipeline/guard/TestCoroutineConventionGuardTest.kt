package org.ort.pipeline.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ort.testing.Requirement
import java.io.File

/**
 * Register R-1180 (the sweep's open half), R-1189, R-1190: the repository-wide guard against the
 * two conventions those rows are about, plus the discriminating cases for the scanner itself.
 *
 * [TestCoroutineConventions]'s own KDoc carries the reasoning — including why this is a unit test
 * rather than the custom detekt rule the audit first asked for (this build hosts no custom rule set
 * and has no seam to host one), and exactly what each rule does and does not see.
 *
 * It reads the real committed test sources, the way `net`'s `NetManifestPermissionTest` reads the
 * real committed manifests: a regression fails a plain `./gradlew :pipeline:test` with no
 * build-script involvement at all. It lives in `:pipeline` because that is the module R-1190 and
 * R-1191 were filed against and the one this builder owns; nothing about it is pipeline-specific,
 * and moving it later costs a package rename.
 */
@Suppress("unused") // JUnit4 instantiates this reflectively; detekt cannot see that.
class TestCoroutineConventionGuardTest {

    /**
     * Files this change could not bring clean, with the exact number of findings each still has.
     *
     * **This is an inventory, not an amnesty.** Every entry is a real finding of the rule it is
     * listed under; none has been judged. They are here because they live in `:app` and `:rig`,
     * which other builders own — a guard that fails on code this change is not allowed to touch
     * cannot be merged at all, and shipping no guard would leave every future copy invisible.
     *
     * The count is pinned in both directions on purpose. Add a finding to one of these files and
     * the guard fails; annotate or fix one and the guard fails too, so the number can never quietly
     * stop describing the file. Delete the entry when its file reaches zero.
     *
     * Worth recording against R-1190, which says `ProseDigestRunnerTest` was "the **only** one of 16
     * flush call sites" not wrapped in `runBlocking`: it was not. The five below are the
     * `FieldReportRecorder.flush` half of that same check, which nobody counted — the same shape, in
     * `:app`, inside `runTest` bodies.
     */
    private val unreviewed: Map<String, Int> = mapOf(
        // R-1180 / Rule A — every one of these is an `UnconfinedTestDispatcher`-shaped test whose
        // timeout is very probably a provable no-op, but "very probably" is not a written reason.
        "app/src/test/kotlin/org/ort/app/ui/setup/InMemoryRigLinkPortTest.kt" to 8,
        "rig/src/test/kotlin/org/ort/rig/descriptor/DescriptorRigModuleTest.kt" to 9,
        "rig/src/test/kotlin/org/ort/rig/fakes/FakeRigTransportTest.kt" to 1,
        // R-1190 / Rule B — the FieldReportRecorder half the register row did not count.
        "app/src/test/kotlin/org/ort/app/fieldreport/bundle/FieldReportBundleBuilderTest.kt" to 3,
        "app/src/test/kotlin/org/ort/app/fieldreport/wiring/FieldReportAppWiringTest.kt" to 2,
    )

    @Test
    @Requirement("R-1180", "R-1190")
    fun `every test source keeps both coroutine-clock conventions, or is a named, counted exception`() {
        val root = repoRoot()
        val found: Map<String, List<TestCoroutineConventions.Finding>> = testSources(root)
            .associate { file ->
                val path = file.relativeTo(root).invariantSeparatorsPath
                path to TestCoroutineConventions.scan(path, file.readText())
            }
            .filterValues { it.isNotEmpty() }

        val counts = found.mapValues { it.value.size }
        if (counts == unreviewed) return

        val report = buildString {
            appendLine("Test-coroutine convention violation(s) - register R-1180, R-1189, R-1190.")
            appendLine()
            (counts.keys + unreviewed.keys).sorted().distinct().forEach { path ->
                val actual = counts[path] ?: 0
                val expected = unreviewed[path] ?: 0
                if (actual == expected) return@forEach
                appendLine("  $path: $actual finding(s), the counted exception list says $expected")
                found[path].orEmpty().forEach { appendLine("      line ${it.line}: ${it.text}") }
            }
            appendLine()
            TestCoroutineConventions.Rule.entries.forEach { appendLine("  - ${TestCoroutineConventions.explain(it)}") }
            appendLine()
            appendLine(
                "If a listed file has genuinely been cleaned up, delete its entry here - the count " +
                    "is pinned in both directions so it can never quietly stop describing the file.",
            )
        }
        assertEquals(report, unreviewed, counts)
    }

    // ---- the scanner's own discrimination: a deliberate violation fires, its fix does not -------

    @Test
    @Requirement("R-1180")
    fun `Rule A flags a virtual timeout in a runTest body and is satisfied by a written reason`() {
        val offending = """
            class X {
                @Test fun a() = runTest { withTimeout(5_000) { channel.receive() } }
            }
        """.trimIndent()
        val single = TestCoroutineConventions.scan("X.kt", offending).single()
        assertEquals(TestCoroutineConventions.Rule.VIRTUAL_TIMEOUT_IN_RUN_TEST, single.rule)

        val annotated = """
            class X {
                @Test fun a() = runTest {
                    // ${TestCoroutineConventions.OPT_OUT_MARKER} everything here is on the test scheduler
                    withTimeout(5_000) { channel.receive() }
                }
            }
        """.trimIndent()
        assertTrue(TestCoroutineConventions.scan("X.kt", annotated).isEmpty())

        val bareMarker = annotated.replace("everything here is on the test scheduler", "ok")
        assertEquals(
            "a marker with no reason behind it is not a reason",
            1,
            TestCoroutineConventions.scan("X.kt", bareMarker).size,
        )
    }

    @Test
    @Requirement("R-1180")
    fun `Rule A leaves a timeout alone once its test moves to runBlocking, which is the real fix`() {
        val fixed = """
            class X {
                @Test fun a() = runBlocking { withTimeout(5_000) { channel.receive() } }
            }
        """.trimIndent()
        assertTrue(TestCoroutineConventions.scan("X.kt", fixed).isEmpty())
    }

    @Test
    @Requirement("R-1190")
    fun `Rule B flags a flush whose innermost enclosing builder is not runBlocking`() {
        val offending = """
            class X {
                @Test fun a() = runTest {
                    worker.doWork()
                    DiagnosticsLog.flush()
                }
            }
        """.trimIndent()
        val single = TestCoroutineConventions.scan("X.kt", offending).single()
        assertEquals(TestCoroutineConventions.Rule.FLUSH_OUTSIDE_RUN_BLOCKING, single.rule)

        val fixed = offending.replace("DiagnosticsLog.flush()", "runBlocking { DiagnosticsLog.flush() }")
        assertTrue(
            "the innermost builder is what decides, so a runBlocking nested in runTest is correct",
            TestCoroutineConventions.scan("X.kt", fixed).isEmpty(),
        )
        val recorder = offending.replace("DiagnosticsLog", "FieldReportRecorder")
        assertEquals(1, TestCoroutineConventions.scan("X.kt", recorder).size)
    }

    /**
     * The masking pass earns its place here. This repository's test sources discuss `runTest` and
     * `withTimeout` at length in KDoc — R-1180's own fix is documented that way — and an unmasked
     * scanner would open a builder span on the first paragraph that says the word and swallow the
     * rest of the file.
     */
    @Test
    @Requirement("R-1180")
    fun `a builder named only in a comment, a KDoc or a string opens no span`() {
        val prose = """
            /** This class deliberately uses runTest { } and never a real dispatcher. */
            class X {
                private val note = "runTest { withTimeout(1) { } }"
                // runTest { withTimeout(1) { } }
                @Test fun a() = runBlocking { withTimeout(5_000) { channel.receive() } }
            }
        """.trimIndent()
        assertTrue(TestCoroutineConventions.scan("X.kt", prose).isEmpty())
    }

    // ---- locating the real sources --------------------------------------------------------------

    /** The same marker-file walk `NetManifestPermissionTest` uses; `settings.gradle.kts` is the
     * only file guaranteed to sit at a checkout's (or a worktree's) own root. */
    private fun repoRoot(): File {
        var dir = File(".").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("no settings.gradle.kts above ${File(".").absolutePath}")
        }
        return dir
    }

    /**
     * Every module's Kotlin test sources, sorted by `invariantSeparatorsPath` so this reports the
     * same order on Windows and on a Linux runner (the discipline `CoverageMatrix` already follows
     * for the same reason). Directories starting with `.` are skipped, which is what keeps other
     * builders' `.claude/worktrees/` checkouts out of a scan run from inside one of them.
     */
    private fun testSources(root: File): List<File> =
        root.listFiles { file -> file.isDirectory && !file.name.startsWith(".") }
            .orEmpty()
            .flatMap { module -> listOf("src/test/kotlin", "src/androidTest/kotlin").map { module.resolve(it) } }
            .filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" } }
            .sortedBy { it.invariantSeparatorsPath }
}
