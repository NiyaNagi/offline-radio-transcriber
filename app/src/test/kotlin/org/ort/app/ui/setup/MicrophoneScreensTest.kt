package org.ort.app.ui.setup

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner

/** R-080/R-085 (ui-conformance-plan WP9) — `Setup-Mic.dc.html`/`Setup-Mic-Denied.dc.html`
 * (S02/S02b), re-homed from `MainActivity` onto [SetupScaffold]. */
@RunWith(RobolectricTestRunner::class)
class MicrophoneScreensTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `R_080 MicrophoneScreen shows Allow microphone and invokes onAllow`() {
        var allowed = false
        composeTestRule.setContent {
            OrtTheme { MicrophoneScreen(onAllow = { allowed = true }) }
        }
        composeTestRule.onNodeWithText("Microphone").assertIsDisplayed()
        composeTestRule.onNodeWithTag("setup-mic-allow").performClick()
        assert(allowed)
    }

    @Test
    fun `R_085 MicrophoneDeniedScreen shows the halt banner and both actions`() {
        var openedSettings = false
        var checkedAgain = false
        composeTestRule.setContent {
            OrtTheme {
                MicrophoneDeniedScreen(
                    onOpenSettings = { openedSettings = true },
                    onCheckAgain = { checkedAgain = true },
                )
            }
        }
        composeTestRule.onNodeWithText("Microphone refused").assertIsDisplayed()
        composeTestRule.onNodeWithText("Android will not ask again").assertIsDisplayed()
        composeTestRule.onNodeWithTag("setup-mic-denied-open-settings").performClick()
        assert(openedSettings)
        composeTestRule.onNodeWithTag("setup-mic-denied-check-again").performClick()
        assert(checkedAgain)
    }

    /** R-220 (validator finding, register R-220..R-227): a ghost, semi-transparent copy of the
     * bottom bar's secondary text action rendered near the status bar on every two-action
     * screen — this asserts exactly one "Check again" exists in the semantics tree, never two. */
    @Test
    fun `R_220 exactly one Check again renders, never a ghost duplicate`() {
        composeTestRule.setContent {
            OrtTheme { MicrophoneDeniedScreen(onOpenSettings = {}, onCheckAgain = {}) }
        }
        composeTestRule.onAllNodesWithText("Check again").assertCountEquals(1)
    }

    /** R-223 (validator pass 2): `Setup-Mic-Denied.dc.html` draws the header chevron like every
     * other step; it was rendered nowhere at all before this. */
    @Test
    fun `R_223 the back chevron renders and invokes onBack`() {
        var backed = false
        composeTestRule.setContent {
            OrtTheme {
                MicrophoneDeniedScreen(onOpenSettings = {}, onCheckAgain = {}, onBack = { backed = true })
            }
        }
        composeTestRule.onNodeWithTag("setup-back").performClick()
        assert(backed)
    }
}
