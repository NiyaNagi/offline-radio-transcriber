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
        // audit F-029: the fake test-function name is built by concatenation, not written as one
        // contiguous string literal (and this comment deliberately never spells it out whole
        // either). Because F-027 added buildSrc/src/test/kotlin to the matrix's own scanned
        // roots, a `fun \`<id>_not_a_real_requirement\`(`-shaped literal sitting in *this file's*
        // own source text — including inside a comment — would be picked up by the real
        // coverageMatrix task as a genuine (fake) test declaration and rendered as a false orphan
        // in the committed matrix: the bug this fixture is supposed to exercise in isolation, not
        // cause for real. Splitting the token defeats that static scan while the
        // runtime-assembled file content, which is all CoverageMatrix.analyse ever sees, is
        // unchanged.
        val fakeId = "AC" + "_999"
        File(tests, "T.kt").writeText("@Test fun `${fakeId}_not_a_real_requirement`() {}")

        val coverage = CoverageMatrix.analyse(spec, listOf(tests))
        val fakeIdHyphenated = fakeId.replace('_', '-')
        assertTrue(coverage.orphanTests.containsKey(fakeIdHyphenated))
        assertFalse(coverage.covered.contains(fakeIdHyphenated))
    }

    // F-029: the audit register writes its own ids hyphenated (`F-005`, not `F5`), and
    // `@Requirement("FR-UI-7", "F-005")`-style annotations in RealCaptureServiceTest use that
    // form. The old CROSS_REFERENCE regex only matched the bare `F13` shape, so a hyphenated
    // audit id fell through to "orphan" — a false positive in the generated matrix.

    @Test
    fun `F-029 a hyphenated audit id like F-005 is a cross-reference, not an orphan`(@TempDir dir: Path) {
        val spec = dir.resolve("spec").toFile().apply { mkdirs() }
        File(spec, "s.md").writeText("AC-1 exists.")
        val tests = dir.resolve("t").toFile().apply { mkdirs() }
        File(tests, "T.kt").writeText(
            """
            class T {
                @Requirement("FR-UI-7", "F-005")
                @Test fun something() {}
            }
            """.trimIndent(),
        )

        val coverage = CoverageMatrix.analyse(spec, listOf(tests))
        assertFalse(coverage.orphanTests.containsKey("F-005"), coverage.orphanTests.toString())
        assertTrue(coverage.crossReferencedTests.containsKey("F-005"), coverage.crossReferencedTests.toString())
    }

    // F-023: bare `F13`/`Q8`-style ids are cross-references to §12 failure modes and the
    // open-questions register, not requirement ids — the tool must not report a test naming one
    // as an orphan, but the reference is still worth surfacing, so it gets its own section.

    @Test
    fun `F-023 a test naming a bare failure-mode or question id is not an orphan`(@TempDir dir: Path) {
        val spec = dir.resolve("spec").toFile().apply { mkdirs() }
        File(spec, "s.md").writeText("AC-1 exists.")
        val tests = dir.resolve("t").toFile().apply { mkdirs() }
        File(tests, "T.kt").writeText(
            """
            class T {
                @Requirement("F13")
                @Test fun something() {}

                @Requirement("Q8")
                @Test fun something_else() {}
            }
            """.trimIndent(),
        )

        val coverage = CoverageMatrix.analyse(spec, listOf(tests))
        assertFalse(coverage.orphanTests.containsKey("F13"), coverage.orphanTests.toString())
        assertFalse(coverage.orphanTests.containsKey("Q8"), coverage.orphanTests.toString())
        assertTrue(coverage.crossReferencedTests.containsKey("F13"), coverage.crossReferencedTests.toString())
        assertTrue(coverage.crossReferencedTests.containsKey("Q8"), coverage.crossReferencedTests.toString())
    }

    @Test
    fun `F-023 the rendered matrix lists cross-references in their own section, not as orphans`(@TempDir dir: Path) {
        val spec = dir.resolve("spec").toFile().apply { mkdirs() }
        File(spec, "s.md").writeText("AC-1 exists.")
        val tests = dir.resolve("t").toFile().apply { mkdirs() }
        File(tests, "T.kt").writeText(
            """
            class T {
                @Requirement("F13")
                @Test fun `a probe-run crash refuses activation`() {}
            }
            """.trimIndent(),
        )

        val coverage = CoverageMatrix.analyse(spec, listOf(tests))
        val rendered = CoverageMatrix.render(coverage)
        assertTrue(
            rendered.contains("Cross-referenced failure modes / decisions / questions"),
            rendered,
        )
        assertFalse(rendered.contains("## Orphan tests"), rendered)
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

    // F-014: coverageMatrixCheck compares generated bytes against the committed file and fails
    // on any real drift. CI's Linux runner and a Windows checkout of the *same* sources were
    // seen to render different bytes for results/coverage-matrix.md with no real drift between
    // them — File.walkTopDown() enumerates a directory in filesystem order, which NTFS and the
    // Linux runner's filesystem are not obliged to agree on, so an unsorted per-requirement test
    // list ends up in walk order and differs by platform alone. This test does not depend on
    // actual filesystem enumeration order (that cannot be forced from a JUnit test); instead it
    // feeds testsByRequirement two directory layouts that discover the same two test files in
    // opposite order and asserts the rendered matrix is byte-identical either way — pinning the
    // real deliverable: determinism regardless of discovery order.
    //
    // audit F-029's fixture technique applies here too: the fake ids and fun names below are
    // assembled at runtime by string interpolation, not written as one contiguous literal, so
    // this test's own source text never contains a `fun \`AC_970...\`(`-shaped string for the
    // real coverageMatrix task to pick up when it scans buildSrc/src/test/kotlin (this file is
    // one of its roots) — that would silently add fake fixture tests to the committed matrix.

    @Test
    fun `rendered matrix is byte-identical regardless of file discovery order`(@TempDir dir: Path) {
        val idA = "AC" + "_97001"
        val idB = "AC" + "_97002"
        val idAHyphen = idA.replace('_', '-')
        val idBHyphen = idB.replace('_', '-')

        val spec = dir.resolve("spec").toFile().apply { mkdirs() }
        File(spec, "s.md").writeText("$idAHyphen exists. $idBHyphen exists.")

        // Two requirements, each established by a test in a *different* file, so the order in
        // which the two files are visited controls the order their names land in the list for
        // whichever requirement both happen to name (mirrors AC-31 in the real committed matrix,
        // whose test list depends on walk order today).
        val rootA = dir.resolve("a").toFile().apply { mkdirs() }
        File(rootA, "AaaTest.kt").writeText(
            "class AaaTest {\n" +
                "    @Test fun `${idA}_covered_from_the_first_file`() {}\n" +
                "    @Test fun `${idB}_shared_requirement_seen_here_too`() {}\n" +
                "}\n",
        )
        val rootB = dir.resolve("z").toFile().apply { mkdirs() }
        File(rootB, "ZzzTest.kt").writeText(
            "class ZzzTest {\n" +
                "    @Test fun `${idB}_shared_requirement_seen_here_too`() {}\n" +
                "}\n",
        )

        val forward = CoverageMatrix.render(CoverageMatrix.analyse(spec, listOf(rootA, rootB)))
        val reversed = CoverageMatrix.render(CoverageMatrix.analyse(spec, listOf(rootB, rootA)))

        assertEquals(forward, reversed, "$forward\n---\n$reversed")

        // Same scenario, forced past render() to pin testsByRequirement itself: two Coverage
        // maps built from roots discovered in opposite order must be identical, and the shared
        // requirement's test list must come out in a fixed (sorted) order either way.
        val coverageInOrder = CoverageMatrix.testsByRequirement(listOf(rootA, rootB))
        val coverageReordered = CoverageMatrix.testsByRequirement(listOf(rootB, rootA))
        assertEquals(coverageInOrder, coverageReordered)
        assertEquals(
            listOf(
                "AaaTest.${idB}_shared_requirement_seen_here_too",
                "ZzzTest.${idB}_shared_requirement_seen_here_too",
            ),
            coverageInOrder.getValue(idBHyphen),
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
