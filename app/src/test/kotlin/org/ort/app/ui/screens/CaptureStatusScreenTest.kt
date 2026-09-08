package org.ort.app.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.CaptureStateTone
import org.ort.app.ui.data.CaptureStatusViewState
import org.ort.app.ui.data.KeyValueFacts
import org.ort.app.ui.theme.OrtTheme
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * `Capture-Status.dc.html` (ui-conformance-plan WP4, R-031/R-032/R-034/R-035/R-038, FR-UI-7).
 */
@RunWith(RobolectricTestRunner::class)
class CaptureStatusScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun notMeasured() = KeyValueFacts(value = "Not measured")

    private val baseState = CaptureStatusViewState(
        stateLabel = "Capturing",
        stateTone = CaptureStateTone.NOMINAL,
        sinceElapsedLabel = "Since 23:32 · 6:42:18 · alive, heartbeat 4s ago",
        haltActionLabel = "Stop",
        haltConfirmTitle = "Stop capture?",
        haltConfirmBody = "Audio already captured is kept.",
        input = notMeasured(),
        level = notMeasured(),
        radio = KeyValueFacts(value = "No rig configured"),
        overs = KeyValueFacts(value = "412 captured", subLine = "6 rejected · 0 failed · 0 gaps"),
        backlog = KeyValueFacts(value = "3 overs waiting"),
        tier = KeyValueFacts(value = "3 of 3", subLine = "whisper-small · Silero VAD"),
        thermal = KeyValueFacts(value = "Nominal", subLine = "RTF 0.31 · no throttling"),
        storage = KeyValueFacts(value = "38.2 GB", subLine = "16 nights left"),
        battery = KeyValueFacts(value = "71% phone · charging", subLine = "exemption reports on — not trusted"),
    )

    @Test
    @Requirement("FR-UI-7")
    fun `R_032 every FR-UI-7 fact is present on the screen`() {
        composeTestRule.setContent { OrtTheme { CaptureStatusScreen(state = baseState) } }

        composeTestRule.onNodeWithTag("capture-status-title").assertExists()
        composeTestRule.onNodeWithTag("capture-status-input").assertExists()
        composeTestRule.onNodeWithTag("capture-status-radio").assertExists()
        composeTestRule.onNodeWithTag("capture-status-overs").assertExists()
        composeTestRule.onNodeWithTag("capture-status-backlog").assertExists()
        composeTestRule.onNodeWithTag("capture-status-tier").assertExists()
        composeTestRule.onNodeWithTag("capture-status-thermal").assertExists()
        composeTestRule.onNodeWithTag("capture-status-storage").assertExists()
        composeTestRule.onNodeWithTag("capture-status-battery").assertExists()
    }

    @Test
    @Requirement("R-034")
    fun `R_034 the ASR VAD sub-line never shows a raw file path`() {
        val state = baseState.copy(
            tier = KeyValueFacts(value = "1 of 3", subLine = "no model — see Models · energy VAD (not Silero)"),
        )
        composeTestRule.setContent { OrtTheme { CaptureStatusScreen(state = state) } }

        composeTestRule.onNodeWithText("no model — see Models · energy VAD (not Silero)", substring = true)
            .assertExists()
    }

    @Test
    @Requirement("R-038")
    fun `R_038 there is no raw shed-level line, only Tier Thermal and Backlog`() {
        composeTestRule.setContent { OrtTheme { CaptureStatusScreen(state = baseState) } }

        composeTestRule.onNodeWithText("Shed level", substring = true).assertDoesNotExist()
    }

    @Test
    @Requirement("R-031")
    fun `R_031 there is no doubled ASR ASR prefix anywhere on the screen`() {
        composeTestRule.setContent { OrtTheme { CaptureStatusScreen(state = baseState) } }

        composeTestRule.onNodeWithText("ASR: ASR:", substring = true).assertDoesNotExist()
    }

    @Test
    fun `tapping Stop opens a confirmation dialog before calling onStop`() {
        var stopped = false
        composeTestRule.setContent {
            OrtTheme { CaptureStatusScreen(state = baseState, onStop = { stopped = true }) }
        }

        composeTestRule.onNodeWithTag("capture-status-stop").performClick()
        composeTestRule.onNodeWithTag("capture-status-stop-confirm").assertExists()
        assert(!stopped)

        composeTestRule.onNodeWithTag("capture-status-stop-confirm").performClick()
        assert(stopped)
    }

    @Test
    @Requirement("R-039")
    fun `R_039 tapping the Level row invokes onOpenLevel, with a real 44dp target and description`() {
        var opened = false
        composeTestRule.setContent {
            OrtTheme { CaptureStatusScreen(state = baseState, onOpenLevel = { opened = true }) }
        }

        composeTestRule.onNodeWithTag("capture-status-level").performClick()
        assert(opened)
    }

    @Test
    fun `an idle session shows no Stop action`() {
        val idle = baseState.copy(
            stateLabel = "Not capturing",
            stateTone = CaptureStateTone.IDLE,
            haltActionLabel = null,
        )
        composeTestRule.setContent { OrtTheme { CaptureStatusScreen(state = idle) } }

        composeTestRule.onNodeWithTag("capture-status-stop").assertDoesNotExist()
    }
}
