package org.ort.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-arithmetic half of the scale finding (2026-09-09): every artboard under `design/canvas/`
 * is authored at a fixed 390dp-wide root (`design/design-guide.md` §4-5), so on a wider-in-dp
 * device the same dp-authored screens occupy proportionally less of the display —
 * `results/ui-audit-findx9/overnight/L01-log.png` (480dp) shows 13 rows where
 * `results/ui-audit-board390/overnight/L01-log.png` (390dp) shows 7. [ortScaleFor] is the
 * multiplier `OrtTheme` applies to device density so every screen lays out at the board's own
 * 390dp proportion regardless of the device's real width.
 *
 * Tested directly against the pure function, not only through a Robolectric-measured layout
 * (`OrtThemeScaleTest`), per `results/ui-audit/register.md` R-551/R-590: Robolectric's own
 * density/text measurement has bitten two other builders here, so the arithmetic must be
 * checkable independent of it.
 */
class OrtThemeScaleArithmeticTest {

    @Test
    fun `R_600 scale is 1_0 at exactly the board width, 390dp`() {
        assertEquals(1.0f, ortScaleFor(390f), TOLERANCE)
    }

    @Test
    fun `R_600 scale is 1_0 below the board width, 360dp`() {
        assertEquals(1.0f, ortScaleFor(360f), TOLERANCE)
    }

    @Test
    fun `R_600 scale is approximately 1_23 at 480dp, the Find X9 Ultra width`() {
        assertEquals(1.2307f, ortScaleFor(480f), TOLERANCE)
    }

    @Test
    fun `R_600 scale is approximately 1_05 at 411dp, the audit emulator width`() {
        assertEquals(1.0538f, ortScaleFor(411f), TOLERANCE)
    }

    @Test
    fun `R_600 scale clamps at 1_35 for a 600dp screen`() {
        assertEquals(1.35f, ortScaleFor(600f), TOLERANCE)
    }

    @Test
    fun `R_600 scale clamps at 1_35 well past 600dp too`() {
        assertEquals(1.35f, ortScaleFor(1200f), TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 0.001f
    }
}
