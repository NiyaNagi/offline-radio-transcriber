package org.ort.app.ui.improve

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

    @Test
    @Requirement("R-445")
    fun `R_445 the empty state names the shared tier label, tier 3, never the raw T3 token`() {
        composeTestRule.setContent {
            OrtTheme {
                ImproveScreen(
                    state = ImproveRootViewState(
                        totalOverCount = 0,
                        allTransmissionIds = emptyList(),
                        currentTierLabel = "3",
                        groups = emptyList(),
                        everythingElseCount = 0,
                    ),
                    onDrawer = {},
                    onImproveAll = {},
                    onOpenGroup = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Every recorded session is at tier 3", substring = true).assertExists()
        composeTestRule.onNodeWithText("T3", substring = true).assertDoesNotExist()
    }

    @Test
    @Requirement("R-350")
    fun `R_350 every item failed names the real reason and offers Install for a missing model`() {
        var installTapped = false
        composeTestRule.setContent {
            OrtTheme {
                ImproveDoneScreen(
                    state = ImproveDoneViewState(
                        headline = "Field, Thu 3 Sep",
                        clearedCount = 12,
                        summary = ReprocessStatus.Summary(
                            total = 12,
                            transcriptsChanged = 0,
                            attributionsChanged = 0,
                            rejected = 0,
                            failed = 12,
                            failureReasons = listOf(
                                "ASR unavailable: no ASR model installed at /data/.../models " +
                                    "(expected tiny.en-encoder.int8.onnx)",
                            ),
                        ),
                    ),
                    onDone = {},
                    onInstallModel = { installTapped = true },
                )
            }
        }

        // The real, single distinct reason (WorkQueue's own `lastError`, never fabricated) is
        // reworded to `F13`'s already-established board phrase for this exact fact — the same
        // count `summary.failed` already carries is real too (every failure shares this one reason).
        composeTestRule.onNodeWithText("12 failed — No transcription model installed").assertExists()
        composeTestRule.onNodeWithText("Install").performClick()
        assert(installTapped) { "expected onInstallModel to fire for a missing-model reason" }
    }

    @Test
    @Requirement("R-350")
    fun `R_350 a non-model failure reason is shown real, with no Install action offered`() {
        var installTapped = false
        composeTestRule.setContent {
            OrtTheme {
                ImproveDoneScreen(
                    state = ImproveDoneViewState(
                        headline = "Field, Thu 3 Sep",
                        clearedCount = 3,
                        summary = ReprocessStatus.Summary(
                            total = 3,
                            failed = 3,
                            failureReasons = listOf("expected retained audio at …/scenario-field-tier1-tx1.flac"),
                        ),
                    ),
                    onDone = {},
                    onInstallModel = { installTapped = true },
                )
            }
        }

        composeTestRule.onNodeWithText(
            "3 failed — expected retained audio at …/scenario-field-tier1-tx1.flac",
        ).assertExists()
        composeTestRule.onNodeWithText("Install").assertDoesNotExist()
        assert(!installTapped)
    }
}
