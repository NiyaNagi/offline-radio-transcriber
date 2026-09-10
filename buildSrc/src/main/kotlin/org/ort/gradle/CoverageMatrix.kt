package org.ort.gradle

import java.io.File

/**
 * Pure logic behind the `coverageMatrix` task, kept separate from Gradle types so it can be
 * unit-tested. See test-plan §9: the matrix is *generated*, never maintained by hand.
 */
object CoverageMatrix {

    /**
     * A requirement id token as it appears in the spec, e.g. `FR-RUN-10a`, `AC-91`, `NFR-1a`,
     * `CON-SEG-1`, `FR-A11Y-1` — the middle segment(s) are letters *or digits* (found by
     * adversarial review: `FR-A11Y-1` was invisible to this regex because `[A-Z]{2,5}` rejected
     * the digits in `A11Y`, silently uncounting a real, correctly-cited requirement and flagging
     * every test that named it as an orphan).
     */
    private val REQUIREMENT = Regex("""\b((?:FR|AC|NFR|CON)(?:-[A-Z][A-Z0-9]{1,5})*-\d+[a-z]?)\b""")

    /** How a test declares the requirement it establishes: an @Requirement annotation, or the id in its name. */
    private val ANNOTATION = Regex("""@Requirement\(\s*((?:"[^"]+"\s*,?\s*)+)\)""")
    private val ANNOTATION_ID = Regex(""""([^"]+)"""")

    /** A function declaration: `fun `name with spaces`(` or `fun plain_name(`. */
    private val TEST_FUNCTION = Regex("""fun\s+(?:`([^`]+)`|([A-Za-z_][A-Za-z0-9_]*))\s*\(""")

    /** A requirement id embedded in a test-function name, `_`-separated: `AC_91`, `FR_RUN_10a`, `CON_SEG_1`. */
    private val NAME_ID =
        Regex("""(?<![A-Za-z0-9])((?:FR|AC|NFR|CON)(?:_[A-Z][A-Z0-9]{1,5})*_\d+[a-z]?)(?=_|\s|${'$'})""")

    /**
     * F-023: a bare `F13`, `D28`, `R15` or `Q8` is not a requirement id — it is a cross-reference
     * to functional-spec §12's failure-mode register, a technical-design decision, a risk, or an
     * open-questions entry. A test naming one is documenting which failure mode, decision or
     * question it establishes behaviour for, not claiming an undefined requirement id, so it must
     * not be counted or rendered as an orphan. It is still worth surfacing on its own.
     *
     * F-029: the audit register itself writes these ids hyphenated (`F-005`, not `F5`), and
     * `@Requirement("FR-UI-7", "F-005")`-style annotations use that form — the bare-only regex
     * rejected it, so a correctly-cited audit id fell through to "orphan" in the generated
     * matrix. The hyphen is now optional.
     */
    private val CROSS_REFERENCE = Regex("""^[FDRQ]-?\d+[A-Z]?$""")

    /**
     * F-027: `corpus/` is Python (its own `pyproject.toml`, pytest under `corpus/tests/`), not a
     * Gradle module — a requirement whose home is the desktop tooling (FR-TST-6, FR-TST-8) can be
     * genuinely established there and still show up as permanently uncovered if the matrix only
     * ever looks at `.kt`. A pytest test declares itself with `def test_...(`, never a backtick
     * name or an annotation, so it is matched on the def line alone.
     */
    private val PY_TEST_FUNCTION = Regex("""def\s+(test_[A-Za-z0-9_]*)\s*\(""")

    data class Coverage(
        val requirements: List<String>,
        val testsByRequirement: Map<String, List<String>>,
    ) {
        val covered: Set<String> get() = testsByRequirement.keys.filter { it in requirements }.toSet()
        val uncovered: List<String> get() = requirements.filterNot { it in testsByRequirement }
        val orphanTests: Map<String, List<String>>
            get() = testsByRequirement.filterKeys { it !in requirements && !CROSS_REFERENCE.matches(it) }
        val crossReferencedTests: Map<String, List<String>>
            get() = testsByRequirement.filterKeys { it !in requirements && CROSS_REFERENCE.matches(it) }
    }

    fun requirementsFrom(specDir: File): List<String> {
        if (!specDir.isDirectory) return emptyList()
        val ids = sortedSetOf<String>(comparator())
        specDir.walkTopDown().filter { it.isFile && it.extension == "md" }
            .sortedByPath()
            .forEach { md -> REQUIREMENT.findAll(md.readText()).forEach { ids += normalise(it.value) } }
        return ids.toList()
    }

    /**
     * `File.walkTopDown()` enumerates a directory in whatever order the underlying filesystem
     * returns entries, which is not a JVM guarantee — NTFS and the Linux CI runner's filesystem
     * legitimately disagree. Left unsorted, the per-requirement test list below ends up in walk
     * order, so two platforms scanning identical sources render different bytes and
     * `coverageMatrixCheck`'s gate (F-014) fails on pure ordering noise even though nothing is
     * stale. Every collection that reaches [render] — the walk itself, and the finished test
     * list per requirement — is therefore sorted with a stable, locale-independent comparator
     * (path/string natural order, never [File.compareTo], which is itself platform-dependent:
     * case-insensitive on Windows, case-sensitive on Unix).
     */
    fun testsByRequirement(testRoots: Collection<File>): Map<String, MutableList<String>> {
        val map = sortedMapOf<String, MutableList<String>>(comparator())
        testRoots.filter { it.isDirectory }.sortedByPath().forEach { root ->
            root.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "py") }
                .sortedByPath()
                .forEach { file ->
                    if (file.extension == "kt") scanKotlinTest(file, map) else scanPythonTest(file, map)
                }
        }
        return map.mapValues { it.value.distinct().sorted().toMutableList() }.toSortedMap(comparator())
    }

    /**
     * Sorts by the file's path as a plain string (forward-slash separators, ordinal comparison)
     * rather than by [File] itself or [File.getName] alone — [File.compareTo] delegates to the
     * platform filesystem's own comparison (case-insensitive on Windows, case-sensitive on
     * Unix), which reintroduces exactly the cross-platform nondeterminism this fix removes.
     */
    private fun Iterable<File>.sortedByPath(): List<File> = sortedBy { it.invariantSeparatorsPath }
    private fun Sequence<File>.sortedByPath(): List<File> = sortedBy { it.invariantSeparatorsPath }.toList()

    private fun scanKotlinTest(kt: File, map: MutableMap<String, MutableList<String>>) {
        val text = kt.readText()
        val fqnHint = kt.nameWithoutExtension

        ANNOTATION.findAll(text).forEach { m ->
            ANNOTATION_ID.findAll(m.groupValues[1]).forEach { id ->
                map.getOrPut(normalise(id.groupValues[1])) { mutableListOf() }.add(fqnHint)
            }
        }
        TEST_FUNCTION.findAll(text).forEach { m ->
            val fn = m.groupValues[1].ifEmpty { m.groupValues[2] }
            NAME_ID.findAll(fn).forEach { g ->
                val id = normalise(g.groupValues[1].replace('_', '-'))
                map.getOrPut(id) { mutableListOf() }.add("$fqnHint.${fn.replace(' ', '_')}")
            }
        }
    }

    /**
     * `def test_FR_TST_8_source_missing_licence_is_rejected(` -> attributed as
     * `corpus/tests/test_manifest.py::test_FR_TST_8_source_missing_licence_is_rejected` — the
     * pytest node id form, rooted at the `corpus/` directory regardless of where on disk the
     * checkout lives, so the matrix is reproducible across machines.
     */
    private fun scanPythonTest(py: File, map: MutableMap<String, MutableList<String>>) {
        val text = py.readText()
        val attribution = corpusRelativePath(py)
        PY_TEST_FUNCTION.findAll(text).forEach { m ->
            val fn = m.groupValues[1]
            NAME_ID.findAll(fn).forEach { g ->
                val id = normalise(g.groupValues[1].replace('_', '-'))
                map.getOrPut(id) { mutableListOf() }.add("$attribution::$fn")
            }
        }
    }

    private fun corpusRelativePath(file: File): String {
        val parts = file.invariantSeparatorsPath.split("/")
        val idx = parts.lastIndexOf("corpus")
        return if (idx >= 0) parts.subList(idx, parts.size).joinToString("/") else file.name
    }

    fun analyse(specDir: File, testRoots: Collection<File>): Coverage =
        Coverage(requirementsFrom(specDir), testsByRequirement(testRoots))

    fun render(coverage: Coverage): String = buildString {
        appendLine("# Coverage matrix")
        appendLine()
        appendLine("_Generated by `./gradlew coverageMatrix`. Do not edit — test-plan §9._")
        appendLine()
        appendLine("| Metric | Count |")
        appendLine("|---|---:|")
        appendLine("| Requirement ids in `spec/` | ${coverage.requirements.size} |")
        appendLine("| Covered by at least one test | ${coverage.covered.size} |")
        appendLine("| Not yet covered | ${coverage.uncovered.size} |")
        appendLine("| Tests naming a requirement id not in the spec | ${coverage.orphanTests.size} |")
        appendLine()
        appendLine("## Covered")
        appendLine()
        appendLine("| Requirement | Tests |")
        appendLine("|---|---|")
        coverage.testsByRequirement.filterKeys { it in coverage.requirements }.toSortedMap(comparator())
            .forEach { (req, tests) -> appendLine("| `$req` | ${tests.joinToString("<br>") { "`$it`" }} |") }
        appendLine()
        if (coverage.orphanTests.isNotEmpty()) {
            appendLine("## Orphan tests (name a requirement id the spec does not define)")
            appendLine()
            coverage.orphanTests.toSortedMap(comparator())
                .forEach { (req, tests) -> appendLine("- `$req` — ${tests.joinToString(", ")}") }
            appendLine()
        }
        if (coverage.crossReferencedTests.isNotEmpty()) {
            // F-023: bare F/D/R/Q ids are not requirements — see CROSS_REFERENCE's doc comment.
            appendLine("## Cross-referenced failure modes / decisions / questions (not requirement ids)")
            appendLine()
            coverage.crossReferencedTests.toSortedMap(comparator())
                .forEach { (req, tests) -> appendLine("- `$req` — ${tests.joinToString(", ")}") }
            appendLine()
        }
        appendLine("## Not yet covered")
        appendLine()
        appendLine("<details><summary>${coverage.uncovered.size} requirements</summary>")
        appendLine()
        coverage.uncovered.sortedWith(comparator()).chunked(8)
            .forEach { row -> appendLine(row.joinToString(" · ") { "`$it`" }) }
        appendLine()
        appendLine("</details>")
    }

    /** `AC-9` before `AC-10`, `FR-RUN-2` before `FR-RUN-10a`. */
    private fun comparator(): Comparator<String> = Comparator { a, b ->
        val ta = tokenise(a)
        val tb = tokenise(b)
        for (i in 0 until minOf(ta.size, tb.size)) {
            val c = compareValues(ta[i], tb[i], i)
            if (c != 0) return@Comparator c
        }
        ta.size - tb.size
    }

    private fun tokenise(id: String): List<String> = id.split("-")

    private fun compareValues(a: String, b: String, index: Int): Int {
        val na = a.takeWhile { it.isDigit() }
        val nb = b.takeWhile { it.isDigit() }
        return if (index > 0 && na.isNotEmpty() && nb.isNotEmpty()) {
            val byNumber = na.toInt().compareTo(nb.toInt())
            if (byNumber != 0) byNumber else a.compareTo(b)
        } else {
            a.compareTo(b)
        }
    }

    private fun normalise(raw: String): String = raw.trim().uppercase().replace("--", "-")

    /**
     * F-014: `coverageMatrixCheck` compares freshly generated content against the committed
     * `results/coverage-matrix.md` and must fail on any real drift, but line-ending
     * (CRLF/LF) and trailing-newline differences are not drift — they are an artefact of the
     * checkout/editor, not a stale matrix — so they are normalised away before comparing.
     */
    fun contentMatches(generated: String, committed: String): Boolean =
        normaliseLineEndings(generated) == normaliseLineEndings(committed)

    private fun normaliseLineEndings(text: String): String =
        text.replace("\r\n", "\n").replace("\r", "\n").trimEnd('\n')
}
