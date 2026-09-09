package org.ort.app.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.audio.FakeTransmissionAudioPlayer
import org.ort.app.ui.data.DetailViewStateMapper
import org.ort.app.ui.data.InspectionViewState
import org.ort.app.ui.data.RejectedViewState
import org.ort.app.ui.data.TransmissionDetailViewState
import org.ort.app.ui.theme.OrtTheme
import org.ort.core.Attribution
import org.robolectric.RobolectricTestRunner

/**
 * `TransmissionDetailScreenTest.kt` split — detekt's `LargeClass` finding, this file's own size
 * after this round's R-423/R-425/R-426 tests; the same fix `RowsTest.kt`'s own `NavRowTest.kt`
 * split and `CorrectionPollingTest.kt`'s own `CorrectionPollingPassAndRevisionsTest.kt` split
 * already used. This file owns R-242/R-331, F04 `Fail-Hallucination.dc.html`: the rejected detail
 * state.
 */
@RunWith(RobolectricTestRunner::class)
class RejectedDetailScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun detail(
        attribution: Attribution = Attribution.unknown(),
        transcriptText: String = "-",
        hasAudio: Boolean = true,
    ) = TransmissionDetailViewState(
        id = "TX1",
        timeLabel = "02:14:22",
        frequencyLabel = "145.230",
        durationLabel = "4.2s",
        signalLabel = "S5",
        attribution = attribution,
        transcriptText = transcriptText,
        revisionHistory = emptyList(),
        hasAudio = hasAudio,
        inspection = InspectionViewState.EMPTY,
    )

    private fun rejectedState(reason: String? = "VAD_NO_SPEECH: squelch tail, 0.4 s") = DetailViewStateMapper.from(
        detail(),
        rejected = RejectedViewState(reason),
    )

    @Test
    fun `R_242_a_rejected_transmission_shows_the_real_reason_and_retained_audio`() {
        composeTestRule.setContent {
            OrtTheme { TransmissionDetailScreen(state = rejectedState(), player = FakeTransmissionAudioPlayer()) }
        }

        composeTestRule.onNodeWithTag("rejected-section").assertExists()
        // R-331: the operator-prose mapping, not the raw record — `LogItemsMapper.whyFor` turns
        // "VAD_NO_SPEECH: squelch tail, 0.4 s" into "No speech detected, squelch tail, 0.4 s".
        composeTestRule.onNodeWithText("No speech detected, squelch tail, 0.4 s", substring = true).assertExists()
        // The audio is retained (constitution III) — the waveform card still renders.
        composeTestRule.onNodeWithTag("waveform-card").assertExists()
    }

    /**
     * R-331: `Fail-Hallucination.dc.html`'s own title is "REJECTED" alone — the validator's own
     * finding was the raw `"$rule: $detail"` record rendering in the title
     * ("REJECTED · VAD_NO_SPEECH: SQUELCH TAIL, 0.4 S"). Proved two ways: the title text is exactly
     * "REJECTED", and the raw rule token never appears anywhere on screen.
     */
    @Test
    fun `R_331_detail_title`() {
        composeTestRule.setContent {
            OrtTheme { TransmissionDetailScreen(state = rejectedState(), player = FakeTransmissionAudioPlayer()) }
        }

        composeTestRule.onNodeWithText("REJECTED").assertExists()
        composeTestRule.onNodeWithText("VAD_NO_SPEECH", substring = true).assertDoesNotExist()
    }

    @Test
    fun `R_242_a_rejected_transmission_with_no_recorded_reason_reads_honestly_rather_than_fabricating_one`() {
        composeTestRule.setContent {
            OrtTheme {
                TransmissionDetailScreen(state = rejectedState(reason = null), player = FakeTransmissionAudioPlayer())
            }
        }

        composeTestRule.onNodeWithText("No reason was recorded.", substring = true).assertExists()
    }

    @Test
    fun `R_242_the_rejected_state_never_also_renders_the_unknown_attributions_what_was_tried_or_why_blocks`() {
        composeTestRule.setContent {
            OrtTheme { TransmissionDetailScreen(state = rejectedState(), player = FakeTransmissionAudioPlayer()) }
        }

        composeTestRule.onNodeWithText("What was tried", substring = true, ignoreCase = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Why this callsign", substring = true, ignoreCase = true).assertDoesNotExist()
    }

    /**
     * R-242: no real action exists for a rejected segment (see `RejectedHeaderSection`'s own doc
     * comment) — the bottom bar renders none of the other states' actions rather than a disabled
     * look-alike of one.
     */
    @Test
    fun `R_242_the_rejected_state_offers_no_bottom_action_bar`() {
        composeTestRule.setContent {
            OrtTheme { TransmissionDetailScreen(state = rejectedState(), player = FakeTransmissionAudioPlayer()) }
        }

        composeTestRule.onNodeWithText("Confirm").assertDoesNotExist()
        composeTestRule.onNodeWithText("Not right?").assertDoesNotExist()
        composeTestRule.onNodeWithText("Retry now").assertDoesNotExist()
        composeTestRule.onNodeWithText("I know who this is").assertDoesNotExist()
        composeTestRule.onNodeWithText("Leave ambiguous").assertDoesNotExist()
    }

    @Test
    fun `R_242_what_the_model_said_shows_the_real_surviving_transcript_text`() {
        composeTestRule.setContent {
            OrtTheme {
                TransmissionDetailScreen(
                    state = DetailViewStateMapper.from(
                        detail(transcriptText = "help help mayday mayday"),
                        rejected = RejectedViewState("hallucination phrase match"),
                    ),
                    player = FakeTransmissionAudioPlayer(),
                )
            }
        }

        composeTestRule.onNodeWithText("What the model said", ignoreCase = true, substring = true).assertExists()
        composeTestRule.onNodeWithText("help help mayday mayday").assertExists()
    }
}
