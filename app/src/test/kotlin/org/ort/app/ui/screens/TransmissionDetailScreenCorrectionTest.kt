package org.ort.app.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.audio.FakeTransmissionAudioPlayer
import org.ort.app.ui.data.CandidateInspectionViewState
import org.ort.app.ui.data.DetailViewStateMapper
import org.ort.app.ui.data.InspectionViewState
import org.ort.app.ui.data.LabelCertainty
import org.ort.app.ui.data.LabelOutcome
import org.ort.app.ui.data.LabelledSample
import org.ort.app.ui.data.PriorContributionViewState
import org.ort.app.ui.data.TransmissionDetailViewState
import org.ort.app.ui.theme.OrtTheme
import org.ort.core.Attribution
import org.robolectric.RobolectricTestRunner

/**
 * ui-conformance WP6: R-051's inspection surface honesty (FR-UI-8) and R-056's labelled-sample
 * capture (FR-OBS-4), both still owned by [TransmissionDetailScreen] directly. The three
 * correction-tier tests that used to live here moved to `CorrectionSheetTest` (R-052/R-058) — the
 * inline "▸ Correct attribution" disclosure this file tested no longer exists; the correction UI
 * is now the sheet `Detail-Correct-A/B/C.dc.html` specify, reached via `Not right?`.
 */
@RunWith(RobolectricTestRunner::class)
class TransmissionDetailScreenCorrectionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun state(
        attribution: Attribution = Attribution.ambiguous(),
        inspection: InspectionViewState = InspectionViewState.EMPTY,
    ) = DetailViewStateMapper.from(
        TransmissionDetailViewState(
            id = "TX1",
            timeLabel = "02:14:22",
            frequencyLabel = "145.230",
            durationLabel = "4.2s",
            signalLabel = "S5",
            attribution = attribution,
            transcriptText = "roger that",
            revisionHistory = emptyList(),
            hasAudio = false,
            inspection = inspection,
            sessionId = "SESSION01",
            startSample = 16_000L,
            endSample = 48_000L,
        ),
    )

    // ---- FR-UI-8: inspection surface (R-051) ----

    @Test
    fun `FR_UI_8 with no resolver output recorded, the inspection surface says so honestly`() {
        composeTestRule.setContent {
            OrtTheme {
                TransmissionDetailScreen(
                    state = state(attribution = Attribution.confirmed("W7NPC", 0.9)),
                    player = FakeTransmissionAudioPlayer(),
                )
            }
        }

        composeTestRule.onNodeWithText("No resolver output recorded for this transmission yet.").assertExists()
    }

    @Test
    fun `FR_UI_8 a cold-start prior and an opposing prior render distinctly, not identically`() {
        val inspection = InspectionViewState(
            lattice = null,
            candidates = listOf(
                CandidateInspectionViewState(
                    callsign = "K7ABC",
                    rank = 0,
                    score = 1.5,
                    grammarValid = true,
                    databaseHit = true,
                    selected = true,
                    priorContributions = listOf(
                        PriorContributionViewState("callsign-history", 0.0, isColdStart = true),
                        PriorContributionViewState("propagation", -0.42, isColdStart = false),
                    ),
                ),
            ),
        )

        composeTestRule.setContent {
            OrtTheme {
                TransmissionDetailScreen(
                    state = state(attribution = Attribution.confirmed("K7ABC", 0.9), inspection = inspection),
                    player = FakeTransmissionAudioPlayer(),
                )
            }
        }

        // "K7ABC" alone is ambiguous here — it's both the header callsign and the inline why-section
        // candidate row; the score is unique to the latter.
        composeTestRule.onNodeWithText("score 1.5", substring = true).assertExists()
        composeTestRule.onNodeWithContentDescription("cold start", substring = true).assertExists()
        composeTestRule.onNodeWithContentDescription("argued against", substring = true).assertExists()
    }

    // ---- FR-OBS-4: labelled-sample capture (R-056) ----

    @Test
    fun `FR_OBS_4 recording a labelled sample writes the session and sample window from the transmission`() {
        var recorded: LabelledSample? = null
        composeTestRule.setContent {
            OrtTheme {
                TransmissionDetailScreen(
                    state = state(),
                    player = FakeTransmissionAudioPlayer(),
                    onRecordLabel = { recorded = it },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Record labelled sample").performScrollTo().performClick()
        composeTestRule.onNodeWithContentDescription("Labelled callsign").performScrollTo().performTextInput("K7ABC")
        composeTestRule.onNodeWithContentDescription("Save labelled sample").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        val sample = recorded
        assert(sample != null) { "expected a labelled sample to be recorded" }
        assert(sample!!.sessionId == "SESSION01")
        assert(sample.startSample == 16_000L)
        assert(sample.endSample == 48_000L)
        assert(sample.callsign == "K7ABC")
        assert(sample.certainty == LabelCertainty.CERTAIN)
        assert(sample.outcome == LabelOutcome.SPEECH)
    }

    @Test
    fun `FR_OBS_4 an empty callsign records with no certainty, per the protocol`() {
        var recorded: LabelledSample? = null
        composeTestRule.setContent {
            OrtTheme {
                TransmissionDetailScreen(
                    state = state(),
                    player = FakeTransmissionAudioPlayer(),
                    onRecordLabel = { recorded = it },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Record labelled sample").performScrollTo().performClick()
        composeTestRule.onNodeWithContentDescription("Save labelled sample").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assert(recorded?.callsign == "")
        assert(recorded?.certainty == null)
    }

    @Test
    fun `R_056 the outcome and certainty pickers are visible radio lists, not tap-to-cycle labels`() {
        composeTestRule.setContent {
            OrtTheme { TransmissionDetailScreen(state = state(), player = FakeTransmissionAudioPlayer()) }
        }

        composeTestRule.onNodeWithContentDescription("Record labelled sample").performScrollTo().performClick()

        // Every LabelOutcome value is its own visible row (guide §6.11: a closed set is always a
        // visible list with counts — never a tap-to-cycle label).
        LabelOutcome.entries.forEach { outcome ->
            composeTestRule.onNodeWithText(outcome.wire).performScrollTo().assertExists()
        }
    }
}
