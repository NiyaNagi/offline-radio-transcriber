package org.ort.app.ui.failures

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.testing.Requirement

/**
 * Register R-447: [isOverConfident] (`FailCalibration.kt`) is the pure colour decision pulled out
 * of `ReliabilityChart`'s `Canvas` draw scope precisely so it is directly testable — no
 * Android/Compose dependency, so plain JUnit5 like [FailureMapperTest].
 */
class FailCalibrationTest {

    /** `Fail-Calibration.dc.html`'s own five example points — every one is numerically below the
     * diagonal (`actuallyRight < score`), yet the board colours only the three whose deviation is
     * large (0.18/0.20/0.20) amber, and the two whose deviation is small (0.08/0.12) green. A bare
     * `actuallyRight < score` check (the bug the review caught) reads all five as amber. */
    @Test
    @Requirement("R-447")
    fun `R_447 the board's own two low-score, small-deviation points stay green`() {
        assertFalse(isOverConfident(score = 0.30f, actuallyRight = 0.22f)) // deviation 0.08
        assertFalse(isOverConfident(score = 0.50f, actuallyRight = 0.38f)) // deviation 0.12
    }

    @Test
    @Requirement("R-447")
    fun `R_447 the board's own three high-score, large-deviation points read amber`() {
        assertTrue(isOverConfident(score = 0.70f, actuallyRight = 0.52f)) // deviation 0.18
        assertTrue(isOverConfident(score = 0.86f, actuallyRight = 0.66f)) // deviation 0.20
        assertTrue(isOverConfident(score = 0.94f, actuallyRight = 0.74f)) // deviation 0.20
    }

    @Test
    @Requirement("R-447")
    fun `R_447 a point on or above the diagonal is never over-confident`() {
        assertFalse(isOverConfident(score = 0.50f, actuallyRight = 0.50f))
        assertFalse(isOverConfident(score = 0.50f, actuallyRight = 0.60f))
    }

    @Test
    @Requirement("R-447")
    fun `R_447 the split sits exactly at the board's own threshold, never a coincidence of round numbers`() {
        // Strictly between the largest still-green deviation (0.12) and the smallest amber one
        // (0.18) the board itself draws — not derived from any other constant in this file.
        assertFalse(isOverConfident(score = 0.50f, actuallyRight = 0.36f)) // deviation 0.14 — still green
        assertTrue(isOverConfident(score = 0.50f, actuallyRight = 0.34f)) // deviation 0.16 — now amber
    }
}
