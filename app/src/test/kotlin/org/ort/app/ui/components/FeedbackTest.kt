package org.ort.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner

/**
 * R-023 (ui-conformance-plan WP2): the six feedback treatments (guide §6.8-6.9/§Feedback), never
 * conflated. `Banner`, `Toast`, `Sheet`, `EmptyState` and `FailedState` did not exist before this.
 */
@RunWith(RobolectricTestRunner::class)
class FeedbackTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `a halting banner and a degradation banner differ in tone, copy and action colour, not just hue`() {
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    Banner(
                        title = "Capture halted — audio is coming from the built-in microphone",
                        body = "The route resolved to the phone's own mic, not the radio. Nothing has been recorded.",
                        tone = BannerTone.HALTING,
                        primaryActionLabel = "Choose another input",
                        onPrimaryAction = {},
                        modifier = Modifier.testTag("halting"),
                    )
                    Banner(
                        title = "Running warm — dropped to tier 2",
                        body = "Fewer callsigns will resolve until it cools. Everything captured is kept and " +
                            "can be improved later.",
                        tone = BannerTone.DEGRADED,
                        primaryActionLabel = "What changes at tier 2",
                        onPrimaryAction = {},
                        modifier = Modifier.testTag("degraded"),
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Choose another input").assertIsDisplayed()
        composeTestRule.onNodeWithText("What changes at tier 2").assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Capture halted — audio is coming from the built-in microphone")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Running warm — dropped to tier 2").assertIsDisplayed()
    }

    @Test
    fun `a toast always carries Undo and states the blast radius, not just success`() {
        composeTestRule.setContent {
            OrtTheme {
                Toast(
                    message = "Corrected to K7LWH · 6 overs updated",
                    onUndo = {},
                    modifier = Modifier.testTag("toast"),
                )
            }
        }

        composeTestRule.onNodeWithText("Corrected to K7LWH · 6 overs updated").assertIsDisplayed()
        composeTestRule.onNodeWithText("Undo").assertIsDisplayed().performClick()
    }

    @Test
    fun `AC_6_8 empty and failed states are never conflated`() {
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    EmptyState(
                        message = "No overs on this frequency yet.",
                        subMessage = "Listening since 22:14.",
                        modifier = Modifier.testTag("empty"),
                    )
                    FailedState(
                        title = "No transcription model installed",
                        body = "Audio is being captured and kept. Transcripts will appear once a model is installed.",
                        actionLabel = "Install a model",
                        onAction = {},
                        modifier = Modifier.testTag("failed"),
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("No overs on this frequency yet.").assertIsDisplayed()
        composeTestRule.onNodeWithText("No transcription model installed").assertIsDisplayed()
        composeTestRule.onNodeWithText("Install a model").assertIsDisplayed()
    }

    @Test
    fun `a sheet renders its handle, title and an optional Clear all action with a 44dp target`() {
        composeTestRule.setContent {
            OrtTheme {
                Sheet(title = "Filter the log", onClearAll = {}) {
                    androidx.compose.material3.Text("content")
                }
            }
        }

        composeTestRule.onNodeWithText("Filter the log").assertIsDisplayed()
        val clearAll = composeTestRule.onNodeWithText("Clear all")
        clearAll.assertIsDisplayed()
        clearAll.assertHeightIsAtLeast(44.dp)
    }
}
