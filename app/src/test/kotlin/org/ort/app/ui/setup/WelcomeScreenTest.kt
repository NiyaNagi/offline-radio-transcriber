package org.ort.app.ui.setup

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.OfflinePromiseCopy
import org.ort.app.ui.theme.OrtTheme
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/** R-080 (ui-conformance-plan WP9) — `Setup-Welcome.dc.html` (S01). */
@RunWith(RobolectricTestRunner::class)
class WelcomeScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /**
     * **P39 (D58): the five-ask list is gone**, and this test is what is left of R-080's half of it.
     * Four of those five asks no longer exist as steps at all — the list was describing a flow that
     * has been deleted — so Welcome now carries one claim, two notices and a button. What R-080 was
     * actually establishing, that `Begin` exists and fires, is unchanged.
     */
    @Test
    fun `R_080 shows the two notices and Begin, tapping Begin invokes the callback`() {
        var begun = false
        composeTestRule.setContent {
            OrtTheme { WelcomeScreen(onBegin = { begun = true }) }
        }

        composeTestRule.onNodeWithTag("setup-welcome-jurisdiction-row").assertIsDisplayed()
        // The 470dp Robolectric viewport does not fit every row without scrolling (as a small real
        // device would not either) -- the content column scrolls, so this scrolls the row into view
        // first, same as a real operator would.
        composeTestRule.onNodeWithTag("setup-welcome-analytics-row").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("setup-welcome-begin").performClick()
        assert(begun)
    }

    /** Matches `FailureScreensTest`'s/`ReaderAccessibilityTest`'s own V7 font-scale pattern — see
     * `SetupScaffoldTest`'s identical note on why this is not `@Config(qualifiers = ...)`. */
    @Test
    fun `R_123 the last notice row scrolls clear of Begin at font scale 2_0`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrtTheme { WelcomeScreen(onBegin = {}) }
            }
        }

        composeTestRule.onNodeWithTag("setup-welcome-analytics-row").performScrollTo().assertIsDisplayed()
    }

    // --- AC-166 / AC-180 / AC-203: folded in, one tap away (P39, D58) ------------------------------

    /**
     * **AC-166 as amended by D58**: the jurisdiction notice is *shown and acknowledged before
     * capture*, not a step of its own. "Reachable" has to mean the operator can actually read the
     * whole thing, so this opens the row and asserts the real text is there — a row that merely said
     * the words "check your local law" and led nowhere would satisfy a weaker test and not the
     * criterion.
     */
    @Test
    @Requirement("NFR-6c", "AC-166")
    fun `AC_166 the jurisdiction notice's full text is one tap from Welcome`() {
        composeTestRule.setContent {
            OrtTheme { WelcomeScreen(onBegin = {}) }
        }

        composeTestRule.onNodeWithTag("setup-welcome-jurisdiction-row").performClick()

        composeTestRule.onNodeWithTag("setup-welcome-jurisdiction-sheet").assertIsDisplayed()
        composeTestRule.onNodeWithTag("setup-welcome-jurisdiction-body", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("setup-welcome-jurisdiction-point", useUnmergedTree = true).assertExists()
    }

    /**
     * **AC-180 as amended by D58**: the explanation and the two toggles are present and reachable
     * within the first-run flow. Unchecked-by-default is unchanged and is asserted here as the state
     * the operator actually meets — an opt-in that arrives pre-ticked is the one failure of this
     * criterion that matters.
     */
    @Test
    @Requirement("FR-ANL-10", "AC-180")
    fun `AC_180 the analytics explanation and its two unchecked toggles are one tap from Welcome`() {
        composeTestRule.setContent {
            OrtTheme { WelcomeScreen(onBegin = {}) }
        }

        composeTestRule.onNodeWithTag("setup-welcome-analytics-row").performScrollTo().performClick()

        composeTestRule.onNodeWithTag("setup-welcome-analytics-sheet").assertIsDisplayed()
        composeTestRule.onNodeWithTag("setup-welcome-analytics-body", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("Also share transcripts and callsigns").assertIsOff()
        composeTestRule.onNodeWithText("Also share audio").assertIsOff()
    }

    /** AC-180: flipping either toggle reports it, and neither is a gate — there is no `Continue` on
     * this sheet to wait for, because nothing here blocks anything (FR-ANL-10). */
    @Test
    @Requirement("FR-ANL-10", "AC-180")
    fun `AC_180 flipping a tier toggle reports it straight through, with nothing to confirm`() {
        var tier2: Boolean? = null
        composeTestRule.setContent {
            OrtTheme { WelcomeScreen(onBegin = {}, onToggleTier2 = { tier2 = it }) }
        }

        composeTestRule.onNodeWithTag("setup-welcome-analytics-row").performScrollTo().performClick()
        composeTestRule.onNodeWithText("Also share transcripts and callsigns").performClick()

        assert(tier2 == true) { "the toggle must write through as it is flipped, got $tier2" }
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

    /**
     * R-1164. `OfflinePromiseCopyTest` proves the wording satisfies FR-ANL-14; this proves the
     * Welcome screen is the surface that states it, asserted through **this screen's own tag**
     * rather than by looking for the words anywhere in the tree — R-1160 and R-1070 are both cases
     * of a check that passed by matching something other than the screen under test.
     */
    @Test
    @Requirement("FR-ANL-14", "R-1164")
    fun `FR_ANL_14 the Welcome screen's lead paragraph is the shared promise copy`() {
        composeTestRule.setContent {
            OrtTheme { WelcomeScreen(onBegin = {}) }
        }

        composeTestRule.onNodeWithTag("setup-welcome-promise", useUnmergedTree = true)
            .assertTextEquals(OfflinePromiseCopy.WELCOME_PROMISE)
    }

    /** R-1165 — the sheet renders every shared point, in order, and invents none of its own. */
    @Test
    @Requirement("FR-SPK-20", "R-1165")
    fun `R_1165 the What is captured sheet renders exactly the shared promise points`() {
        composeTestRule.setContent {
            OrtTheme { WelcomeScreen(onBegin = {}) }
        }

        composeTestRule.onNodeWithTag("setup-welcome-what-is-captured").performClick()

        OfflinePromiseCopy.POINTS.forEachIndexed { index, point ->
            composeTestRule.onNodeWithTag("setup-welcome-promise-point-$index", useUnmergedTree = true)
                .assertTextEquals(point)
        }
        composeTestRule
            .onNodeWithTag("setup-welcome-promise-point-${OfflinePromiseCopy.POINTS.size}", useUnmergedTree = true)
            .assertDoesNotExist()
    }
}
