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

/** R-083 (ui-conformance-plan WP9) — `Setup-Battery.dc.html` (S08): the rationale states the API
 * lies before either action fires, and the state row says this never blocks capture. */
@RunWith(RobolectricTestRunner::class)
class OvernightScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `R_083 states the API lies and never blocks capture, before either action is taken`() {
        composeTestRule.setContent {
            OrtTheme { OvernightScreen(onOpenSetting = {}, onSkip = {}) }
        }

        composeTestRule.onNodeWithText(
            "This is less likely, not guaranteed. On some devices the setting reports exempt " +
                "and the OS ends the app anyway. The app never trusts that report — it proves " +
                "it is alive by heartbeat, and shows you a gap if it was not.",
        ).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Not yet exempt — this never blocks capture")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `R_083 Open the setting and Skip for now both invoke their own callback`() {
        var opened = false
        var skipped = false
        composeTestRule.setContent {
            OrtTheme { OvernightScreen(onOpenSetting = { opened = true }, onSkip = { skipped = true }) }
        }

        composeTestRule.onNodeWithTag("setup-overnight-open-setting").performClick()
        assert(opened)
        composeTestRule.onNodeWithTag("setup-overnight-skip").performClick()
        assert(skipped)
    }
}
