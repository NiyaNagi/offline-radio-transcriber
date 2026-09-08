package org.ort.app.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.LevelViewState
import org.ort.app.ui.theme.OrtTheme
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * `Level-Meter.dc.html` (ui-conformance-plan WP4, R-039, FR-CAP-3, F3). No level signal exists in
 * `:pipeline` today (see [LevelViewState]'s own kdoc) — this screen must render that honestly,
 * never fabricated bars.
 */
@RunWith(RobolectricTestRunner::class)
class LevelMeterScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    @Requirement("R-039")
    fun `R_039 an honest not-measured failed state is shown, never a fabricated meter`() {
        composeTestRule.setContent { OrtTheme { LevelMeterScreen(state = LevelViewState.notMeasured()) } }

        composeTestRule.onNodeWithTag("level-meter-failed").assertExists()
        composeTestRule.onNodeWithText("No level", substring = true).assertExists()
    }

    @Test
    @Requirement("R-039")
    fun `R_039 a real reading, when supplied, renders its facts`() {
        val state = LevelViewState(
            inputLabel = "USB Audio Device · last 60 s",
            peakDbfsLabel = "−14 dBFS",
            noiseFloorDbfsLabel = "−58 dBFS",
            headroomLabel = "14 dB",
            clippedSamplesLabel = "0",
            notMeasuredReason = null,
        )
        composeTestRule.setContent { OrtTheme { LevelMeterScreen(state = state) } }

        composeTestRule.onNodeWithText("−14 dBFS", substring = true).assertExists()
        composeTestRule.onNodeWithTag("level-meter-failed").assertDoesNotExist()
    }
}
