package org.ort.eval

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * Calibration and the CONFIRMED threshold must come from the fit set alone (build-plan P7:
 * "Platt calibration fitted on train+dev and verified on eval") — never from whatever is being
 * scored. This is the eval-fold-leakage guard: [Harness.run] takes `fitOn` and `evaluate` as two
 * separate parameters precisely so the one real caller who eventually wires the `eval` fold in
 * cannot pass it to both and silently fit on it.
 */
class HarnessFoldIsolationTest {

    private fun config() = HarnessConfig("dev", "m", "cpu", 1, "test", precisionTarget = 0.7f)

    @Test
    fun `the derived threshold depends only on the fit set, not on what is being evaluated`() {
        val harness = Harness(bundledGrammar(), bundledCombiner())
        val fitOn = (1..40).map { positive("f$it", "VK3MMM") } + (1..10).map { negative("fn$it") }

        val evaluateA = (1..5).map { positive("a$it", "VK3MMM") }
        val evaluateB = (1..5).map { negative("b$it") } // wildly different distribution

        val thresholdA = harness.run(fitOn, evaluateA, config()).confirmedThreshold
        val thresholdB = harness.run(fitOn, evaluateB, config()).confirmedThreshold

        assertEquals(thresholdA, thresholdB, "the threshold must be a function of fitOn alone")
    }

    @Test
    fun `metrics reflect the evaluate set's own labels, not the fit set's`() {
        val harness = Harness(bundledGrammar(), bundledCombiner())
        val fitOn = (1..40).map { positive("f$it", "VK3MMM") } + (1..10).map { negative("fn$it") }

        val allPositive = (1..10).map { positive("p$it", "VK3MMM") }
        val allNegative = (1..10).map { negative("n$it") }

        val reportPositive = harness.run(fitOn, allPositive, config())
        val reportNegative = harness.run(fitOn, allNegative, config())

        assertNotEquals(
            reportPositive.callsignMetrics.truePositives,
            reportNegative.callsignMetrics.truePositives,
            "metrics must track the evaluate set actually scored, not the fit set",
        )
    }
}
