package org.ort.app.ui.setup

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
}
