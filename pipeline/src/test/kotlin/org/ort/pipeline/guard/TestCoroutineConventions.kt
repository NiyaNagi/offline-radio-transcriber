package org.ort.pipeline.guard

/**
 * Register R-1180 / R-1189 / R-1190: the two test-source conventions that keep a virtual clock from
 * being mistaken for a bound. Pure logic over source text, kept apart from the scan itself so it can
 * be unit-tested against synthetic sources — the same split `PlatformGuards`/`PlatformGuardsTask`
 * use in `buildSrc`, and for the same reason.
 *
 * **Why this is a test and not a custom detekt rule.** The audit's first choice was a detekt rule
 * over test sources. This repository cannot host one without inventing a mechanism: there is no
 * `detektPlugins` configuration anywhere, no `RuleSetProvider`, and no `includeBuild` in
 * `settings.gradle.kts`, so a custom rule set would need a new Gradle build plus wiring in
 * `ort.common.gradle.kts` — and `buildSrc`'s own classes are on the buildscript classpath, never on
 * a module's, so they cannot be that rule set either. `config/detekt/detekt.yml`'s own header says
 * where this project puts its real guardrails: "the dependency-rules task, the spec-integrity checks
 * and strict TDD, not style nagging". The nearest existing pattern is therefore
 * `net/src/test/.../NetManifestPermissionTest.kt` — a plain unit test that reads the real committed
 * files and fails `./gradlew :module:test` with no build-script involvement at all — and that is
 * what [TestCoroutineConventionGuardTest] is.
 *
 * ### Rule A — `withTimeout`/`withTimeoutOrNull` lexically inside a `runTest` body
 *
 * Under `runTest` the timeout counts **virtual** time, and the virtual clock leaps to the deadline
 * the instant the test scheduler has nothing runnable. It is therefore only correct when everything
 * it bounds is on that scheduler — and when that is true the timeout is a provable **no-op**,
 * because virtual time cannot elapse against work the scheduler owns. So the false-positive set is
 * exactly "timeouts that are provably doing nothing", which is precisely the set worth a written
 * reason. That reason is the opt-out: [OPT_OUT_MARKER] followed by it, on the finding's own line or
 * within the [OPT_OUT_LOOKBACK_LINES] lines above.
 *
 * **What this rule deliberately does not attempt** (the audit was explicit): the general claim, "a
 * `runTest` body that reaches a real scope". That needs a whole-program call graph across module
 * boundaries, through interfaces and lambdas, and cannot be written without false positives.
 * R-1189's own fix — [org.ort.data.WorkQueue]'s runtime refusal — is what covers the case no lexical
 * rule can see.
 *
 * ### Rule B — `DiagnosticsLog.flush` / `FieldReportRecorder.flush` outside `runBlocking`
 *
 * Both await a `CompletableDeferred` completed on a real `Dispatchers.IO` consumer. Inside a
 * `runTest` body that await is bounded by `runTest`'s own wall-clock timeout rather than by virtual
 * time, so it is not a race today (R-1190 says so explicitly, and this guard does not pretend
 * otherwise) — but one added `withTimeout` in the same body turns it into R-1180 exactly.
 *
 * ### The limit both rules share, stated rather than glossed
 *
 * A finding is judged by its **innermost enclosing coroutine builder**, found lexically. A call made
 * from a `private suspend fun` helper has no enclosing builder in its own file and is not reported
 * by either rule. That is the deliberate price of zero false positives: every one of the sixteen
 * real flush sites and every real timeout site in this repository is written inline in the builder
 * body it belongs to, so the rules see all of them, but a future author who hides one behind a
 * helper will not be caught here.
 */
internal object TestCoroutineConventions {

    internal enum class Rule {
        /** R-1180: a virtual-time `withTimeout` inside a `runTest` body, with no written reason. */
        VIRTUAL_TIMEOUT_IN_RUN_TEST,

        /** R-1190: a `flush()` whose innermost enclosing builder is not `runBlocking`. */
        FLUSH_OUTSIDE_RUN_BLOCKING,
    }

    internal data class Finding(val path: String, val line: Int, val rule: Rule, val text: String)

    /** The opt-out Rule A accepts, and the register row whose reasoning it points at. */
    internal const val OPT_OUT_MARKER: String = "R-1180-virtual-timeout-ok:"

    /** How far above a finding the opt-out may sit — enough for the marker to be the line above a
     * multi-line `withTimeout(...) {` block, never enough to drift onto an unrelated statement. */
    internal const val OPT_OUT_LOOKBACK_LINES: Int = 8

    /** A bare marker is not a reason; this is the shortest thing that can be one. */
    internal const val MIN_REASON_LENGTH: Int = 12

    private const val RUN_TEST = "runTest"
    private const val RUN_BLOCKING = "runBlocking"

    private val BUILDER = Regex("""\b(runTest|runBlocking)\b""")
    private val TIMEOUT = Regex("""\bwithTimeout(?:OrNull)?\s*\(""")
    private val FLUSH = Regex("""\b(?:DiagnosticsLog|FieldReportRecorder)\.flush\s*\(""")

    /** Every finding in one Kotlin test source, in file order. [path] is only carried through. */
    internal fun scan(path: String, source: String): List<Finding> {
        val scan = Scan(path, maskCommentsAndLiterals(source), source.lines())
        val timeouts = TIMEOUT.findAll(scan.masked).mapNotNull { scan.virtualTimeout(it.range.first) }
        val flushes = FLUSH.findAll(scan.masked).mapNotNull { scan.unwrappedFlush(it.range.first) }
        return (timeouts.toList() + flushes.toList()).sortedBy { it.line }
    }

    /** One file under inspection: the masked text, its original lines, and its builder spans. */
    private class Scan(val path: String, val masked: String, val lines: List<String>) {
        private val spans = builderSpans(masked)

        fun virtualTimeout(offset: Int): Finding? {
            if (innermostBuilder(spans, offset) != RUN_TEST) return null
            val line = lineNumberAt(masked, offset)
            if (hasOptOut(lines, line)) return null
            return Finding(path, line, Rule.VIRTUAL_TIMEOUT_IN_RUN_TEST, lines[line - 1].trim())
        }

        fun unwrappedFlush(offset: Int): Finding? {
            if (innermostBuilder(spans, offset) == RUN_BLOCKING) return null
            val line = lineNumberAt(masked, offset)
            return Finding(path, line, Rule.FLUSH_OUTSIDE_RUN_BLOCKING, lines[line - 1].trim())
        }
    }

    /** The human-readable explanation a failing guard prints for [rule]. */
    internal fun explain(rule: Rule): String = when (rule) {
        Rule.VIRTUAL_TIMEOUT_IN_RUN_TEST ->
            "register R-1180: a `withTimeout` inside a `runTest` body counts VIRTUAL time, and the " +
                "virtual clock jumps to the deadline the moment the test scheduler is idle - so it " +
                "races anything on a real dispatcher instead of bounding it. Either move the test to " +
                "`kotlinx.coroutines.runBlocking`, so the seconds are real, or - if everything the " +
                "timeout bounds genuinely runs on this test scheduler, which also makes it a provable " +
                "no-op - write the reason as `// $OPT_OUT_MARKER <why>` on the line or just above it."
        Rule.FLUSH_OUTSIDE_RUN_BLOCKING ->
            "register R-1190: `DiagnosticsLog.flush`/`FieldReportRecorder.flush` await a " +
                "CompletableDeferred completed on a real Dispatchers.IO consumer, and every other " +
                "call site in this repository wraps them in `runBlocking`. Wrap this one too: the " +
                "convention is what keeps a single added `withTimeout` in the same body from " +
                "becoming R-1180."
    }

    // ---- lexical machinery -------------------------------------------------------------------

    private data class Span(val builder: String, val open: Int, val close: Int)

    private fun builderSpans(masked: String): List<Span> = BUILDER.findAll(masked).mapNotNull { match ->
        val open = openingBraceAfter(masked, match.range.last + 1) ?: return@mapNotNull null
        val close = matchingBrace(masked, open) ?: return@mapNotNull null
        Span(match.groupValues[1], open, close)
    }.toList()

    /** The builder of the innermost span containing [offset], or `null` if none does. */
    private fun innermostBuilder(spans: List<Span>, offset: Int): String? =
        spans.filter { offset > it.open && offset < it.close }.maxByOrNull { it.open }?.builder

    /** `runTest {`, `runTest(timeout = ...) {` and `runBlocking(ctx) {` all reach the same brace. */
    private fun openingBraceAfter(masked: String, from: Int): Int? {
        var i = skipWhitespace(masked, from)
        if (i < masked.length && masked[i] == '(') {
            i = skipBalanced(masked, i, '(', ')')
            i = skipWhitespace(masked, i)
        }
        return if (i < masked.length && masked[i] == '{') i else null
    }

    private fun skipWhitespace(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    private fun skipBalanced(text: String, from: Int, open: Char, close: Char): Int {
        var depth = 0
        var i = from
        while (i < text.length) {
            if (text[i] == open) depth++
            if (text[i] == close) {
                depth--
                if (depth == 0) return i + 1
            }
            i++
        }
        return i
    }

    private fun matchingBrace(masked: String, open: Int): Int? {
        val end = skipBalanced(masked, open, '{', '}')
        return if (end > open && end <= masked.length) end - 1 else null
    }

    private fun lineNumberAt(text: String, offset: Int): Int {
        var line = 1
        for (i in 0 until offset) if (text[i] == '\n') line++
        return line
    }

    private fun hasOptOut(lines: List<String>, lineNumber: Int): Boolean {
        val first = maxOf(0, lineNumber - 1 - OPT_OUT_LOOKBACK_LINES)
        return (first until lineNumber).any { index ->
            val at = lines[index].indexOf(OPT_OUT_MARKER)
            at >= 0 && lines[index].substring(at + OPT_OUT_MARKER.length).trim().length >= MIN_REASON_LENGTH
        }
    }

    /**
     * Replaces comments, string literals, character literals and backticked identifiers with spaces
     * of the same length, so offsets and line numbers survive exactly. Without this a `runTest`
     * named inside a KDoc paragraph — of which this repository has a great many — would open a
     * builder span that swallows the rest of the file.
     */
    private fun maskCommentsAndLiterals(source: String): String {
        val out = StringBuilder(source)
        var i = 0
        while (i < source.length) {
            val end = maskedRegionEnd(source, i)
            if (end < 0) {
                i++
            } else {
                blank(out, i, end)
                i = end
            }
        }
        return out.toString()
    }

    /** The exclusive end of the maskable region starting at [i], or `-1` if none starts there. */
    private fun maskedRegionEnd(source: String, i: Int): Int = when {
        source.startsWith("//", i) -> source.indexOf('\n', i).let { if (it < 0) source.length else it }
        source.startsWith("/*", i) -> closingOf(source, i, "*/", 2)
        source.startsWith("\"\"\"", i) -> closingOf(source, i + 3, "\"\"\"", 3)
        source[i] == '"' -> endOfSingleLineLiteral(source, i, '"')
        source[i] == '\'' -> endOfSingleLineLiteral(source, i, '\'')
        source[i] == '`' -> closingOf(source, i + 1, "`", 1)
        else -> -1
    }

    private fun closingOf(source: String, from: Int, terminator: String, terminatorLength: Int): Int {
        val at = source.indexOf(terminator, from)
        return if (at < 0) source.length else at + terminatorLength
    }

    private fun endOfSingleLineLiteral(source: String, from: Int, quote: Char): Int {
        var i = from + 1
        while (i < source.length) {
            when (source[i]) {
                '\\' -> i++
                quote -> return i + 1
                '\n' -> return i
            }
            i++
        }
        return source.length
    }

    private fun blank(out: StringBuilder, from: Int, to: Int) {
        for (i in from until minOf(to, out.length)) {
            if (out[i] != '\n') out.setCharAt(i, ' ')
        }
    }
}
