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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** P22 (NFR-6c, AC-166) — `SetupStep.JURISDICTION_NOTICE`, shown exactly once on first run. No
 * artboard exists yet for this screen (outside this unit's `design` tree ownership); these tests
 * cover the real content and the one action, matching this package's established per-screen
 * pattern (e.g. [OvernightScreenTest]). */
@RunWith(RobolectricTestRunner::class)
class JurisdictionNoticeScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `AC_166 states that legality varies by jurisdiction and never claims a specific law`() {
        composeTestRule.setContent {
            OrtTheme { JurisdictionNoticeScreen(onContinue = {}) }
        }

        composeTestRule.onNodeWithText(
            "Rules on recording amateur and scanner radio traffic — and on retaining or " +
                "sharing what you capture — differ by country, state and sometimes locality. This " +
                "app does not know which rules apply where you are.",
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `AC_166 I understand invokes onContinue`() {
        var continued = false
        composeTestRule.setContent {
            OrtTheme { JurisdictionNoticeScreen(onContinue = { continued = true }) }
        }

        composeTestRule.onNodeWithTag("setup-jurisdiction-continue").performClick()
        assert(continued)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `AC_166 the continue action is reachable at font scale 2_0 at the tour's own width and density`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrtTheme { JurisdictionNoticeScreen(onContinue = {}) }
            }
        }

        composeTestRule.onNodeWithTag("setup-jurisdiction-continue").assertIsDisplayed()
    }
}
