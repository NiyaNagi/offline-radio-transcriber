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

    // F-027: corpus/ is Python (pyproject.toml, pytest), not Kotlin — the matrix must also scan
    // its test root so ids established there (FR-TST-6/8-style) are not stuck "not yet covered"
    // just because the requirement's home is the desktop tooling, not a Gradle module.

    @Test
    fun `a python test naming a requirement id in its function name is linked, attributed to corpus`(
        @TempDir dir: Path,
    ) {
        val corpusTests = dir.resolve("corpus").resolve("tests").toFile().apply { mkdirs() }
        File(corpusTests, "test_manifest.py").writeText(
            """
            def test_FR_TST_8_source_missing_licence_is_rejected():
                pass


            def test_something_unrelated():
                pass
            """.trimIndent(),
        )
        val map = CoverageMatrix.testsByRequirement(listOf(corpusTests))
        assertTrue(map.containsKey("FR-TST-8"), map.toString())
        val attributions = map.getValue("FR-TST-8")
        assertTrue(
            attributions.any {
                it == "corpus/tests/test_manifest.py::test_FR_TST_8_source_missing_licence_is_rejected"
            },
            attributions.toString(),
        )
    }

    @Test
    fun `a python test naming a requirement id is covered end to end via analyse`(@TempDir dir: Path) {
        val spec = dir.resolve("spec").toFile().apply { mkdirs() }
        File(spec, "s.md").writeText("**FR-TST-6 (S)** synthetic traffic generator.")

        val corpusTests = dir.resolve("corpus").resolve("tests").toFile().apply { mkdirs() }
        File(corpusTests, "test_traffic.py").writeText(
            "def test_FR_TST_6_activity_fraction_controls_transmission_density():\n    pass\n",
        )

        val coverage = CoverageMatrix.analyse(spec, listOf(corpusTests))
        assertTrue(coverage.covered.contains("FR-TST-6"), coverage.covered.toString())
        assertFalse(coverage.uncovered.contains("FR-TST-6"), coverage.uncovered.toString())
    }
}
