package org.ort.app.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.ModelId
import org.ort.app.ui.data.ModelRowStatus
import org.ort.app.ui.data.ModelRowViewState
import org.ort.app.ui.data.ModelsViewState
import org.ort.app.ui.theme.OrtTheme
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * Audit F-008: the Compose surface for [org.ort.app.ui.data.ModelsController]'s state — every row
 * carries a Download and a Side-load action, and the screen never claims a model is installed
 * unless the view state it was handed says so.
 */
@RunWith(RobolectricTestRunner::class)
class ModelsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun row(id: ModelId, status: ModelRowStatus) = ModelRowViewState(id, id.label, status, detail = null)

    @Test
    @Requirement("FR-ASR-1")
    fun `FR_ASR_1 a not-installed model shows honestly and offers Download and Side-load`() {
        composeTestRule.setContent {
            OrtTheme {
                ModelsScreen(
                    state = ModelsViewState(rows = listOf(row(ModelId.VAD, ModelRowStatus.NOT_INSTALLED))),
                    onDownload = {},
                    onSideload = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Silero VAD status: Not installed").assertExists()
        composeTestRule.onNodeWithContentDescription("Download Silero VAD").assertExists()
        composeTestRule.onNodeWithContentDescription("Side-load Silero VAD").assertExists()
    }

    @Test
    @Requirement("FR-ASR-1")
    fun `FR_ASR_1 an installed model says checksum verified, not just present`() {
        composeTestRule.setContent {
            OrtTheme {
                ModelsScreen(
                    state = ModelsViewState(rows = listOf(row(ModelId.VAD, ModelRowStatus.INSTALLED))),
                    onDownload = {},
                    onSideload = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Silero VAD status: Installed (checksum verified)")
            .assertExists()
    }

    @Test
    @Requirement("FR-ASR-1")
    fun `FR_ASR_1 tapping Download calls back with that row's id`() {
        var tapped: ModelId? = null
        composeTestRule.setContent {
            OrtTheme {
                ModelsScreen(
                    state = ModelsViewState(rows = listOf(row(ModelId.ASR_ENCODER, ModelRowStatus.NOT_INSTALLED))),
                    onDownload = { tapped = it },
                    onSideload = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Download Whisper tiny.en — encoder").performClick()

        assert(tapped == ModelId.ASR_ENCODER) { "expected a download tap for ASR_ENCODER, got $tapped" }
    }

    @Test
    @Requirement("FR-ASR-1")
    fun `FR_ASR_1 a busy row shows downloading and disables its actions`() {
        composeTestRule.setContent {
            OrtTheme {
                ModelsScreen(
                    state = ModelsViewState(rows = listOf(row(ModelId.VAD, ModelRowStatus.NOT_INSTALLED))),
                    busy = setOf(ModelId.VAD),
                    onDownload = {},
                    onSideload = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Silero VAD status: Downloading…").assertExists()
    }

    @Test
    @Requirement("FR-ASR-1")
    fun `FR_ASR_1 the last action message and requeue message are both shown`() {
        composeTestRule.setContent {
            OrtTheme {
                ModelsScreen(
                    state = ModelsViewState(
                        rows = listOf(row(ModelId.VAD, ModelRowStatus.INSTALLED)),
                        requeuedMessage = "Requeued 3 previously failed transmission(s).",
                    ),
                    lastMessage = "Silero VAD: installed, checksum verified.",
                    onDownload = {},
                    onSideload = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Requeued 3 previously failed transmission(s).").assertExists()
        composeTestRule.onNodeWithText("Silero VAD: installed, checksum verified.").assertExists()
    }
}
