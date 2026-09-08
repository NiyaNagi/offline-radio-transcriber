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
import org.ort.app.ui.data.CorrectionRequest
import org.ort.app.ui.data.CorrectionTier
import org.ort.app.ui.data.InspectionViewState
import org.ort.app.ui.data.LabelCertainty
import org.ort.app.ui.data.LabelOutcome
import org.ort.app.ui.data.LabelledSample
import org.ort.app.ui.data.PriorContributionViewState
import org.ort.app.ui.data.TransmissionDetailViewState
import org.ort.app.ui.theme.OrtTheme
import org.ort.core.Attribution
import org.ort.pipeline.passb.LexiconMatch
import org.robolectric.RobolectricTestRunner

/**
 * Build-plan P16: FR-UI-6 (one-tap correction, Q8's tiers), FR-UI-8 (the inspection surface) and
 * FR-OBS-4 (labelled-sample capture), all extending P14's [TransmissionDetailScreen] in place.
 */
@RunWith(RobolectricTestRunner::class)
class TransmissionDetailScreenCorrectionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun state(
        attribution: Attribution = Attribution.ambiguous(),
        inspection: InspectionViewState = InspectionViewState.EMPTY,
    ) = TransmissionDetailViewState(
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
    )

    // ---- FR-UI-8: inspection surface ----

    @Test
    fun `FR_UI_8 with no resolver output recorded, the inspection surface says so honestly`() {
        composeTestRule.setContent {
            OrtTheme { TransmissionDetailScreen(state = state(), player = FakeTransmissionAudioPlayer(), onBack = {}) }
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
                    state = state(inspection = inspection),
                    player = FakeTransmissionAudioPlayer(),
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithText("K7ABC", substring = true).assertExists()
        composeTestRule.onNodeWithText("cold start", substring = true).assertExists()
        composeTestRule.onNodeWithText("argued against", substring = true).assertExists()
    }

    // ---- FR-UI-6: correction, Q8's tiers ----

    @Test
    fun `FR_UI_6 picking a resolved candidate applies a verified correction`() {
        var applied: CorrectionRequest? = null
        val inspection = InspectionViewState(
            lattice = null,
            candidates = listOf(
                CandidateInspectionViewState("W7NPC", 0, 2.0, true, true, true, emptyList()),
            ),
        )
        composeTestRule.setContent {
            OrtTheme {
                TransmissionDetailScreen(
                    state = state(inspection = inspection),
                    player = FakeTransmissionAudioPlayer(),
                    onBack = {},
                    onCorrect = { applied = it },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Correct attribution").performScrollTo().performClick()
        composeTestRule.onNodeWithContentDescription("Correct to W7NPC").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assert(applied?.newStationId == "W7NPC") { "expected a correction to W7NPC, got $applied" }
        assert(applied?.tier == CorrectionTier.PICK_CANDIDATE)
    }

    @Test
    fun `Q8 free text correction is marked unverified`() {
        var applied: CorrectionRequest? = null
        composeTestRule.setContent {
            OrtTheme {
                TransmissionDetailScreen(
                    state = state(),
                    player = FakeTransmissionAudioPlayer(),
                    onBack = {},
                    onCorrect = { applied = it },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Correct attribution").performScrollTo().performClick()
        composeTestRule.onNodeWithContentDescription("Free-text station (unverified)")
            .performScrollTo().performTextInput("N0CALL")
        composeTestRule.onNodeWithContentDescription("Save unverified correction").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assert(applied?.tier == CorrectionTier.FREE_TEXT) { "expected a FREE_TEXT tier, got $applied" }
        assert(applied?.newStationId == "N0CALL")
    }

    @Test
    fun `FR_UI_6_Q8 searching the lexicon and picking a result applies a verified correction`() {
        var applied: CorrectionRequest? = null
        var searched: String? = null
        composeTestRule.setContent {
            OrtTheme {
                TransmissionDetailScreen(
                    state = state(),
                    player = FakeTransmissionAudioPlayer(),
                    onBack = {},
                    onCorrect = { applied = it },
                    onSearchLexicon = { query ->
                        searched = query
                        listOf(LexiconMatch("K9ZZZ", ituPrefix = "K", ituCountry = "United States", ituIso = "US"))
                    },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Correct attribution").performScrollTo().performClick()
        composeTestRule.onNodeWithContentDescription("Search the lexicon").performScrollTo().performTextInput("K9")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Correct to K9ZZZ").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assert(searched == "K9") { "expected the lexicon search seam to be called with the typed query, got $searched" }
        assert(applied?.tier == CorrectionTier.SEARCH_LEXICON)
        assert(applied?.newStationId == "K9ZZZ")
    }

    // ---- FR-OBS-4: labelled-sample capture ----

    @Test
    fun `FR_OBS_4 recording a labelled sample writes the session and sample window from the transmission`() {
        var recorded: LabelledSample? = null
        composeTestRule.setContent {
            OrtTheme {
                TransmissionDetailScreen(
                    state = state(),
                    player = FakeTransmissionAudioPlayer(),
                    onBack = {},
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
                    onBack = {},
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
}
