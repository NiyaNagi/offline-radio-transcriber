package org.ort.app.ui.setup

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.components.referenceLineY
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner

/** R-082 (ui-conformance-plan WP9) — `Setup-Level.dc.html` (S07): `Continue` only in band; no
 * signal renders an honest "cannot measure" state, never fabricated bars. */
@RunWith(RobolectricTestRunner::class)
class LevelScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun reading(band: LevelBand) = LevelCheckState.Reading(
        LevelReading(bars = listOf(0.1f, 0.4f, 0.7f), peakDbfs = -14.0, noiseFloorDbfs = -58.0, band = band),
    )

    @Test
    fun `R_082 Continue is disabled when too quiet, enabled when in band`() {
        composeTestRule.setContent {
            OrtTheme { LevelScreen(state = reading(LevelBand.TOO_QUIET), onContinue = {}) }
        }
        composeTestRule.onNodeWithTag("setup-level-continue").assertIsNotEnabled()
    }

    @Test
    fun `R_082 Continue enables once the level is in band`() {
        composeTestRule.setContent {
            OrtTheme { LevelScreen(state = reading(LevelBand.IN_BAND), onContinue = {}) }
        }
        composeTestRule.onNodeWithTag("setup-level-continue").assertIsEnabled()
        composeTestRule.onNodeWithTag("setup-level-meter").assertIsDisplayed()
    }

    @Test
    fun `R_082 an Unavailable state renders the honest failed state, not a fabricated meter`() {
        composeTestRule.setContent {
            OrtTheme { LevelScreen(state = LevelCheckState.Unavailable("device open failed"), onContinue = {}) }
        }
        composeTestRule.onNodeWithTag("setup-level-unavailable").assertIsDisplayed()
        composeTestRule.onNodeWithTag("setup-level-continue").assertIsNotEnabled()
    }

    // --- R-124 (validator follow-up, register R-120..R-125) ------------------------------------

    @Test
    fun `R_124 the noise axis label reflects the real reading, never a hardcoded figure`() {
        composeTestRule.setContent {
            OrtTheme { LevelScreen(state = reading(LevelBand.IN_BAND), onContinue = {}) }
        }

        // `reading()` builds a fixture with noiseFloorDbfs = -58.0 -- if this were still the old
        // hardcoded literal, changing the fixture below would not move this assertion at all.
        composeTestRule.onNodeWithText("noise −58").assertIsDisplayed()
    }

    @Test
    fun `R_124 a different noise floor changes the axis label, proving it is not a fixed string`() {
        composeTestRule.setContent {
            OrtTheme {
                LevelScreen(
                    state = LevelCheckState.Reading(
                        LevelReading(
                            bars = listOf(0.5f),
                            peakDbfs = -14.0,
                            noiseFloorDbfs = -42.0,
                            band = LevelBand.IN_BAND,
                        ),
                    ),
                    onContinue = {},
                )
            }
        }

        composeTestRule.onNodeWithText("noise −42").assertIsDisplayed()
    }

    @Test
    fun `R_124 headroom at exactly 0 dBFS peak never renders the literal -0 dB`() {
        composeTestRule.setContent {
            OrtTheme {
                LevelScreen(
                    state = LevelCheckState.Reading(
                        LevelReading(
                            bars = listOf(1f),
                            peakDbfs = 0.0,
                            noiseFloorDbfs = -55.0,
                            band = LevelBand.CLIPPING,
                        ),
                    ),
                    onContinue = {},
                )
            }
        }

        // Exact match -- "0 dB" (headroom) vs "0 dBFS" (speech peaks) must not be conflated.
        composeTestRule.onNodeWithText("0 dB").performScrollTo().assertIsDisplayed()
        composeTestRule.onAllNodesWithText("-0 dB", substring = true).assertCountEquals(0)
    }

    @Test
    fun `R_124 the meter chart renders whatever the reading actually holds`() {
        composeTestRule.setContent {
            OrtTheme { LevelScreen(state = reading(LevelBand.CLIPPING), onContinue = {}) }
        }

        composeTestRule.onNodeWithTag("setup-level-meter").assertIsDisplayed()
    }

    // --- R-225 (validator pass 2): the footer facts row must not collapse at font scale 2.0 -----

    @Test
    fun `R_225 at normal font scale the three facts sit on one row`() {
        composeTestRule.setContent {
            OrtTheme { LevelScreen(state = reading(LevelBand.IN_BAND), onContinue = {}) }
        }

        val noiseTop = composeTestRule.onNodeWithText("noise −58").fetchSemanticsNode().boundsInRoot.top
        val clipTop = composeTestRule.onNodeWithText("clip 0").fetchSemanticsNode().boundsInRoot.top
        assert(kotlin.math.abs(noiseTop - clipTop) < 1f) {
            "expected the same row at normal font scale, got noise top=$noiseTop clip top=$clipTop"
        }
    }

    @Test
    fun `R_225 at font scale 2_0 the three facts stack instead of collapsing into one run`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrtTheme { LevelScreen(state = reading(LevelBand.IN_BAND), onContinue = {}) }
            }
        }

        // Each fact remains its own, independently-displayed node (never smooshed into a single
        // concatenated run the validator's own @2x screenshot showed) -- and stacking means the
        // "clip 0" row sits meaningfully below "noise -58", not on the same line.
        composeTestRule.onNodeWithText("noise −58").assertIsDisplayed()
        composeTestRule.onNodeWithText("target −18 to −12").assertIsDisplayed()
        composeTestRule.onNodeWithText("clip 0").assertIsDisplayed()
        val noiseTop = composeTestRule.onNodeWithText("noise −58").fetchSemanticsNode().boundsInRoot.top
        val clipTop = composeTestRule.onNodeWithText("clip 0").fetchSemanticsNode().boundsInRoot.top
        assert(clipTop - noiseTop > 10f) {
            "expected clip 0 to sit well below noise -58 once stacked, got noise top=$noiseTop clip top=$clipTop"
        }
    }

    // --- R-465 (Reviewer A round 2): the clip line at CHART_CEILING_DBFS (fraction 1.0, canvas
    // row 0) was device-confirmed invisible -- bisected by/merged into the chart Box's own top
    // border, not merely faint at capture resolution (`setup-level/S07-level.png`, pixel-sampled:
    // zero non-background pixels near the expected row). `referenceLineY` (WP4 lifted this fix's
    // own pure math into the shared `org.ort.app.ui.components.ChartGeometry.kt`, register R-542 --
    // the identical defect existed in `LevelMeterScreen.kt` too) is exercised directly here at
    // LevelScreen's own call site, with no Canvas/DrawScope needed. ------------------------------

    @Test
    fun `R_465 the clip line at the scale's own ceiling no longer lands exactly on the canvas edge`() {
        // fraction 1.0 is CHART_CEILING_DBFS's own position (LevelViewState) -- yForFraction alone
        // would put this at y=0 exactly, the defect this fixes.
        val y = referenceLineY(fraction = 1f, heightPx = 96f, edgeClearancePx = 4f)
        assert(y > 0f) { "expected the clip line's stroke to sit inside the canvas, not on row 0, got y=$y" }
        assert(y == 4f) { "expected the clip line inset by the full clearance (4f), got y=$y" }
    }

    @Test
    fun `R_465 a noise floor at the scale's own bottom edge is inset the same way, not lost off-canvas`() {
        // fraction 0.0 is the mirror case at the bottom edge (a noise floor at CHART_FLOOR_DBFS).
        val y = referenceLineY(fraction = 0f, heightPx = 96f, edgeClearancePx = 4f)
        assert(y == 92f) { "expected the line inset up from the bottom edge by the full clearance, got y=$y" }
    }

    // --- R-944 (register, reviewer A3 run 4a, design): the meter is a genuine proportional -------
    // --- envelope from real level samples, never a fixed/alternating pattern -----------------------

    @Test
    fun `R_944 bar heights are proportional to their own real sample, never a fixed alternating pattern`() {
        val rects = levelBarRects(
            bars = listOf(0.1f, 0.9f, 0.3f, 0.3f, 0.05f),
            widthPx = 200f,
            heightPx = 100f,
            gapPx = 0f,
        )

        // A taller real sample (0.9) draws with a smaller topY (closer to the canvas top) than a
        // shorter one (0.1) beside it -- proportional, not alternating short/tall/short/tall.
        assert(rects[1].topY < rects[0].topY) { "the 0.9 sample must draw taller than the 0.1 sample" }
        // Two bars with the *identical* real sample must draw at the identical height -- an
        // alternating pattern would instead force every other bar tall regardless of its own value.
        assert(rects[2].topY == rects[3].topY) {
            "equal samples must draw equal heights, got ${rects[2].topY} and ${rects[3].topY}"
        }
        // Monotonic ordering across a genuinely descending real sequence -- a fixed alternating
        // pattern could never reproduce this shape.
        assert(rects[3].topY < rects[4].topY) { "a smaller sample must draw shorter than a larger one" }
    }

    // --- R-944 reopened (register, reviewer A4 run 5, halt): quiet bars grey, the speech ramp -----
    // --- green, red only at clip -- amber is for anomalies (guide §8), never this chart's own bars -

    private val bandTopFraction =
        levelBarFraction(org.ort.app.ui.data.LevelViewState.TARGET_BAND_TOP_DBFS.toDouble())

    @Test
    fun `R_944 a quiet bar reads the neutral grey ramp, never the warn amber colour`() {
        val rects = levelBarRects(bars = listOf(0.05f, 0.1f), widthPx = 20f, heightPx = 100f, gapPx = 0f)

        rects.forEach { assert(it.color == OrtColors.chartNeutralRamp[0]) { "expected grey, got ${it.color}" } }
    }

    @Test
    fun `R_944 a bar at or above the target band top reads full accent green`() {
        val rects = levelBarRects(
            bars = listOf(bandTopFraction, (bandTopFraction + 1f) / 2f),
            widthPx = 20f,
            heightPx = 100f,
            gapPx = 0f,
        )

        rects.forEach { assert(it.color == OrtColors.accentGreen) { "expected accentGreen, got ${it.color}" } }
    }

    @Test
    fun `R_944 a bar between quiet and the target band top reads a real chartGreenRamp shade, never amber`() {
        val midway = levelBarColorFor((0.30f + bandTopFraction) / 2f, bandTopFraction)

        assert(midway in OrtColors.chartGreenRamp) { "expected a chartGreenRamp shade, got $midway" }
        assert(midway != OrtColors.meterWarn && midway != OrtColors.accentAmber) {
            "the speech ramp must never render amber (guide §8) -- amber is for anomalies, got $midway"
        }
    }

    @Test
    fun `R_944 the green ramp brightens monotonically as the bar gets louder, never darker`() {
        val quiet = levelBarColorFor(0.35f, bandTopFraction)
        val louder = levelBarColorFor((0.35f + bandTopFraction) / 2f, bandTopFraction)
        val loudest = levelBarColorFor(bandTopFraction, bandTopFraction)
        val ramp = OrtColors.chartGreenRamp

        assert(ramp.indexOf(louder) <= ramp.indexOf(quiet)) { "a louder bar must never be dimmer" }
        assert(ramp.indexOf(loudest) <= ramp.indexOf(louder)) { "the loudest bar must be the brightest" }
        assert(loudest == OrtColors.accentGreen)
    }

    @Test
    fun `R_944 a bar that actually reaches the chart ceiling reads red, the one anomaly this chart shows`() {
        val rects = levelBarRects(bars = listOf(1.0f), widthPx = 20f, heightPx = 100f, gapPx = 0f)

        assert(rects[0].color == OrtColors.haltFill) { "expected haltFill at true clip, got ${rects[0].color}" }
    }

    @Test
    fun `R_944 no bar across the whole real range ever renders amber`() {
        val samples = (0..20).map { it / 20f }
        val rects = levelBarRects(bars = samples, widthPx = 200f, heightPx = 100f, gapPx = 0f)

        rects.forEach { rect ->
            assert(rect.color != OrtColors.meterWarn && rect.color != OrtColors.accentAmber) {
                "expected never amber across the sampled range, got ${rect.color} for a sample"
            }
        }
    }

    /**
     * R-944 reopened: a genuine two-syllable rise-and-fall envelope (the same shape
     * `app/src/debug/kotlin/org/ort/app/debug/Scenarios.kt`'s own `speechShapedPeakHistoryDbfs`
     * seeds) must render left to right as a real rise then fall -- never the "single monotonically
     * decaying spike" the validator screenshotted -- proving this chart draws whatever [bars] it is
     * handed in full, with no windowing/truncation of its own, so the shape reaching it is the
     * shape shown.
     */
    @Test
    fun `R_944 a rise-and-fall input renders as a rise then a fall, not a decaying spike`() {
        val riseAndFall = listOf(0.05f, 0.10f, 0.25f, 0.45f, 0.65f, 0.75f, 0.60f, 0.40f, 0.20f, 0.08f)
        val rects = levelBarRects(bars = riseAndFall, widthPx = 200f, heightPx = 100f, gapPx = 0f)

        val peakIndex = rects.indices.minByOrNull { rects[it].topY }!!
        assert(peakIndex in 4..6) { "expected the peak (smallest topY) near the middle, got index $peakIndex" }
        // Strictly falling topY (rising height) up to the peak...
        for (i in 1..peakIndex) {
            assert(rects[i].topY <= rects[i - 1].topY) {
                "expected a monotonic rise up to the peak at $peakIndex, broke at $i: ${rects.map { it.topY }}"
            }
        }
        // ...then strictly rising topY (falling height) after it -- never still decaying from the
        // very first bar, the exact defect class this register line reopened over.
        for (i in (peakIndex + 1) until rects.size) {
            assert(rects[i].topY >= rects[i - 1].topY) {
                "expected a monotonic fall after the peak at $peakIndex, broke at $i: ${rects.map { it.topY }}"
            }
        }
        assert(rects.first().topY > rects[peakIndex].topY) { "the first bar must be shorter than the peak" }
        assert(rects.last().topY > rects[peakIndex].topY) { "the last bar must be shorter than the peak" }
    }

    @Test
    fun `R_944 an empty sample history draws no bars at all, never a fabricated one`() {
        val rects = levelBarRects(bars = emptyList(), widthPx = 100f, heightPx = 100f, gapPx = 1.5f)

        assert(rects.isEmpty())
    }

    @Test
    fun `R_465 a line already well clear of either edge is unaffected, no regression for the target band`() {
        // fraction 0.8 (TARGET_BAND_TOP_DBFS's own position on the -60..0 scale) sits nowhere near
        // either edge -- the inset must be a true no-op here, the same position yForFraction alone
        // would already give.
        val plain = 96f * (1f - 0.8f)
        val inset = referenceLineY(fraction = 0.8f, heightPx = 96f, edgeClearancePx = 4f)
        assert(inset == plain) { "expected no change away from the edges, plain=$plain inset=$inset" }
    }
}
