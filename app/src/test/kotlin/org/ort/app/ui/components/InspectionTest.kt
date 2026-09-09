package org.ort.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner

/** R-023 (ui-conformance-plan WP2): `Detail.dc.html`'s waveform card, lattice slot and prior bar. */
@RunWith(RobolectricTestRunner::class)
class InspectionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `the four waveform card states render distinctly`() {
        val bars = listOf(WaveformBar(0.5f, true), WaveformBar(0.2f, false), WaveformBar(0.8f, true))
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    WaveformCard(state = WaveformViewState.NoAudio, modifier = Modifier.testTag("no-audio"))
                    WaveformCard(
                        state = WaveformViewState.Unavailable("Playback unavailable: file missing"),
                        modifier = Modifier.testTag("unavailable"),
                    )
                    WaveformCard(
                        state = WaveformViewState.Idle(bars, "4.2s"),
                        onPlayPause = {},
                        modifier = Modifier.testTag("idle"),
                    )
                    WaveformCard(
                        state = WaveformViewState.Playing(bars, 0.4f, "1.6s", "4.2s"),
                        onPlayPause = {},
                        modifier = Modifier.testTag("playing"),
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("No retained audio for this transmission").assertIsDisplayed()
        composeTestRule.onNodeWithText("Playback unavailable: file missing").assertIsDisplayed()
        composeTestRule.onNodeWithText("4.2s").assertIsDisplayed()
        composeTestRule.onNodeWithText("1.6s / 4.2s").assertIsDisplayed()
    }

    @Test
    fun `a playing waveform's play control is a 44dp-ish real target with a role`() {
        val bars = listOf(WaveformBar(0.5f, true))
        var toggled = false
        composeTestRule.setContent {
            OrtTheme {
                WaveformCard(state = WaveformViewState.Idle(bars, "4.2s"), onPlayPause = { toggled = true })
            }
        }

        composeTestRule.onNode(hasContentDescription("Play retained audio")).performClick()
        assert(toggled)
    }

    @Test
    fun `R_054_scrubFraction converts an x position into a 0 to 1 fraction so playback can seek`() {
        assert(scrubFraction(0f, 200f) == 0f)
        assert(scrubFraction(200f, 200f) == 1f)
        assert(scrubFraction(100f, 200f) == 0.5f)
        // Never out of bounds, and never a crash on a not-yet-laid-out (zero-width) waveform.
        assert(scrubFraction(-50f, 200f) == 0f)
        assert(scrubFraction(500f, 200f) == 1f)
        assert(scrubFraction(100f, 0f) == 0f)
    }

    @Test
    fun `a waveform with onScrub set renders without crashing, exactly like one with it unset`() {
        val bars = listOf(WaveformBar(0.5f, true), WaveformBar(0.6f, true), WaveformBar(0.3f, false))
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    WaveformCard(
                        state = WaveformViewState.Idle(bars, "4.2s"),
                        onPlayPause = {},
                        onScrub = {},
                        modifier = Modifier.testTag("with-scrub"),
                    )
                    WaveformCard(
                        state = WaveformViewState.Idle(bars, "4.2s"),
                        onPlayPause = {},
                        modifier = Modifier.testTag("without-scrub"),
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("with-scrub").assertIsDisplayed()
        composeTestRule.onNodeWithTag("without-scrub").assertIsDisplayed()
    }

    @Test
    fun `a lattice slot below threshold is described as such and a kept alternate is announced`() {
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    LatticeSlot(LatticeSlotViewState(unit = "K", score = 0.96), modifier = Modifier.testTag("ok"))
                    LatticeSlot(
                        LatticeSlotViewState(unit = "W", score = 0.64, belowThreshold = true, alternate = "M"),
                        modifier = Modifier.testTag("uncertain"),
                    )
                }
            }
        }

        composeTestRule.onNode(hasContentDescription("below threshold", substring = true)).assertIsDisplayed()
        composeTestRule.onNode(hasContentDescription("alternate M", substring = true)).assertIsDisplayed()
    }

    @Test
    fun `a prior that abstained reads cold start rather than a fabricated zero`() {
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    PriorBar(PriorBarViewState(name = "Heard acoustically", fillFraction = 0.84f, valueLabel = "+3.1"))
                    PriorBar(PriorBarViewState(name = "In the FCC database", fillFraction = null, valueLabel = null))
                }
            }
        }

        composeTestRule.onNodeWithText("+3.1").assertIsDisplayed()
        composeTestRule.onNodeWithText("cold start").assertIsDisplayed()
        composeTestRule.onNodeWithText("0.00").assertDoesNotExist()
    }

    // ---- R-323: D05's prior rows never wrap mid-word; the bar/value reflow beneath a long label ----

    private val longPriorName = "Heard acoustically on this exact repeater before"

    private fun setPriorBarAtScale(fontScale: Float) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = fontScale)) {
                OrtTheme {
                    PriorBar(PriorBarViewState(name = longPriorName, fillFraction = 0.84f, valueLabel = "+3.1"))
                }
            }
        }
    }

    /** `PriorBar`'s own outer container merges its descendants into one semantics node (its real
     * `contentDescription`) — reading each text's *individual* rendered position needs the
     * unmerged tree, the same way `ActivityPatternChartTest`'s own `R_271` axis tests do. */
    private fun unmergedBounds(text: String) =
        composeTestRule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    @Test
    fun `R_323_the_label_never_wraps_mid_word_at_a_large_font_scale`() {
        setPriorBarAtScale(2.0f)

        // A wrapped label reports a taller-than-one-line box; `assertIsDisplayed` alone would pass
        // either way, so this reads the real rendered height instead — the same proof
        // `R_271`'s own hour-axis test (`ActivityPatternChartTest.kt`) already established for
        // exactly this class of defect.
        composeTestRule.onNodeWithText(longPriorName, useUnmergedTree = true).assertIsDisplayed()
        val labelHeight = unmergedBounds(longPriorName).height
        val oneLineHeight = unmergedBounds("+3.1").height
        assertTrue(
            "label height $labelHeight looks wrapped past one line (single line is ~$oneLineHeight)",
            labelHeight <= oneLineHeight * 1.5f,
        )
    }

    @Test
    fun `R_323_below_the_threshold_the_bar_and_value_share_the_labels_own_row`() {
        setPriorBarAtScale(1.0f)

        val labelTop = unmergedBounds(longPriorName).top
        val valueTop = unmergedBounds("+3.1").top
        assertEquals("expected the value beside the label below the large-scale threshold", labelTop, valueTop)
    }

    @Test
    fun `R_323_at_a_large_font_scale_the_bar_and_value_reflow_beneath_the_label_instead_of_beside_it`() {
        setPriorBarAtScale(2.0f)

        val labelBottom = unmergedBounds(longPriorName).bottom
        val valueTop = unmergedBounds("+3.1").top
        assertTrue(
            "expected the value below the label at a large font scale, label bottom=$labelBottom, " +
                "value top=$valueTop",
            valueTop >= labelBottom,
        )
    }

    // ---- R-561: PriorBarValue's own figure never clips at a large font scale ----

    @Test
    fun `R_561_the_prior_figure_renders_its_full_string_uncut_at_a_large_font_scale`() {
        // WP6's own repro (`overnight/D02-inferred@2x-end.png`): "+0.85"/"-0.12" rendered clipped
        // to "+0."/"-0." — `PriorBarValue`'s own `widthIn(min = 30.dp, max = 34.dp)` gave the figure
        // a hard *ceiling* even though it's `softWrap = false, maxLines = 1` (nowhere to wrap the
        // overflow to) and `OrtType.signal` grows with font scale like every other row in this
        // package.
        //
        // A genuine host limit, found and disclosed while writing this test, not assumed: a pixel-
        // width comparison against the old 34dp ceiling cannot discriminate the fixed and unfixed
        // code *on this host* — this package's own already-established finding
        // (`RowsTest.kt`'s `assertColumnsDoNotCollide` doc comment; `LogRowResponsiveTest.kt`'s own
        // `R_373` doc comment) that this host's Robolectric font metrics are degenerate and do not
        // scale with `fontScale` the way a real device's do. Confirmed directly, not assumed: even a
        // 15-character literal ("+0.850000000000") still measured exactly the 30dp *floor* here,
        // both before and after this fix — this host simply never asks for more than that floor,
        // with or without a `max` present, so there is nothing this host's own measurement pass can
        // show as "no longer capped". What this test pins instead — real and checkable regardless of
        // this host's own glyph metrics — is that the figure's full text reaches the semantics tree
        // unclipped (`onNodeWithText` finds the *entire* string, not a truncated prefix): a
        // regression guard against a future `TextOverflow.Ellipsis`/similar creeping back in, even
        // though this alone would not have caught the *original* R-561 defect (a paint-level clip,
        // not a data-level truncation, which is exactly why the register's own confirmation was a
        // screenshot, not a semantics query — this round's own `emulator-5554` capture, in
        // `CHANGELOG.md`, is what actually proves the pixel claim).
        val valueLabel = "+0.850000000000"
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2.0f)) {
                OrtTheme {
                    val state = PriorBarViewState(
                        name = "Heard acoustically",
                        fillFraction = 0.84f,
                        valueLabel = valueLabel,
                    )
                    PriorBar(state)
                }
            }
        }

        composeTestRule.onNodeWithText(valueLabel).assertIsDisplayed()
    }
}
