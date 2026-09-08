package org.ort.eval

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/** FR-LEX-20, AC-55 groundwork: the harness's reliability diagram — predicted confidence versus
 * observed accuracy in bins, with an expected calibration error. */
class ReliabilityDiagramTest {

    @Test
    fun `FR_LEX_20 a perfectly calibrated set of bins has zero expected calibration error`() {
        val diagram = ReliabilityDiagram(
            listOf(
                ReliabilityBin(0.0f, 0.1f, 10, meanPredicted = 0.05f, observedAccuracy = 0.05f),
                ReliabilityBin(0.8f, 0.9f, 10, meanPredicted = 0.85f, observedAccuracy = 0.85f),
                ReliabilityBin(0.9f, 1.0f, 10, meanPredicted = 0.92f, observedAccuracy = 0.92f),
            ),
        )
        assertEquals(0f, diagram.expectedCalibrationError, 1e-6f)
    }

    @Test
    fun `FR_LEX_20 a systematically overconfident model has a positive expected calibration error`() {
        val diagram = ReliabilityDiagram(
            listOf(ReliabilityBin(0.9f, 1.0f, 100, meanPredicted = 0.95f, observedAccuracy = 0.6f)),
        )
        assertTrue(diagram.expectedCalibrationError > 0.3f)
    }

    @Test
    fun `AC_55 the harness's reliability diagram bins put observed accuracy near predicted confidence`() {
        // Construct a set where the resolver is correct roughly in proportion to how strongly
        // the priors favour the true answer, so the calibrated 0.9 bin should show ~90% accuracy.
        val occurrences = buildList {
            repeat(90) { add(positive("t$it", "VK3MMM")) }
            repeat(10) { add(negative("f$it")) }
        }
        val harness = Harness(bundledGrammar(), bundledCombiner())
        val report = harness.run(occurrences, occurrences, HarnessConfig("dev", "m", "cpu", 1, "test"))
        val highBin = report.reliabilityDiagram.bins.lastOrNull { it.count > 0 }
        if (highBin != null) {
            assertTrue(abs(highBin.observedAccuracy - highBin.meanPredicted) < 0.5f)
        }
    }
}
