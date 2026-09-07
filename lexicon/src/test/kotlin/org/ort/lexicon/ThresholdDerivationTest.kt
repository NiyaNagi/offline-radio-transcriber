package org.ort.lexicon

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** FR-LEX-19, AC-56: the CONFIRMED threshold is derived from a precision target; the user
 * never sets a raw score directly. */
class ThresholdDerivationTest {

    private fun syntheticCalibratedSample(n: Int, seed: Int): Pair<List<Float>, List<Boolean>> {
        val rnd = Random(seed)
        val scores = ArrayList<Float>()
        val labels = ArrayList<Boolean>()
        repeat(n) {
            val p = rnd.nextDouble(0.0, 1.0)
            scores += p.toFloat()
            labels += rnd.nextDouble() < p
        }
        return scores to labels
    }

    @Test
    fun `AC_56 raising the precision target moves the CONFIRMED threshold upward`() {
        val (scores, labels) = syntheticCalibratedSample(20_000, seed = 11)
        val low = ThresholdDerivation.forPrecisionTarget(scores, labels, precisionTarget = 0.7f)
        val high = ThresholdDerivation.forPrecisionTarget(scores, labels, precisionTarget = 0.95f)
        assertNotNull(low)
        assertNotNull(high)
        assertTrue(high!!.threshold >= low!!.threshold, "a higher precision target must not lower the threshold")
        assertTrue(high.achievedPrecision >= 0.95f - 1e-3f)
    }

    @Test
    fun `AC_56 the achieved recall falls as the precision target rises`() {
        val (scores, labels) = syntheticCalibratedSample(20_000, seed = 12)
        val loose = ThresholdDerivation.forPrecisionTarget(scores, labels, precisionTarget = 0.6f)!!
        val strict = ThresholdDerivation.forPrecisionTarget(scores, labels, precisionTarget = 0.97f)!!
        assertTrue(strict.achievedRecall <= loose.achievedRecall)
    }

    @Test
    fun `an unreachable precision target yields no threshold rather than a false promise`() {
        // All labels false: no threshold can ever reach any positive precision target.
        val scores = List(100) { it / 100f }
        val labels = List(100) { false }
        val result = ThresholdDerivation.forPrecisionTarget(scores, labels, precisionTarget = 0.5f)
        assertTrue(result == null)
    }
}
