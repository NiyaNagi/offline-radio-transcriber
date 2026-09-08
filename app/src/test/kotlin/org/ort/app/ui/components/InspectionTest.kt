package org.ort.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
}
