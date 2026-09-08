package org.ort.app.ui.setup

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner

/** R-080 (ui-conformance-plan WP9) — `Setup-Welcome.dc.html` (S01). */
@RunWith(RobolectricTestRunner::class)
class WelcomeScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `R_080 shows the five asks and Begin, tapping Begin invokes the callback`() {
        var begun = false
        composeTestRule.setContent {
            OrtTheme { WelcomeScreen(onBegin = { begun = true }) }
        }

        composeTestRule.onNodeWithText("Microphone and notifications").assertIsDisplayed()
        // The 470dp Robolectric viewport does not fit every row without scrolling (as a small real
        // device would not either) -- the content column scrolls (guide's "rows grow" floor), so
        // this scrolls the row into view first, same as a real operator would.
        composeTestRule.onNodeWithText("A radio to read the frequency from").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("setup-welcome-begin").performClick()
        assert(begun)
    }

    @Test
    fun `R_080 What is captured opens a Sheet with the offline-promise copy`() {
        composeTestRule.setContent {
            OrtTheme { WelcomeScreen(onBegin = {}) }
        }

        composeTestRule.onNodeWithTag("setup-welcome-what-is-captured").performClick()

        composeTestRule.onNodeWithTag("setup-welcome-sheet").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "Nothing is deleted quietly. Every attribution carries its confidence. A weaker phone " +
                "knows less; it is never more wrong.",
        ).assertIsDisplayed()
    }
}
