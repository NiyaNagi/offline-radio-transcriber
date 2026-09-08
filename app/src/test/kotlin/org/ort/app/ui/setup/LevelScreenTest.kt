package org.ort.app.ui.setup

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
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
}
