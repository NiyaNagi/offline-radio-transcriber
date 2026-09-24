package org.ort.app.ui.setup

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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

    // R-080's `MicrophoneScreen shows Allow microphone and invokes onAllow` is **deleted, not
    // skipped** (P39, D58, AC-204): the explainer screen it covered no longer exists. The system
    // dialog is fired straight from the mode tap, and the behaviour that replaced this test is
    // `SetupActivityTest`'s `AC_204 ...` — that choosing a mode requests RECORD_AUDIO with no screen
    // in between, and `ModeScreenTest`'s that the rationale is on the surface doing the asking.

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

    /**
     * R-1091 (register): `Setup-Mic-Denied.dc.html` draws its step-indicator segment in
     * `halt/text`, but [SetupStep.MICROPHONE_DENIED.isHalted] used to be `false`, so
     * [SegmentBars]' own merged content description read "Step 2 of 10" here, never naming the
     * halt the screen's own [SetupHaltBanner] body already announces. This is the wiring proof
     * `SetupStepTest`'s own `R_1091` unit test cannot stand in for: it exercises the real
     * [MicrophoneDeniedScreen] composition through [SetupScaffold]/[SegmentBars], not the bare
     * [isHalted] function.
     */
    @Test
    fun `R_1091 the step indicator announces the halt, matching Setup-Mic-Denied dc html`() {
        composeTestRule.setContent {
            OrtTheme { MicrophoneDeniedScreen(onOpenSettings = {}, onCheckAgain = {}) }
        }
        // P39: the denied microphone halts the stage the ask belongs to — the mode surface, stage 1 —
        // against the derived denominator rather than R-1087's fixed 10.
        composeTestRule
            .onNodeWithContentDescription("Step 1 of $SETUP_STEPS_WITHOUT_DOWNLOAD, halted at step 1")
            .assertIsDisplayed()
    }
}
