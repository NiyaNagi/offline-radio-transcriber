package org.ort.app.ui.failures

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner

/**
 * Register R-448: F14/F19/F21/F22 each carry the board's own "‹ &lt;parent&gt;" back header (WP2's
 * shared `DrillInHeader`) — none rendered before this. Split out of `FailureScreensTest.kt` purely
 * to keep that file under detekt's `LargeClass` (this package's report says the same reasoning
 * `FailCalibrationTest.kt` already used for R-447).
 */
@RunWith(RobolectricTestRunner::class)
class FailureBackHeaderTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `R_448 F14 renders the board's own back-Earlier-nights header, wired to the screen's own dismiss`() {
        var continued = false
        composeTestRule.setContent {
            OrtTheme {
                FailClockScreen(
                    state = ClockViewState("PDT → PST", "8 h 30 m", "23:10", "06:40"),
                    onContinue = { continued = true },
                )
            }
        }
        composeTestRule.onNodeWithText("Earlier nights").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Back to Earlier nights").performClick()
        assert(continued)
    }

    @Test
    fun `R_448 F19 renders the board's own back-Storage-and-retention header, wired to Leave as is`() {
        var leftAsIs = false
        composeTestRule.setContent {
            OrtTheme {
                FailReconcileScreen(
                    state = ReconcileViewState(
                        recordsNoFile = emptyList(),
                        filesNoRecord = emptyList(),
                        causeText = "cause",
                    ),
                    onImport = {},
                    onLeaveAsIs = { leftAsIs = true },
                )
            }
        }
        composeTestRule.onNodeWithText("Storage and retention").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Back to Storage and retention").performClick()
        assert(leftAsIs)
    }

    @Test
    fun `R_448 F21 renders the board's own back-Models-and-lexicon header, wired to Done`() {
        var done = false
        composeTestRule.setContent {
            OrtTheme {
                FailAssetSwapScreen(
                    state = AssetSwapViewState(
                        activeLabel = "2026.08 active",
                        stagedLabel = "2026.09 staged",
                        options = listOf(AssetSwapOption("Wait", "the default")),
                        selectedOption = 0,
                    ),
                    onSelectOption = {},
                    onDone = { done = true },
                )
            }
        }
        composeTestRule.onNodeWithText("Models and lexicon").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Back to Models and lexicon").performClick()
        assert(done)
    }

    @Test
    fun `R_448 F22 renders the board's own back-Models-and-lexicon header, wired to the new onBack`() {
        var wentBack = false
        composeTestRule.setContent {
            OrtTheme {
                FailCalibrationScreen(
                    state = CalibrationViewState(
                        sinceLabel = "1 Sep",
                        scoreLabel = "0.90",
                        accuracyLabel = "78%",
                        points = listOf(0.3f to 0.22f),
                        calibrationVersion = "2026.09-a",
                        correctionsCount = 22,
                        correctionsNeeded = 100,
                    ),
                    onInstall = {},
                    onBack = { wentBack = true },
                )
            }
        }
        composeTestRule.onNodeWithText("Models and lexicon").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Back to Models and lexicon").performClick()
        assert(wentBack)
    }
}
