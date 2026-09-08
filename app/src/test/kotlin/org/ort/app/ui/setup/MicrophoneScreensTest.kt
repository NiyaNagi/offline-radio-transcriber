package org.ort.app.ui.setup

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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
}
