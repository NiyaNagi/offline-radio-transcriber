package org.ort.app.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.status.StatusViewState
import org.ort.app.ui.theme.OrtTheme
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * FR-UI-7 / audit F-004: the status surface must show whether a transcription model is actually
 * installed and running, in plain text — the only reachable in-app signal before this fix was the
 * foreground-notification text nothing in the Compose reader referenced.
 */
@RunWith(RobolectricTestRunner::class)
class StatusScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val baseState = StatusViewState(
        stateLabel = "Capturing",
        elapsedLabel = "00:05:00",
        transmissionCount = 3,
        gapCount = 1,
        shedLevel = 0,
        shedLevelLabel = "Nominal",
        livenessLabel = "Alive (heartbeat current)",
        uncleanEndBanner = null,
        backlog = 7,
        backlogLabel = "7 queued",
    )

    @Test
    @Requirement("FR-UI-7")
    fun `FR_UI_7 an unavailable ASR reason is displayed`() {
        composeTestRule.setContent {
            OrtTheme {
                StatusScreen(state = baseState.copy(asrStatusLabel = "ASR: unavailable — no model at /models/asr"))
            }
        }

        composeTestRule.onNodeWithContentDescription(
            "ASR: ASR: unavailable — no model at /models/asr",
            substring = true,
        ).assertExists()
    }

    @Test
    @Requirement("FR-UI-7")
    fun `FR_UI_7 an unset ASR state reads as not started, not as available`() {
        composeTestRule.setContent {
            OrtTheme {
                StatusScreen(state = baseState)
            }
        }

        composeTestRule.onNodeWithContentDescription("ASR: ASR: not started", substring = true).assertExists()
    }

    @Test
    @Requirement("FR-UI-7")
    fun `FR_UI_7 VAD fallback reason is displayed`() {
        composeTestRule.setContent {
            OrtTheme {
                StatusScreen(
                    state = baseState.copy(vadStatusLabel = "VAD: energy fallback (Silero model not installed)"),
                )
            }
        }

        composeTestRule.onNodeWithContentDescription(
            "VAD: VAD: energy fallback (Silero model not installed)",
            substring = true,
        ).assertExists()
    }

    @Test
    @Requirement("FR-RUN-5")
    fun `FR_RUN_5 queue backlog is shown as a number`() {
        composeTestRule.setContent {
            OrtTheme {
                StatusScreen(state = baseState)
            }
        }

        composeTestRule.onNodeWithContentDescription("Queue backlog: 7 queued", substring = true).assertExists()
    }

    @Test
    @Requirement("FR-RUN-5")
    fun `FR_RUN_5 an unmeasured shed level and backlog render as not measured, never zero`() {
        composeTestRule.setContent {
            OrtTheme {
                StatusScreen(
                    state = baseState.copy(
                        shedLevel = null,
                        shedLevelLabel = "Not measured",
                        backlog = null,
                        backlogLabel = "Not measured",
                    ),
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Shed level: Not measured", substring = true).assertExists()
        composeTestRule.onNodeWithContentDescription("Queue backlog: Not measured", substring = true).assertExists()
    }
}
