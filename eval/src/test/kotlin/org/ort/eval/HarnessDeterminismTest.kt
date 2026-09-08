package org.ort.eval

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** AC-90: two harness runs over the same corpus, model and configuration are byte-identical
 * within a fixed (machine, provider, thread count). */
class HarnessDeterminismTest {

    private fun config() = HarnessConfig("dev", "bench-1", "cpu", 4, "onnxruntime-1.18", precisionTarget = 0.8f)

    private fun sample() = (1..30).map { positive("p$it", "VK3MMM") } +
        (1..30).map { positive("q$it", "K7ABC") } +
        (1..10).map { negative("n$it") }

    @Test
    fun `FR_TST_4 AC_90 two runs over identical inputs produce byte-identical canonical reports`() {
        val harness = Harness(bundledGrammar(), bundledCombiner())
        val occurrences = sample()
        val cfg = config()
        val first = harness.run(occurrences, occurrences, cfg).canonicalText()
        val second = harness.run(occurrences, occurrences, cfg).canonicalText()
        assertEquals(first, second)
    }

    @Test
    fun `FR_TST_4 AC_90 a fresh harness instance over the same corpus and config reproduces the same report`() {
        val occurrences = sample()
        val cfg = config()
        val a = Harness(bundledGrammar(), bundledCombiner()).run(occurrences, occurrences, cfg).canonicalText()
        val b = Harness(bundledGrammar(), bundledCombiner()).run(occurrences, occurrences, cfg).canonicalText()
        assertEquals(a, b)
    }
}
