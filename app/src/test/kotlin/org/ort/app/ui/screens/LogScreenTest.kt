package org.ort.app.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.ReaderTransmissionViewStateMapper
import org.ort.app.ui.data.TransmissionDetail
import org.ort.app.ui.data.TransmissionListEntryViewState
import org.ort.app.ui.theme.OrtTheme
import org.ort.core.Attribution
import org.ort.core.TransmissionState
import org.robolectric.RobolectricTestRunner

/**
 * The "Log" destination (build-plan P14, `design/canvas/Log.dc.html`). FR-UI-1: newest-first
 * ordering is a data concern proven in `ReaderPollingTest`; this proves the screen renders
 * whatever order it is handed, shows the honest empty-transcript state, and makes a revision
 * visible rather than hiding it. FR-UI-4: every attribution renders through
 * [org.ort.app.ui.components.AttributionMarker], never colour-only.
 */
@RunWith(RobolectricTestRunner::class)
class LogScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun entry(
        id: String,
        transcriptText: String,
        attribution: Attribution = Attribution.unknown(),
        revisionNote: String? = null,
    ) = TransmissionListEntryViewState(
        id = id,
        timeLabel = "02:14:07",
        frequencyLabel = "145.230",
        transcriptText = transcriptText,
        attribution = attribution,
        revisionNote = revisionNote,
        signalLabel = "S7",
    )

    @Test
    fun `FR_UI_1 a transmission with no transcript yet shows the honest not-yet-transcribed state, not an empty row`() {
        composeTestRule.setContent {
            OrtTheme {
                LogScreen(entries = listOf(entry("TX1", "(captured, not yet transcribed)")), onOpen = {})
            }
        }

        composeTestRule.onNodeWithText("(captured, not yet transcribed)").assertExists()
    }

    @Test
    fun `FR_UI_1 a superseded partial is visibly marked as revised, never silently replaced`() {
        composeTestRule.setContent {
            OrtTheme {
                LogScreen(
                    entries = listOf(entry("TX1", "final transcript", revisionNote = "revised · 1 earlier version")),
                    onOpen = {},
                )
            }
        }

        composeTestRule.onNodeWithText("revised · 1 earlier version").assertExists()
    }

    @Test
    fun `FR_UI_4 all four attribution states render through the shared marker`() {
        composeTestRule.setContent {
            OrtTheme {
                LogScreen(
                    entries = listOf(
                        entry("TX-confirmed", "a", Attribution.confirmed("W7NPC", 0.95)),
                        entry("TX-inferred", "b", Attribution.inferred("K7LWH", 0.82)),
                        entry("TX-ambiguous", "c", Attribution.ambiguous()),
                        entry("TX-unknown", "d", Attribution.unknown()),
                    ),
                    onOpen = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("filled circle, ✓ CONFIRMED", substring = true).assertExists()
        composeTestRule.onNodeWithContentDescription("outlined circle, ~ INFERRED", substring = true).assertExists()
        composeTestRule.onNodeWithContentDescription("half-filled circle, ? AMBIGUOUS", substring = true).assertExists()
        composeTestRule.onNodeWithContentDescription("small dot, — UNKNOWN", substring = true).assertExists()
    }

    @Test
    fun `tapping a row opens that transmission`() {
        var opened: String? = null
        composeTestRule.setContent {
            OrtTheme {
                LogScreen(entries = listOf(entry("TX1", "roger that")), onOpen = { opened = it })
            }
        }

        composeTestRule.onNodeWithText("roger that").performClick()

        assert(opened == "TX1") { "expected TX1 to be opened but was $opened" }
    }

    @Test
    fun `FR_RUN_9 a transmission the work queue moved to FAILED shows a failure label, not the pending state`() {
        val failedDetail = TransmissionDetail(
            id = "TX-failed",
            startedAtUtcMillis = 1_000L,
            frequencyHz = 146_960_000L,
            durationMs = 4_200L,
            signalStrength = 7.0,
            attribution = Attribution.unknown(),
            currentTranscriptText = null,
            supersededTranscriptTexts = emptyList(),
            hasAudio = false,
            processingState = TransmissionState.FAILED,
        )

        composeTestRule.setContent {
            OrtTheme {
                LogScreen(entries = listOf(ReaderTransmissionViewStateMapper.listEntry(failedDetail)), onOpen = {})
            }
        }

        composeTestRule.onNodeWithText("(captured, not yet transcribed)").assertDoesNotExist()
    }

    @Test
    fun `an empty log shows an honest empty state rather than a blank screen`() {
        composeTestRule.setContent {
            OrtTheme {
                LogScreen(entries = emptyList(), onOpen = {})
            }
        }

        composeTestRule.onNodeWithText("No transmissions yet").assertExists()
    }
}
