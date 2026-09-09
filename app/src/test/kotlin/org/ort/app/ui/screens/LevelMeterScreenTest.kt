package org.ort.app.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.components.LiveBarTone
import org.ort.app.ui.components.LiveBarViewState
import org.ort.app.ui.data.LevelViewState
import org.ort.app.ui.data.LevelViewStateMapper
import org.ort.app.ui.theme.OrtTheme
import org.ort.pipeline.capture.LevelStatus
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * `Level-Meter.dc.html` (ui-conformance-plan WP4, R-039, R-112, FR-CAP-3, F3). [LevelStatus]
 * (WP11c) now publishes a real reading — [LevelViewState.notMeasuredReason] non-null is the one
 * honest case this screen renders as failed rather than the meter.
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
        composeTestRule.onNodeWithTag("level-meter-chart").assertDoesNotExist()
    }

    @Test
    @Requirement("R-112")
    fun `R_112 a real reading renders the chart and every fact, never the failed state`() {
        val state = LevelViewStateMapper.from(
            level = LevelStatus.State.Measured(
                peakDbfs = -14f,
                rmsDbfs = -20f,
                noiseFloorDbfs = -58f,
                clipped = false,
                clipCountLastSecond = 0,
                sampleRateHz = 16_000,
                updatedAtMillis = 0L,
            ),
            history = List(60) { -20f },
            inputLabel = "USB Audio Device · last 60 s",
        )
        composeTestRule.setContent { OrtTheme { LevelMeterScreen(state = state) } }

        composeTestRule.onNodeWithTag("level-meter-chart").assertExists()
        composeTestRule.onNodeWithTag("level-meter-failed").assertDoesNotExist()
        composeTestRule.onNodeWithTag("level-meter-peak").assertExists()
        composeTestRule.onNodeWithTag("level-meter-noise-floor").assertExists()
        composeTestRule.onNodeWithTag("level-meter-headroom").assertExists()
        composeTestRule.onNodeWithTag("level-meter-clipped").assertExists()
        composeTestRule.onNodeWithText("-14 dBFS", substring = true).assertExists()
        composeTestRule.onNodeWithText("-58 dBFS", substring = true).assertExists()
    }

    @Test
    @Requirement("R-175")
    fun `R_175 the weakest-over row, the band-state sentence and the pinned live bar all render`() {
        val state = LevelViewStateMapper.from(
            level = LevelStatus.State.Measured(
                peakDbfs = -14f,
                rmsDbfs = -20f,
                noiseFloorDbfs = -58f,
                clipped = false,
                clipCountLastSecond = 0,
                sampleRateHz = 16_000,
                updatedAtMillis = 0L,
            ),
            history = List(60) { -20f },
            inputLabel = "USB Audio Device · last 60 s",
            weakestOverLabel = "S2",
        )
        val liveBar = LiveBarViewState(
            level = listOf(0.1f, 0.2f, 0.1f, 0.3f),
            partialText = null,
            label = "Quiet",
            tone = LiveBarTone.NOMINAL,
        )
        composeTestRule.setContent { OrtTheme { LevelMeterScreen(state = state, liveBar = liveBar) } }

        composeTestRule.onNodeWithTag("level-meter-weakest-over").assertExists()
        composeTestRule.onNodeWithText("S2", substring = true).assertExists()
        composeTestRule.onNodeWithTag("level-meter-band-state").assertExists()
        composeTestRule.onNodeWithText("In the band. Nothing to adjust.", substring = true).assertExists()
        composeTestRule.onNodeWithTag("level-meter-livebar").assertExists()
    }

    @Test
    @Requirement("R-175")
    fun `R_175 the weakest-over row is honestly absent, never a fabricated zero, when no over recorded a signal`() {
        val state = LevelViewStateMapper.from(
            level = LevelStatus.State.Measured(
                peakDbfs = -14f,
                rmsDbfs = -20f,
                noiseFloorDbfs = null,
                clipped = false,
                clipCountLastSecond = 0,
                sampleRateHz = 16_000,
                updatedAtMillis = 0L,
            ),
            history = List(60) { -20f },
            inputLabel = "USB Audio Device · last 60 s",
        )
        composeTestRule.setContent { OrtTheme { LevelMeterScreen(state = state) } }

        composeTestRule.onNodeWithTag("level-meter-weakest-over").assertDoesNotExist()
    }

    @Test
    @Requirement("R-419")
    fun `R_419 the clipped-samples row carries the real session total, not clipCountLastSecond`() {
        val state = LevelViewStateMapper.from(
            level = LevelStatus.State.Measured(
                peakDbfs = -14f,
                rmsDbfs = -20f,
                noiseFloorDbfs = -58f,
                clipped = false,
                // Deliberately different from clippedSamplesThisSession below, so this proves the
                // row reads the real session total, not the per-second count.
                clipCountLastSecond = 9,
                sampleRateHz = 16_000,
                updatedAtMillis = 0L,
            ),
            history = List(60) { -20f },
            inputLabel = "USB Audio Device · last 60 s",
            clippedSamplesThisSession = 340L,
        )
        composeTestRule.setContent { OrtTheme { LevelMeterScreen(state = state) } }

        composeTestRule.onNodeWithText("Clipped samples this session", substring = true).assertExists()
        composeTestRule.onNodeWithText("340", substring = true).assertExists()
        composeTestRule.onNodeWithText("Clipped samples, last second", substring = true).assertDoesNotExist()
    }

    @Test
    @Requirement("R-419")
    fun `R_419 the static paragraph renders beneath the band-state sentence`() {
        val state = LevelViewStateMapper.from(
            level = LevelStatus.State.Measured(
                peakDbfs = -14f,
                rmsDbfs = -20f,
                noiseFloorDbfs = -58f,
                clipped = false,
                clipCountLastSecond = 0,
                sampleRateHz = 16_000,
                updatedAtMillis = 0L,
            ),
            history = List(60) { -20f },
            inputLabel = "USB Audio Device · last 60 s",
        )
        composeTestRule.setContent { OrtTheme { LevelMeterScreen(state = state) } }

        composeTestRule.onNodeWithTag("level-meter-band-state-body").assertExists()
        composeTestRule.onNodeWithText("The level is set on the radio.", substring = true).assertExists()
    }
}
