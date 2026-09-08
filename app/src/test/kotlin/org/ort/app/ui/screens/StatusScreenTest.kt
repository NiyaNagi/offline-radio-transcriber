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
}
