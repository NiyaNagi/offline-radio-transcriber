package org.ort.app.ui.setup

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
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
        composeTestRule.onNodeWithTag("setup-overnight-state-row").performScrollTo().assertIsDisplayed()
    }

    /**
     * **R-1172** (register; constitution I): the state row used to read *"Not yet exempt — this
     * never blocks capture"*, hardcoded and never recomputed, so it reported *not yet exempt* on a
     * device that was already exempt — an invented specific about the exact fact the screen exists
     * to establish. Asserted on the literal string because the register row names that literal
     * string as the defect; the honest half of the sentence keeps its own coverage above, through
     * the row's tag.
     */
    @Test
    fun `R_1172 the state row asserts no exemption reading it has not actually taken`() {
        composeTestRule.setContent {
            OrtTheme { OvernightScreen(onOpenSetting = {}, onSkip = {}) }
        }

        composeTestRule.onAllNodesWithText("Not yet exempt — this never blocks capture").assertCountEquals(0)
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

    /**
     * **R-1162**: a step that can be shown *after* setup is complete must offer a way back into the
     * app, not only a forward action. The exit is absent by default — on the first-run walk there
     * is genuinely nowhere to go back to, which is what `onBack = null` was always right about —
     * and appears only when the caller supplies it.
     */
    @Test
    fun `R_1162 the return-to-the-app exit is absent on the first-run walk`() {
        composeTestRule.setContent {
            OrtTheme { OvernightScreen(onOpenSetting = {}, onSkip = {}) }
        }

        composeTestRule.onAllNodesWithTag("setup-overnight-return-to-app").assertCountEquals(0)
    }

    @Test
    fun `R_1162 the return-to-the-app exit invokes its own callback when the caller supplies one`() {
        var returned = false
        composeTestRule.setContent {
            OrtTheme { OvernightScreen(onOpenSetting = {}, onSkip = {}, onReturnToApp = { returned = true }) }
        }

        composeTestRule.onNodeWithTag("setup-overnight-return-to-app").performClick()
        assert(returned)
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
        composeTestRule.onNodeWithTag("setup-overnight-state-row").performScrollTo().assertIsDisplayed()
    }

    /**
     * **R-1162**, the layout half (working agreement 7 / constitution VIII: a row or column change
     * gets a bounds test at the tour's own width, native graphics). The exit is a *third* action in
     * a bar that had two, and the bar is measured before the content it leaves room for — so at the
     * operator's maximum font scale the thing to prove is that all three actions are still fully on
     * screen — a bar that grows past the viewport would push the exit off the bottom edge, which is
     * precisely the failure this row exists to end.
     */
    @Test
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `R_1162 all three actions sit fully on screen at font scale 2_0`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrtTheme { OvernightScreen(onOpenSetting = {}, onSkip = {}, onReturnToApp = {}) }
            }
        }

        val root = composeTestRule.onNodeWithTag("setup-screen-OVERNIGHT").fetchSemanticsNode().boundsInRoot
        listOf("setup-overnight-open-setting", "setup-overnight-skip", "setup-overnight-return-to-app")
            .forEach { tag ->
                val bounds = composeTestRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                assert(bounds.top >= root.top && bounds.bottom <= root.bottom) {
                    "expected $tag ($bounds) to sit inside the screen ($root) at font scale 2.0"
                }
            }
    }
}
