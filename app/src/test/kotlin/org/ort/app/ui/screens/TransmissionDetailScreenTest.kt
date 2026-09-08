package org.ort.app.ui.screens

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.audio.FakeTransmissionAudioPlayer
import org.ort.app.ui.data.CandidateInspectionViewState
import org.ort.app.ui.data.DetailViewStateMapper
import org.ort.app.ui.data.InspectionViewState
import org.ort.app.ui.data.LatticeInspectionViewState
import org.ort.app.ui.data.PassFailureViewState
import org.ort.app.ui.data.PriorContributionViewState
import org.ort.app.ui.data.TransmissionDetailViewState
import org.ort.app.ui.theme.OrtTheme
import org.ort.core.Attribution
import org.ort.core.PassId
import org.robolectric.RobolectricTestRunner

/**
 * The transmission detail drill-in (ui-conformance WP6, R-050/R-051/R-057; originally build-plan
 * P14, `design/canvas/Detail.dc.html` = INFERRED, `Detail-Confirmed.dc.html`). Four states, one
 * layout — see [org.ort.app.ui.data.DetailViewStateMapper]'s class doc for the state → explanation
 * mapping this test exercises.
 */
@RunWith(RobolectricTestRunner::class)
class TransmissionDetailScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun detail(
        attribution: Attribution = Attribution.inferred("K7LWH", 0.82),
        transcriptText: String = "roger that, good copy on the repeater this morning",
        revisionHistory: List<String> = emptyList(),
        hasAudio: Boolean = true,
        inspection: InspectionViewState = InspectionViewState.EMPTY,
    ) = TransmissionDetailViewState(
        id = "TX1",
        timeLabel = "02:14:22",
        frequencyLabel = "145.230",
        durationLabel = "4.2s",
        signalLabel = "S5",
        attribution = attribution,
        transcriptText = transcriptText,
        revisionHistory = revisionHistory,
        hasAudio = hasAudio,
        inspection = inspection,
    )

    private fun state(detail: TransmissionDetailViewState = detail()) = DetailViewStateMapper.from(detail)

    @Test
    fun `FR_UI_5 the play control asks the player for this transmission's audio`() {
        val player = FakeTransmissionAudioPlayer()
        composeTestRule.setContent {
            OrtTheme { TransmissionDetailScreen(state = state(), player = player) }
        }

        composeTestRule.onNodeWithContentDescription("Play retained audio").performClick()

        composeTestRule.waitForIdle()
        assert(player.playCalls == listOf("TX1")) { "expected play(\"TX1\") but calls were ${player.playCalls}" }
    }

    @Test
    fun `FR_UI_5 a transmission with no retained audio shows no play control rather than a dead button`() {
        composeTestRule.setContent {
            OrtTheme {
                TransmissionDetailScreen(
                    state = state(detail(hasAudio = false)),
                    player = FakeTransmissionAudioPlayer(),
                )
            }
        }

        composeTestRule.onNodeWithText("No retained audio for this transmission").assertExists()
    }

    /**
     * R-054: WP2's `WaveformCard.onScrub` reports a tap/drag on the waveform as a `0f..1f`
     * fraction; this screen wires it straight to [org.ort.app.ui.audio.TransmissionAudioPlayer.seekToFraction]
     * — proven by a real touch dispatched at the waveform's own bounds (`performTouchInput`, not a
     * direct lambda call, so this proves the wiring reaches the actual composed gesture handler,
     * not just that a Kotlin function reference is correct) reaching [FakeTransmissionAudioPlayer].
     */
    @Test
    fun `R_054_scrubbing_the_waveform_seeks_the_player`() {
        val player = FakeTransmissionAudioPlayer()
        composeTestRule.setContent {
            OrtTheme { TransmissionDetailScreen(state = state(), player = player) }
        }

        val node = composeTestRule.onNodeWithTag("waveform-card")
        val bounds = node.fetchSemanticsNode().size
        node.performTouchInput {
            click(Offset(bounds.width * 0.8f, bounds.height / 2f))
        }
        composeTestRule.waitForIdle()

        assert(player.seekCalls.isNotEmpty()) { "expected at least one seekToFraction call, got none" }
        assert(player.seekCalls.last() in 0f..1f) { "fraction out of range: ${player.seekCalls.last()}" }
    }

    @Test
    fun `the transcript and attribution are both shown`() {
        composeTestRule.setContent {
            OrtTheme {
                TransmissionDetailScreen(
                    state = state(detail(attribution = Attribution.confirmed("W7NPC", 0.95))),
                    player = FakeTransmissionAudioPlayer(),
                )
            }
        }

        composeTestRule.onNodeWithText("roger that, good copy on the repeater this morning").assertExists()
        composeTestRule.onNodeWithContentDescription("filled circle, Confirmed, W7NPC", substring = true).assertExists()
    }

    /**
     * FR-UI-4, rewritten by this package's audit of the old assertion here (`States.dc.html`,
     * design-guide.md §6.2): the score chip is reserved for INFERRED alone — never a bare number
     * on CONFIRMED — but a confidence value is never *omitted* either. It renders as prose in the
     * header's explanation sentence for every state that carries one.
     */
    @Test
    fun `FR_UI_4_confidence_is_present_in_the_header_sentence_and_the_chip_only_on_INFERRED`() {
        composeTestRule.setContent {
            OrtTheme {
                TransmissionDetailScreen(
                    state = state(detail(attribution = Attribution.confirmed("W7NPC", 0.94))),
                    player = FakeTransmissionAudioPlayer(),
                )
            }
        }
        // CONFIRMED: the confidence is in the explanation sentence...
        composeTestRule.onNodeWithText("0.94", substring = true).assertExists()
        // ...but never as a score-chip content description (that phrase only ever appears for INFERRED).
        composeTestRule.onNodeWithContentDescription("confidence 0.94", substring = true).assertDoesNotExist()
    }

    @Test
    fun `FR_UI_4 INFERRED carries its confidence in prose and a score chip beside the callsign`() {
        composeTestRule.setContent {
            OrtTheme {
                TransmissionDetailScreen(
                    state = state(detail(attribution = Attribution.inferred("K7LWH", 0.82))),
                    player = FakeTransmissionAudioPlayer(),
                )
            }
        }

        // "0.82" alone is ambiguous — it is both the score chip and the prose sentence; "Confidence
        // 0.82" (the prose wording) is unique to the sentence.
        composeTestRule.onNodeWithText("Confidence 0.82", substring = true).assertExists()
        composeTestRule.onNodeWithContentDescription("confidence 0.82", substring = true).assertExists()
    }

    @Test
    fun `FR_UI_4 an UNKNOWN attribution shows no confidence number rather than a fabricated one`() {
        composeTestRule.setContent {
            OrtTheme {
                TransmissionDetailScreen(
                    state = state(detail(attribution = Attribution.unknown())),
                    player = FakeTransmissionAudioPlayer(),
                )
            }
        }

        composeTestRule.onNode(looksLikeAConfidenceNumber).assertDoesNotExist()
    }

    /**
     * R-050: the header now renders through WP2's `TitleAttributionRow` (27sp mono, marker at
     * `MARKER_TITLE_SIZE`) rather than `AttributionRow` at card size — proven by the visible
     * callsign existing and the marker's content description still naming the state, for every
     * state including AMBIGUOUS, whose "or QRF" alternate `TitleAttributionRow` has no parameter
     * for and this screen renders as one further `Text` beside it.
     */
    @Test
    fun `R_050_the_header_renders_the_title_attribution_row`() {
        composeTestRule.setContent {
            OrtTheme {
                TransmissionDetailScreen(
                    state = state(detail(attribution = Attribution.confirmed("W7NPC", 0.94))),
                    player = FakeTransmissionAudioPlayer(),
                )
            }
        }

        composeTestRule.onNodeWithText("W7NPC").assertExists()
        composeTestRule
            .onNodeWithContentDescription("filled circle, Confirmed, W7NPC", substring = true)
            .assertExists()
    }

    @Test
    fun `R_050 AMBIGUOUS renders the alternate callsign beside the title attribution row`() {
        val inspection = InspectionViewState(
            lattice = null,
            candidates = listOf(
                CandidateInspectionViewState("KE7QRS", 0, 0.51, true, true, false, emptyList()),
                CandidateInspectionViewState("KE7QRF", 1, 0.46, true, false, false, emptyList()),
            ),
        )
        composeTestRule.setContent {
            OrtTheme {
                TransmissionDetailScreen(
                    state = state(detail(attribution = Attribution.ambiguous(), inspection = inspection)),
                    player = FakeTransmissionAudioPlayer(),
                )
            }
        }

        composeTestRule.onNodeWithText("KE7QRS").assertExists()
        composeTestRule.onNodeWithText("or KE7QRF").assertExists()
    }

    @Test
    fun `R_055 a superseded transcript's earlier versions stay reachable, one tap away`() {
        var opened = false
        composeTestRule.setContent {
            OrtTheme {
                TransmissionDetailScreen(
                    state = state(detail(revisionHistory = listOf("first partial"))),
                    player = FakeTransmissionAudioPlayer(),
                    onOpenRevisions = { opened = true },
                )
            }
        }

        composeTestRule.onNodeWithText("1 earlier version").performClick()
        assert(opened)
    }

    @Test
    fun `AC_14 the candidate list is viewable inline, with a link to the full lattice`() {
        val inspection = InspectionViewState(
            lattice = LatticeInspectionViewState(
                source = "TEXT_DERIVED",
                modelId = "text-derived-v1",
                createdAt = 100L,
            ),
            candidates = listOf(
                CandidateInspectionViewState(
                    callsign = "K7ABC",
                    rank = 0,
                    score = 1.5,
                    grammarValid = true,
                    databaseHit = true,
                    selected = true,
                    priorContributions = listOf(
                        PriorContributionViewState(priorName = "database", logOdds = 0.8, isColdStart = false),
                    ),
                ),
            ),
        )
        var openedWhy = false
        composeTestRule.setContent {
            OrtTheme {
                TransmissionDetailScreen(
                    state = state(detail(inspection = inspection)),
                    player = FakeTransmissionAudioPlayer(),
                    onOpenWhy = { openedWhy = true },
                )
            }
        }

        composeTestRule.onNodeWithText("K7ABC", substring = true).assertExists()
        composeTestRule.onNodeWithText("Full lattice").performClick()
        assert(openedWhy)
    }

    @Test
    fun `not right opens the correction flow`() {
        var notRightCalled = false
        composeTestRule.setContent {
            OrtTheme {
                TransmissionDetailScreen(
                    state = state(detail(attribution = Attribution.confirmed("W7NPC", 0.95))),
                    player = FakeTransmissionAudioPlayer(),
                    onNotRight = { notRightCalled = true },
                )
            }
        }

        composeTestRule.onNodeWithText("Not right?").performClick()
        assert(notRightCalled)
    }

    // ---- R-153, F18 Fail-Pass, FR-RUN-9 ----

    private fun failedState(attempts: Int = 3, lastError: String = "out of memory in the decoder") =
        DetailViewStateMapper.from(
            detail(attribution = Attribution.unknown(), transcriptText = "okay so for the net tonight"),
            PassFailureViewState(
                passId = PassId.B_OFFLINE,
                passLabel = "Pass B",
                lastError = lastError,
                attempts = attempts,
            ),
        )

    @Test
    fun `R_153_a_failed_pass_shows_which_pass_failed_the_recorded_error_and_attempts`() {
        composeTestRule.setContent {
            OrtTheme { TransmissionDetailScreen(state = failedState(), player = FakeTransmissionAudioPlayer()) }
        }

        composeTestRule.onNodeWithText("not transcribed").assertExists()
        composeTestRule.onNodeWithText("Pass B errored 3 times", substring = true).assertExists()
        // SectionHeader uppercases its label — matched case-insensitively rather than assuming the exact case.
        composeTestRule
            .onNodeWithText("What went wrong · 3 times", substring = true, ignoreCase = true)
            .assertExists()
        composeTestRule.onNodeWithTag("pass-failure-last-error").assertExists()
        composeTestRule.onNodeWithText("Out of memory in the decoder", substring = true).assertExists()
    }

    @Test
    fun `R_153_the_rest_of_the_over_still_renders_audio_and_partial_text_when_a_pass_has_failed`() {
        composeTestRule.setContent {
            OrtTheme { TransmissionDetailScreen(state = failedState(), player = FakeTransmissionAudioPlayer()) }
        }

        // The waveform card (audio) and the Pass A partial transcript both still render — a failed
        // pass never hides the rest of the over (constitution III, FR-RUN-9).
        composeTestRule.onNodeWithTag("waveform-card").assertExists()
        composeTestRule.onNodeWithText("okay so for the net tonight", substring = true).assertExists()
    }

    @Test
    fun `FR_RUN_9_retry_this_pass_is_always_offered_and_calls_back`() {
        var retried = false
        composeTestRule.setContent {
            OrtTheme {
                TransmissionDetailScreen(
                    state = failedState(),
                    player = FakeTransmissionAudioPlayer(),
                    onRetryPass = { retried = true },
                )
            }
        }

        composeTestRule.onNodeWithText("Retry now").performClick()
        assert(retried)
    }

    @Test
    fun `FR_RUN_9_keep_the_partial_is_offered_beside_retry_and_never_blocks_the_screen`() {
        var kept = false
        composeTestRule.setContent {
            OrtTheme {
                TransmissionDetailScreen(
                    state = failedState(),
                    player = FakeTransmissionAudioPlayer(),
                    onKeepPartial = { kept = true },
                )
            }
        }

        composeTestRule.onNodeWithText("Keep the partial").performClick()
        assert(kept)
    }

    /** Matches any node whose visible text looks like a two-decimal confidence value (e.g. "0.82"). */
    private val looksLikeAConfidenceNumber = SemanticsMatcher("has text matching a confidence number (0.NN)") { node ->
        val texts = node.config.getOrNull(SemanticsProperties.Text).orEmpty()
        texts.any { Regex("""^\d\.\d\d$""").matches(it.text) }
    }
}
