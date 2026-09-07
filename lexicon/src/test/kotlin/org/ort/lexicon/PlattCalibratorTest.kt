package org.ort.lexicon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.core.AssetRef
import kotlin.math.abs
import kotlin.random.Random

/** FR-LEX-17: raw scores are calibrated to an estimated probability of correctness. */
class PlattCalibratorTest {

    /** A synthetic ground truth where correctness is exactly `sigmoid(2*score - 1)` — recovering
     * a=2, b=-1 from samples is the mechanism AC-55 depends on. */
    private fun syntheticSample(n: Int, seed: Int): Pair<List<Float>, List<Boolean>> {
        val rnd = Random(seed)
        val scores = ArrayList<Float>()
        val labels = ArrayList<Boolean>()
        repeat(n) {
            val s = rnd.nextDouble(-3.0, 3.0)
            val p = 1.0 / (1.0 + kotlin.math.exp(-(2.0 * s - 1.0)))
            scores += s.toFloat()
            labels += rnd.nextDouble() < p
        }
        return scores to labels
    }

    @Test
    fun `FR_LEX_17 fitting recovers a monotonic calibration matching the generating process`() {
        val (scores, labels) = syntheticSample(4000, seed = 42)
        val calibrator = PlattCalibrator.fit(scores, labels, AssetRef("test-calibration", "1"))
        // monotonic: a higher raw score must never calibrate to a lower probability
        val a = calibrator.calibrate(-1f)
        val b = calibrator.calibrate(0f)
        val c = calibrator.calibrate(1f)
        assertTrue(a < b && b < c, "calibration must be monotonic in the raw score: $a, $b, $c")
    }

    @Test
    fun `FR_LEX_17 output is always a valid probability`() {
        val (scores, labels) = syntheticSample(500, seed = 7)
        val calibrator = PlattCalibrator.fit(scores, labels, AssetRef("test-calibration", "1"))
        for (s in listOf(-100f, -1f, 0f, 1f, 100f)) {
            val p = calibrator.calibrate(s)
            assertTrue(p in 0f..1f, "calibrated value $p out of [0,1] for raw score $s")
        }
    }

    @Test
    fun `AC_55 a calibrated confidence of 0_9 corresponds to approximately 90 percent observed accuracy`() {
        val (fitScores, fitLabels) = syntheticSample(6000, seed = 1)
        val calibrator = PlattCalibrator.fit(fitScores, fitLabels, AssetRef("test-calibration", "1"))

        // A held-out sample from the SAME generating process — fitting and "eval" here are
        // deliberately different draws, mirroring the fold discipline the harness enforces for real.
        val (evalScores, evalLabels) = syntheticSample(20_000, seed = 2)
        val calibrated = evalScores.map { calibrator.calibrate(it) }

        val near90 = calibrated.indices.filter { abs(calibrated[it] - 0.9f) < 0.03f }
        assertTrue(near90.size > 30, "need enough samples near 0.9 to measure observed accuracy, got ${near90.size}")
        val observedAccuracy = near90.count { evalLabels[it] }.toDouble() / near90.size
        assertTrue(
            abs(observedAccuracy - 0.9) < 0.08,
            "observed accuracy $observedAccuracy should be close to the stated confidence 0.9",
        )
    }

    @Test
    fun `fitting refuses mismatched score and label lengths`() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            PlattCalibrator.fit(listOf(1f, 2f), listOf(true), AssetRef("test-calibration", "1"))
        }
    }

    @Test
    fun `every calibrator carries its versioned asset reference`() {
        val (scores, labels) = syntheticSample(50, seed = 3)
        val calibrator = PlattCalibrator.fit(scores, labels, AssetRef("prod-calibration", "7"))
        assertEquals(AssetRef("prod-calibration", "7"), calibrator.version)
    }
}
