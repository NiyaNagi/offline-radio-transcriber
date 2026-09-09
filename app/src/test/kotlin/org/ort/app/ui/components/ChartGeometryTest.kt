package org.ort.app.ui.components

import org.junit.Test

/**
 * R-542 (register — WP9's own R-465 found this first, `LevelScreenTest`'s own tests): the shared
 * clearance math `LevelMeterScreen.kt`'s `LevelHistoryChart` now uses too, for the identical
 * `LevelScreen.kt` (S07) defect — a fixed reference line at fraction `0f`/`1f` lands exactly on the
 * canvas edge, bisected by the canvas bounds and overpainted by the chart's own border, so it never
 * actually renders. No Robolectric/Compose rule needed — [referenceLineY] is a plain function of
 * already-resolved pixel values, the same reasoning `LevelScreenTest`'s own `R_465` cases already
 * established for it before this file existed.
 */
class ChartGeometryTest {

    @Test
    fun `R_542 the clip line at fraction 1_0 no longer lands exactly on the canvas edge`() {
        // fraction 1.0 is LevelViewState.CHART_CEILING_DBFS's own position — the exact clip-line
        // defect R-542 was filed about (byte-identical to R-465's own finding on LevelScreen.kt).
        val y = referenceLineY(fraction = 1f, heightPx = 96f, edgeClearancePx = 4f)
        assert(y > 0f) { "expected the clip line's stroke to sit inside the canvas, not on row 0, got y=$y" }
        assert(y == 4f) { "expected the clip line inset by the full clearance (4f), got y=$y" }
    }

    @Test
    fun `R_542 a noise floor at fraction 0_0 is inset the same way, not lost off-canvas`() {
        val y = referenceLineY(fraction = 0f, heightPx = 96f, edgeClearancePx = 4f)
        assert(y == 92f) { "expected the line inset up from the bottom edge by the full clearance, got y=$y" }
    }

    @Test
    fun `R_542 a line already well clear of either edge is unaffected, no regression for the target band`() {
        // fraction 0.8 (roughly TARGET_BAND_TOP_DBFS's own position on the -60..0 scale) sits
        // nowhere near either edge — the inset must be a true no-op here.
        val plain = 96f * (1f - 0.8f)
        val inset = referenceLineY(fraction = 0.8f, heightPx = 96f, edgeClearancePx = 4f)
        assert(inset == plain) { "expected no change away from the edges, plain=$plain inset=$inset" }
    }

    @Test
    fun `R_542 a fraction outside 0f-1f is clamped before the clearance is applied`() {
        val aboveCeiling = referenceLineY(fraction = 1.4f, heightPx = 96f, edgeClearancePx = 4f)
        val belowFloor = referenceLineY(fraction = -0.4f, heightPx = 96f, edgeClearancePx = 4f)
        assert(aboveCeiling == 4f) { "expected a fraction above 1f to clamp to the same clip-line position" }
        assert(belowFloor == 92f) { "expected a fraction below 0f to clamp to the same floor-line position" }
    }
}
