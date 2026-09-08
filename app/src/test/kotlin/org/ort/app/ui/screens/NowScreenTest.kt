package org.ort.app.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.status.StatusViewState
import org.ort.app.ui.data.NowSummaryViewState
import org.ort.app.ui.theme.OrtTheme
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * The "Now" home (build-plan P14, `design/canvas/Main.dc.html`). The header counts are real
 * (`NowSummaryMapperTest` covers the mapping); the "Worth knowing" digest is an honest empty
 * state, not a fabricated look-alike — digest computation is M9, after the M4 fork, and no data
 * for it exists yet (see this class's own doc comment for why that is not this prompt's gap to
 * fill).
 */
@RunWith(RobolectricTestRunner::class)
class NowScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val idleStatus = StatusViewState(
        stateLabel = "Idle",
        elapsedLabel = "00:00:00",
        transmissionCount = 0,
        gapCount = 0,
        shedLevel = 0,
        shedLevelLabel = "Nominal",
        livenessLabel = "No session",
        uncleanEndBanner = null,
    )

    @Test
    fun `the header shows real over and station counts`() {
        composeTestRule.setContent {
            OrtTheme {
                NowScreen(status = idleStatus, summary = NowSummaryViewState(overCount = 412, stationCount = 19))
            }
        }

        composeTestRule.onNodeWithText("412 overs · 19 stations", substring = true).assertExists()
    }

    @Test
    fun `an empty session shows an honest zero summary, not a placeholder number`() {
        composeTestRule.setContent {
            OrtTheme {
                NowScreen(status = idleStatus, summary = NowSummaryViewState(overCount = 0, stationCount = 0))
            }
        }

        composeTestRule.onNodeWithText("0 overs · 0 stations", substring = true).assertExists()
    }

    @Test
    fun `worth knowing is an honest empty state, not a fabricated digest`() {
        composeTestRule.setContent {
            OrtTheme {
                NowScreen(status = idleStatus, summary = NowSummaryViewState(overCount = 0, stationCount = 0))
            }
        }

        composeTestRule.onNodeWithContentDescription("Nothing to report yet", substring = true).assertExists()
    }

    @Test
    @Requirement("FR-UI-7")
    fun `FR_UI_7 the header says no transcription model is installed when unavailable`() {
        composeTestRule.setContent {
            OrtTheme {
                NowScreen(status = idleStatus, summary = NowSummaryViewState(overCount = 0, stationCount = 0))
            }
        }

        composeTestRule.onNodeWithText(
            "No transcription model installed — transcripts will not appear",
            substring = true,
        ).assertExists()
    }

    @Test
    @Requirement("FR-UI-7")
    fun `FR_UI_7 the header stays silent once a transcription model is actually available`() {
        composeTestRule.setContent {
            OrtTheme {
                NowScreen(
                    status = idleStatus.copy(transcriptionUnavailableMessage = null),
                    summary = NowSummaryViewState(overCount = 0, stationCount = 0),
                )
            }
        }

        composeTestRule.onNodeWithText("No transcription model installed", substring = true).assertDoesNotExist()
    }

    @Test
    fun `capture state is still shown, unchanged from the status screen`() {
        composeTestRule.setContent {
            OrtTheme {
                NowScreen(
                    status = idleStatus.copy(stateLabel = "Capturing"),
                    summary = NowSummaryViewState(overCount = 1, stationCount = 1),
                )
            }
        }

        composeTestRule.onNodeWithText("Capturing").assertExists()
    }
}
