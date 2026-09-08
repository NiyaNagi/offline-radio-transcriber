package org.ort.app.ui.setup

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
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

    /** R-283 (validator pass 3): at font scale 2.0 numbered step 1 was clipped by the viewport
     * above the fixed bar (`setup-verified/S08-battery@2x.png`) — `SetupScaffold`'s own shared
     * `weight(1f)` + `verticalScroll` mechanism already covers this screen like every other; this
     * is the per-screen regression proof `SetupScaffoldTest`'s generic version cannot stand in for,
     * matching `WelcomeScreenTest`'s/`InputScreenTest`'s own R-123 tests for S01/S04 — proving the
     * true last item (after step 1, by definition already scrolled past on the way to it) reaches
     * the viewport cleanly is the same "the whole column scrolls clear" proof those tests give. */
    @Test
    fun `R_283 the last content item scrolls clear of the fixed bar at font scale 2_0`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrtTheme { OvernightScreen(onOpenSetting = {}, onSkip = {}) }
            }
        }

        composeTestRule.onNodeWithText("Open the system setting").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Not yet exempt — this never blocks capture")
            .performScrollTo()
            .assertIsDisplayed()
    }
}
