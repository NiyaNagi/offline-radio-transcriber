package org.ort.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class CoverageMatrixTest {

    @Test
    fun `requirement ids are extracted from spec markdown`(@TempDir dir: Path) {
        val spec = dir.resolve("spec").toFile().apply { mkdirs() }
        File(spec, "functional-spec.md").writeText(
            "**FR-RUN-8 (M)** blah. See AC-47 and NFR-1a. Also FR-RUN-10a here.",
        )
        val ids = CoverageMatrix.requirementsFrom(spec)
        assertTrue(ids.containsAll(listOf("FR-RUN-8", "FR-RUN-10A", "AC-47", "NFR-1A")), ids.toString())
    }

    @Test
    fun `a test is linked to its requirement by name and by annotation`(@TempDir dir: Path) {
        val tests = dir.resolve("t").toFile().apply { mkdirs() }
        File(tests, "SomeTest.kt").writeText(
            """
            class SomeTest {
                @Test fun `AC_47_killing_process_mid_pass_completes_identically`() {}

                @Requirement("FR-RUN-8", "AC-45")
                @Test fun something_else() {}
            }
            """.trimIndent(),
        )
        val map = CoverageMatrix.testsByRequirement(listOf(tests))
        assertTrue(map.containsKey("AC-47"))
        assertTrue(map.containsKey("FR-RUN-8"))
        assertTrue(map.containsKey("AC-45"))
    }

    @Test
    fun `ordering is numeric within a family`() {
        val cov = CoverageMatrix.Coverage(listOf("AC-2", "AC-10", "AC-9"), emptyMap())
        val rendered = CoverageMatrix.render(cov)
        val order = Regex("""AC-\d+""").findAll(rendered).map { it.value }.distinct().toList()
        assertEquals(listOf("AC-2", "AC-9", "AC-10"), order)
    }

    @Test
    fun `a requirement id whose middle segment contains digits is recognised, not orphaned`(@TempDir dir: Path) {
        // Found by adversarial review: FR-A11Y-1 (functional-spec.md's real accessibility
        // requirement) was invisible to the old regex because [A-Z]{2,5} rejected digits in the
        // middle segment, silently dropping it from the requirement count and flagging every
        // test that correctly cited it as an orphan.
        val spec = dir.resolve("spec").toFile().apply { mkdirs() }
        File(spec, "functional-spec.md").writeText(
            "**FR-A11Y-1 (M)** The four attribution states SHALL be distinguishable without colour.",
        )
        val tests = dir.resolve("t").toFile().apply { mkdirs() }
        File(tests, "T.kt").writeText(
            """
            class T {
                @Requirement("FR-A11Y-1")
                @Test fun `all four states render distinctly`() {}
            }
            """.trimIndent(),
        )
        val coverage = CoverageMatrix.analyse(spec, listOf(tests))
        assertTrue(coverage.requirements.contains("FR-A11Y-1"), coverage.requirements.toString())
        assertTrue(coverage.covered.contains("FR-A11Y-1"))
        assertFalse(coverage.orphanTests.containsKey("FR-A11Y-1"), coverage.orphanTests.toString())
    }

    @Test
    fun `an orphan test naming an unknown requirement is surfaced`(@TempDir dir: Path) {
        val spec = dir.resolve("spec").toFile().apply { mkdirs() }
        File(spec, "s.md").writeText("AC-1 exists.")
        val tests = dir.resolve("t").toFile().apply { mkdirs() }
        File(tests, "T.kt").writeText("@Test fun `AC_999_not_a_real_requirement`() {}")

        val coverage = CoverageMatrix.analyse(spec, listOf(tests))
        assertTrue(coverage.orphanTests.containsKey("AC-999"))
        assertFalse(coverage.covered.contains("AC-999"))
    }

    // F-014: results/coverage-matrix.md is committed but nothing failed CI when it went stale.
    // contentMatches() is the comparison `coverageMatrixCheck` uses to fail loudly instead.

    @Test
    fun `contentMatches is false when the committed matrix is stale`() {
        val generated = "# Coverage matrix\n\n| Metric | Count |\n|---|---:|\n| Requirement ids in `spec/` | 117 |\n"
        val committed = "# Coverage matrix\n\n| Metric | Count |\n|---|---:|\n| Requirement ids in `spec/` | 101 |\n"
        assertFalse(CoverageMatrix.contentMatches(generated, committed))
    }

    @Test
    fun `contentMatches is true for byte-identical content`() {
        val text = "# Coverage matrix\n\nsome body\n"
        assertTrue(CoverageMatrix.contentMatches(text, text))
    }

    @Test
    fun `contentMatches ignores a trailing-newline-only difference`() {
        val generated = "# Coverage matrix\n\nsome body\n"
        val committed = "# Coverage matrix\n\nsome body"
        assertTrue(CoverageMatrix.contentMatches(generated, committed))
    }

    @Test
    fun `contentMatches ignores a line-ending-only difference`() {
        val generated = "# Coverage matrix\n\nsome body\n"
        val committed = "# Coverage matrix\r\n\r\nsome body\r\n"
        assertTrue(CoverageMatrix.contentMatches(generated, committed))
    }

    @Test
    fun `contentMatches still catches a real content difference under CRLF`() {
        val generated = "line one\nline two\n"
        val committed = "line one\r\nline TWO\r\n"
        assertFalse(CoverageMatrix.contentMatches(generated, committed))
    }
}
