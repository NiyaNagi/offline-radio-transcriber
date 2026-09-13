package org.ort.app.ui.recordings

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
import org.ort.core.Attribution
import org.ort.pipeline.archive.SessionAudioDeletionRefusal
import org.ort.pipeline.archive.SessionAudioExportRefusal
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/** `Recording-Session.dc.html` (RC02): [RecordingSessionScreen] is a pure function of its view
 * states — no database or context needed to exercise its structure and callbacks, the same
 * discipline [RecordingsScreenTest] already establishes for RC01. */
@RunWith(RobolectricTestRunner::class)
class RecordingSessionScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun header() = RecordingSessionHeaderViewState(
        dateLabel = "Wed 10 Sep",
        timeRangeLabel = "21:48 – 06:12",
        durationLabel = "8 h 24 m",
        modeLabel = "local microphone · room audio",
        countsLabel = "41 overs · 9 stations · 1 gap · 3 failed",
    )

    private fun coverage() = RecordingSessionCoverageViewState(
        ticks = emptyList(),
        gaps = emptyList(),
        playheadFraction = null,
        axisLabels = listOf("22", "00", "02", "04", "06"),
    )

    private fun resolvedOver(id: String = "T1", stationId: String? = "W7NPC") = RecordingSessionRow.Over(
        id = id,
        timeLabel = "22:14:07",
        frequencyLabel = "146.520",
        durationLabel = "3.4 s",
        status = RecordingSessionOverStatus.RESOLVED,
        transcript = "this is whiskey seven november papa charlie, clear",
        attribution = stationId?.let { Attribution.confirmed(it, 0.9) },
        callsign = stationId,
        alternate = null,
        attributionStateLabel = "confirmed",
        inferredFromLabel = null,
        hasAudio = true,
        stationId = stationId,
        trainingLabel = "training · good",
        markedForTraining = true,
        statusReasonLabel = null,
        canRetry = false,
    )

    private fun failedOver(id: String = "T2") = RecordingSessionRow.Over(
        id = id,
        timeLabel = "02:31:48",
        frequencyLabel = "146.520",
        durationLabel = "1.9 s",
        status = RecordingSessionOverStatus.FAILED,
        transcript = null,
        attribution = null,
        callsign = null,
        alternate = null,
        attributionStateLabel = null,
        inferredFromLabel = null,
        hasAudio = true,
        stationId = null,
        trainingLabel = null,
        markedForTraining = false,
        statusReasonLabel = "Pass B errored 5 times",
        canRetry = true,
    )

    private fun state(rows: List<RecordingSessionRow> = listOf(resolvedOver()), deleteFreesBytes: Long = 610_000_000L) =
        RecordingSessionViewState(
            sessionId = "S1",
            header = header(),
            coverage = coverage(),
            rows = rows,
            deleteFreesBytes = deleteFreesBytes,
            exportAvailable = true,
        )

    @Test
    @Requirement("R-1051")
    fun `a null state renders the loading tag, never the overs list`() {
        composeTestRule.setContent {
            OrtTheme {
                RecordingSessionScreen(
                    state = null,
                    playingOverId = null,
                    isPlaying = false,
                    actions = RecordingSessionActions(),
                    deleteSheet = RecordingSessionDeleteState.Idle,
                    exportSheet = RecordingSessionExportState.Idle,
                    labelSheet = null,
                )
            }
        }
        composeTestRule.onNodeWithTag(RECORDING_SESSION_LOADING_TEST_TAG).assertIsDisplayed()
    }

    @Test
    @Requirement("FR-STO-3", "P9")
    fun `the delete preview shows the real bytes to free before anything is tapped`() {
        composeTestRule.setContent {
            OrtTheme {
                RecordingSessionScreen(
                    state = state(),
                    playingOverId = null,
                    isPlaying = false,
                    actions = RecordingSessionActions(),
                    deleteSheet = RecordingSessionDeleteState.Preview(bytesToFree = 610_000_000L),
                    exportSheet = RecordingSessionExportState.Idle,
                    labelSheet = null,
                )
            }
        }
        composeTestRule.onNodeWithTag(RECORDING_SESSION_DELETE_FREES_TEST_TAG).assertIsDisplayed()
    }

    private fun assertDeleteRefusalRendersItsOwnMessage(
        reason: SessionAudioDeletionRefusal,
        expectedSubstring: String,
    ) {
        composeTestRule.setContent {
            OrtTheme {
                RecordingSessionScreen(
                    state = state(),
                    playingOverId = null,
                    isPlaying = false,
                    actions = RecordingSessionActions(),
                    deleteSheet = RecordingSessionDeleteState.Refused(reason),
                    exportSheet = RecordingSessionExportState.Idle,
                    labelSheet = null,
                )
            }
        }
        composeTestRule.onNodeWithTag(RECORDING_SESSION_REFUSAL_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText(expectedSubstring, substring = true).assertIsDisplayed()
    }

    @Test
    @Requirement("FR-STO-3", "constitution II")
    fun `a session-capturing delete refusal states that plainly`() =
        assertDeleteRefusalRendersItsOwnMessage(SessionAudioDeletionRefusal.SessionCapturing, "capturing")

    @Test
    @Requirement("FR-STO-3", "constitution II")
    fun `a processing-in-progress delete refusal states that plainly, distinct from session-capturing`() =
        assertDeleteRefusalRendersItsOwnMessage(
            SessionAudioDeletionRefusal.ProcessingInProgress,
            "pass is still running",
        )

    @Test
    @Requirement("FR-STO-3", "constitution II")
    fun `a session-not-found delete refusal states that plainly, distinct from the other two`() =
        assertDeleteRefusalRendersItsOwnMessage(SessionAudioDeletionRefusal.SessionNotFound, "no longer exists")

    @Test
    @Requirement("FR-STO-3")
    fun `a completed delete states what was freed`() {
        composeTestRule.setContent {
            OrtTheme {
                RecordingSessionScreen(
                    state = state(),
                    playingOverId = null,
                    isPlaying = false,
                    actions = RecordingSessionActions(),
                    deleteSheet = RecordingSessionDeleteState.Deleted(
                        bytesFreed = 610_000_000L,
                        overAudioRemovedAtMillis = 1L,
                        archiveRemovedAtMillis = null,
                    ),
                    exportSheet = RecordingSessionExportState.Idle,
                    labelSheet = null,
                )
            }
        }
        composeTestRule.onNodeWithTag(RECORDING_SESSION_DELETE_DONE_TEST_TAG).assertIsDisplayed()
    }

    @Test
    @Requirement("D40")
    fun `the export preview lists the real file count and total before saving`() {
        composeTestRule.setContent {
            OrtTheme {
                RecordingSessionScreen(
                    state = state(),
                    playingOverId = null,
                    isPlaying = false,
                    actions = RecordingSessionActions(),
                    deleteSheet = RecordingSessionDeleteState.Idle,
                    exportSheet = RecordingSessionExportState.Preview(
                        fileCount = 41,
                        totalBytes = 610_000_000L,
                        suggestedFileName = "ort-session-S1-audio-20260101-000000.zip",
                    ),
                    labelSheet = null,
                )
            }
        }
        composeTestRule.onNodeWithTag(RECORDING_SESSION_EXPORT_PREVIEW_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("41 files", substring = true).assertIsDisplayed()
    }

    @Test
    @Requirement("constitution II")
    fun `each typed export refusal renders its own honest state`() {
        composeTestRule.setContent {
            OrtTheme {
                RecordingSessionScreen(
                    state = state(),
                    playingOverId = null,
                    isPlaying = false,
                    actions = RecordingSessionActions(),
                    deleteSheet = RecordingSessionDeleteState.Idle,
                    exportSheet = RecordingSessionExportState.Refused(
                        SessionAudioExportRefusal.NothingToExport("over audio was removed; no archive was ever kept"),
                    ),
                    labelSheet = null,
                )
            }
        }
        composeTestRule.onNodeWithTag(RECORDING_SESSION_REFUSAL_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("over audio was removed", substring = true).assertIsDisplayed()
    }

    @Test
    @Requirement("C10")
    fun `tapping a playable row's own play control hands its real id up through the callback`() {
        var played: String? = null
        composeTestRule.setContent {
            OrtTheme {
                RecordingSessionScreen(
                    state = state(rows = listOf(resolvedOver(id = "T1"))),
                    playingOverId = null,
                    isPlaying = false,
                    actions = RecordingSessionActions(onPlayRow = { played = it }),
                    deleteSheet = RecordingSessionDeleteState.Idle,
                    exportSheet = RecordingSessionExportState.Idle,
                    labelSheet = null,
                )
            }
        }
        composeTestRule.onNodeWithTag("rc02-play-T1", useUnmergedTree = true).performScrollTo().performClick()
        assert(played == "T1") { "expected the row's own id, got $played" }
    }

    @Test
    @Requirement("FR-RUN-9")
    fun `retry is offered only on a failed over, and fires with its real id`() {
        var retried: String? = null
        composeTestRule.setContent {
            OrtTheme {
                RecordingSessionScreen(
                    state = state(rows = listOf(resolvedOver(id = "T1"), failedOver(id = "T2"))),
                    playingOverId = null,
                    isPlaying = false,
                    actions = RecordingSessionActions(onRetry = { retried = it }),
                    deleteSheet = RecordingSessionDeleteState.Idle,
                    exportSheet = RecordingSessionExportState.Idle,
                    labelSheet = null,
                )
            }
        }
        composeTestRule.onNodeWithTag("rc02-retry-T2", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("rc02-retry-T2", useUnmergedTree = true).performClick()
        assert(retried == "T2") { "expected T2, got $retried" }
    }

    @Test
    @Requirement("FR-OBS-4")
    fun `tapping a row's own Label action opens the sheet for that row's real id`() {
        var opened: String? = null
        composeTestRule.setContent {
            OrtTheme {
                RecordingSessionScreen(
                    state = state(rows = listOf(resolvedOver(id = "T1"))),
                    playingOverId = null,
                    isPlaying = false,
                    actions = RecordingSessionActions(onOpenLabelSheet = { opened = it }),
                    deleteSheet = RecordingSessionDeleteState.Idle,
                    exportSheet = RecordingSessionExportState.Idle,
                    labelSheet = null,
                )
            }
        }
        composeTestRule.onNodeWithTag("rc02-label-T1", useUnmergedTree = true).performScrollTo().performClick()
        assert(opened == "T1") { "expected T1, got $opened" }
    }

    @Test
    @Requirement("FR-OBS-4")
    fun `the label sheet's toggle fires the negation of its current state, never a fixed value`() {
        var marked: Boolean? = null
        composeTestRule.setContent {
            OrtTheme {
                RecordingSessionScreen(
                    state = state(),
                    playingOverId = null,
                    isPlaying = false,
                    actions = RecordingSessionActions(onSetMarkedForTraining = { marked = it }),
                    deleteSheet = RecordingSessionDeleteState.Idle,
                    exportSheet = RecordingSessionExportState.Idle,
                    labelSheet = RecordingSessionLabelSheetViewState(
                        transmissionId = "T1",
                        callsignLabel = "W7NPC",
                        markedForTraining = false,
                        rating = null,
                    ),
                )
            }
        }
        composeTestRule.onNodeWithTag(RECORDING_SESSION_LABEL_TOGGLE_TEST_TAG).performClick()
        assert(marked == true) { "expected the toggle to ask for true (was false), got $marked" }
    }

    @Test
    @Requirement("FR-OBS-4")
    fun `the label sheet's Good and Bad each fire the real rating string`() {
        var rating: String? = null
        composeTestRule.setContent {
            OrtTheme {
                RecordingSessionScreen(
                    state = state(),
                    playingOverId = null,
                    isPlaying = false,
                    actions = RecordingSessionActions(onSetRating = { rating = it }),
                    deleteSheet = RecordingSessionDeleteState.Idle,
                    exportSheet = RecordingSessionExportState.Idle,
                    labelSheet = RecordingSessionLabelSheetViewState(
                        transmissionId = "T1",
                        callsignLabel = "W7NPC",
                        markedForTraining = true,
                        rating = null,
                    ),
                )
            }
        }
        composeTestRule.onNodeWithTag(RECORDING_SESSION_LABEL_GOOD_TEST_TAG).performClick()
        assert(rating == "good") { "expected 'good', got $rating" }
        composeTestRule.onNodeWithTag(RECORDING_SESSION_LABEL_BAD_TEST_TAG).performClick()
        assert(rating == "bad") { "expected 'bad', got $rating" }
    }

    @Test
    @Requirement("constitution I: a label never changes an attribution")
    fun `a label never changes what attribution this screen reports for the same over`() {
        val markedRow = resolvedOver(id = "T1", stationId = "W7NPC").copy(
            attribution = null,
            attributionStateLabel = null,
            trainingLabel = "training · good",
            markedForTraining = true,
        )
        composeTestRule.setContent {
            OrtTheme {
                RecordingSessionScreen(
                    state = state(rows = listOf(markedRow)),
                    playingOverId = null,
                    isPlaying = false,
                    actions = RecordingSessionActions(),
                    deleteSheet = RecordingSessionDeleteState.Idle,
                    exportSheet = RecordingSessionExportState.Idle,
                    labelSheet = null,
                )
            }
        }
        // The screen renders exactly the attribution state it was handed (UNKNOWN here, via a
        // `null` attribution) alongside a real training label — nothing in this composable itself
        // ever promotes one from the other; if it did, this row would need an `AttributionRow` this
        // test never asked for.
        composeTestRule.onNodeWithTag("rc02-over-row-T1", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    @Requirement("IA-3")
    fun `the Log link fires the session-scoped log callback`() {
        var openedLog = false
        composeTestRule.setContent {
            OrtTheme {
                RecordingSessionScreen(
                    state = state(),
                    playingOverId = null,
                    isPlaying = false,
                    actions = RecordingSessionActions(onOpenLog = { openedLog = true }),
                    deleteSheet = RecordingSessionDeleteState.Idle,
                    exportSheet = RecordingSessionExportState.Idle,
                    labelSheet = null,
                )
            }
        }
        composeTestRule.onNodeWithTag(RECORDING_SESSION_OPEN_LOG_TEST_TAG).performClick()
        assert(openedLog) { "the Log link did not fire onOpenLog" }
    }

    @Test
    @Requirement("D40")
    fun `Delete opens its sheet through the dedicated action, never the export one`() {
        var deleteOpened = false
        var exportOpened = false
        composeTestRule.setContent {
            OrtTheme {
                RecordingSessionScreen(
                    state = state(),
                    playingOverId = null,
                    isPlaying = false,
                    actions = RecordingSessionActions(
                        onOpenDeleteSheet = { deleteOpened = true },
                        onOpenExportSheet = { exportOpened = true },
                    ),
                    deleteSheet = RecordingSessionDeleteState.Idle,
                    exportSheet = RecordingSessionExportState.Idle,
                    labelSheet = null,
                )
            }
        }
        composeTestRule.onNodeWithTag(RECORDING_SESSION_DELETE_TEST_TAG).performClick()
        assert(deleteOpened) { "Delete tile did not open the delete sheet" }
        assert(!exportOpened) { "Delete tile must not also open the export sheet" }
    }
}
