package org.ort.app.ui.improve

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.ort.pipeline.reprocess.ReprocessStatus
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * WP11d addendum (round 5, register R-143/FR-REP-6): `Improve-Running`'s honest auto-pause state
 * and `Improve-Done`'s real measured counts, once `RealImproveRunner`/`ReprocessStatus` exist —
 * see `ImproveContent.kt`'s own doc comment for how `ReprocessStatus.state` reaches these screens.
 */
@RunWith(RobolectricTestRunner::class)
class ImproveScreensTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    @Requirement("FR-REP-6")
    fun `FR_REP_6_paused_state_is_shown`() {
        composeTestRule.setContent {
            OrtTheme {
                ImproveRunningScreen(
                    state = ImproveRunningViewState(
                        headline = "Field, Thu 3 Sep",
                        doneCount = 4,
                        totalCount = 12,
                        paused = false,
                        autoPausedReason = "waiting — capture is busy",
                    ),
                    onPause = {},
                    onCancel = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Paused").assertExists()
        composeTestRule.onNodeWithText("waiting — capture is busy").assertExists()
    }

    @Test
    @Requirement("FR-REP-6")
    fun `FR_REP_6_a_running_state_with_no_auto_pause_reads_Improving, not Paused`() {
        composeTestRule.setContent {
            OrtTheme {
                ImproveRunningScreen(
                    state = ImproveRunningViewState(
                        headline = "Field, Thu 3 Sep",
                        doneCount = 4,
                        totalCount = 12,
                        paused = false,
                        autoPausedReason = null,
                    ),
                    onPause = {},
                    onCancel = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Improving").assertExists()
    }

    @Test
    @Requirement("R-143")
    fun `R_143_improve_done_shows_measured_counts`() {
        composeTestRule.setContent {
            OrtTheme {
                ImproveDoneScreen(
                    state = ImproveDoneViewState(
                        headline = "Field, Thu 3 Sep",
                        clearedCount = 12,
                        summary = ReprocessStatus.Summary(
                            total = 12,
                            transcriptsChanged = 5,
                            attributionsChanged = 3,
                            rejected = 1,
                            failed = 0,
                        ),
                    ),
                    onDone = {},
                )
            }
        }

        composeTestRule.onNodeWithText(
            "5 transcripts changed · 3 attributions changed · 1 rejected · 0 failed",
        ).assertExists()
        composeTestRule.onNodeWithText("no reprocessing engine", substring = true).assertDoesNotExist()
    }

    @Test
    @Requirement("R-143")
    fun `R_143_no summary keeps the honest no-engine note, never zeroes standing in for real counts`() {
        composeTestRule.setContent {
            OrtTheme {
                ImproveDoneScreen(
                    state = ImproveDoneViewState(headline = "Field, Thu 3 Sep", clearedCount = 1, summary = null),
                    onDone = {},
                )
            }
        }

        composeTestRule.onNodeWithText("transcripts changed", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Nothing is deleted", substring = true).assertExists()
    }
}
