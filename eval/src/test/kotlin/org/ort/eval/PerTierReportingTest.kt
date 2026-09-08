package org.ort.eval

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * NFR-1c: each tier's numbers are measured and reported separately, on the same evaluation set —
 * never folded into one aggregate. [HarnessConfig.precisionTarget] is a tier's precision target
 * (FR-LEX-19), so two [Harness.run] calls with different targets over the *same* evaluate set
 * stand in for "the same evaluation set, reported per tier": each call returns its own complete,
 * independent [HarnessReport] rather than contributing to a shared running total.
 */
class PerTierReportingTest {

    private fun sample() = (1..30).map { positive("p$it", "VK3MMM") } + (1..30).map { negative("n$it") }

    @Test
    fun `NFR_1c two tiers evaluated on the same set produce two independent reports, not one aggregate`() {
        val occurrences = sample()
        val harness = Harness(bundledGrammar(), bundledCombiner())

        val t0Strict = HarnessConfig("dev", "m", "cpu", 1, "test", precisionTarget = 0.97f)
        val t2Loose = HarnessConfig("dev", "m", "cpu", 1, "test", precisionTarget = 0.6f)

        val strictReport = harness.run(occurrences, occurrences, t0Strict)
        val looseReport = harness.run(occurrences, occurrences, t2Loose)

        // Each report is self-contained: its own threshold, its own metrics, computed only from
        // this call's inputs — nothing here merges the two runs into a shared number.
        assertNotEquals(
            strictReport.confirmedThreshold,
            looseReport.confirmedThreshold,
            "a stricter tier's derived threshold must differ, proving each run is independent",
        )
        // rerunning the same tier again reproduces exactly that tier's own numbers (not drifted by
        // the other tier having run in between) — there is no shared mutable aggregate state.
        val strictAgain = harness.run(occurrences, occurrences, t0Strict)
        assertEquals(strictReport.canonicalText(), strictAgain.canonicalText())
    }
}
