package org.ort.app.ui.screens

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.audio.FakeTransmissionAudioPlayer
import org.ort.app.ui.data.DetailViewStateMapper
import org.ort.app.ui.data.InspectionViewState
import org.ort.app.ui.data.PassAttemptViewState
import org.ort.app.ui.data.PassFailureViewState
import org.ort.app.ui.data.TransmissionDetailViewState
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtTheme
import org.ort.core.Attribution
import org.ort.core.PassId
import org.robolectric.RobolectricTestRunner

/**
 * `TransmissionDetailScreenTest.kt` split — detekt's `LargeClass` finding, the same fix
 * `RejectedDetailScreenTest.kt`'s own doc comment describes in full (and the earlier
 * `RowsTest.kt`→`NavRowTest.kt`/`ScenariosTest.kt`→`FailureOverrideScenariosTest.kt` splits before
 * it): adding round 12's `R_426` per-attempt tests grew the original file past the threshold. This
 * file owns the failed-pass family — R-153/R-195/R-196/R-426 (`Fail-Pass.dc.html`, FR-RUN-9) — plus
 * R-423 (its own tests already lived beside the failed-pass block before this split). The four
 * detail-state/inspection/revisions/playback tests stay in `TransmissionDetailScreenTest.kt`.
 */
@RunWith(RobolectricTestRunner::class)
class PassFailureDetailScreenTest {

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

    // ---- R-153, F18 Fail-Pass, FR-RUN-9 ----

    private fun failedState(
        attempts: Int = 3,
        lastError: String = "out of memory in the decoder",
        attemptLog: List<PassAttemptViewState> = emptyList(),
        sessionFailedCount: Int = 1,
    ) = DetailViewStateMapper.from(
        detail(attribution = Attribution.unknown(), transcriptText = "okay so for the net tonight"),
        PassFailureViewState(
            passId = PassId.B_OFFLINE,
            passLabel = "Pass B",
            lastError = lastError,
            attempts = attempts,
            attemptLog = attemptLog,
            sessionFailedCount = sessionFailedCount,
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
        // R-426: the header reads "N attempts" (`Fail-Pass.dc.html`'s own exact wording), not "N times".
        composeTestRule
            .onNodeWithText("What went wrong · 3 attempts", substring = true, ignoreCase = true)
            .assertExists()
        composeTestRule.onNodeWithTag("pass-failure-last-error").assertExists()
        composeTestRule.onNodeWithText("Out of memory in the decoder", substring = true).assertExists()
    }

    @Test
    fun `R_426_the_retry_limit_line_names_the_real_attempt_count`() {
        composeTestRule.setContent {
            OrtTheme { TransmissionDetailScreen(state = failedState(), player = FakeTransmissionAudioPlayer()) }
        }

        composeTestRule.onNodeWithTag("pass-failure-retry-limit").assertExists()
        composeTestRule
            .onNodeWithText("Retry limit reached after 3 attempts. Marked failed; the queue continued without it.")
            .assertExists()
    }

    // ---- R-426 (round 12): the real per-attempt history, `:data` schema v6 ----

    @Test
    fun `R_426_attempts_renders_one_real_line_per_attempt_oldest_first_and_words_a_timeout_honestly`() {
        val attemptLog = listOf(
            PassAttemptViewState(timeLabel = "01:23:20", reasonLabel = "Out of memory in the decoder"),
            PassAttemptViewState(timeLabel = "01:24:05", reasonLabel = "Out of memory in the decoder"),
            PassAttemptViewState(timeLabel = "01:25:35", reasonLabel = "Timed out"),
        )
        composeTestRule.setContent {
            OrtTheme {
                TransmissionDetailScreen(
                    state = failedState(attemptLog = attemptLog),
                    player = FakeTransmissionAudioPlayer(),
                )
            }
        }

        composeTestRule.onNodeWithTag("pass-failure-attempt-0")
            .assertTextEquals("01:23:20 · Out of memory in the decoder")
        composeTestRule.onNodeWithTag("pass-failure-attempt-1")
            .assertTextEquals("01:24:05 · Out of memory in the decoder")
        composeTestRule.onNodeWithTag("pass-failure-attempt-2")
            .assertTextEquals("01:25:35 · Timed out")
        // The retry-limit line still follows, using the real stored `attempts` count — unchanged
        // by which of the two attempt-rendering paths ran above it.
        composeTestRule
            .onNodeWithText("Retry limit reached after 3 attempts. Marked failed; the queue continued without it.")
            .assertExists()
        // The round-11 single-line fallback is for records with no `attemptLog` at all — it must
        // not also render once real per-attempt rows exist.
        composeTestRule.onNodeWithTag("pass-failure-last-error").assertDoesNotExist()
    }

    @Test
    fun `R_426_attempts_falls_back_to_the_single_last_error_line_when_no_attempt_rows_exist`() {
        composeTestRule.setContent {
            OrtTheme { TransmissionDetailScreen(state = failedState(), player = FakeTransmissionAudioPlayer()) }
        }

        // `failedState()`'s own default `attemptLog = emptyList()` — a pre-schema-v6 `FAILED` item,
        // honestly.
        composeTestRule.onNodeWithTag("pass-failure-last-error").assertExists()
        composeTestRule.onNodeWithTag("pass-failure-attempt-0").assertDoesNotExist()
    }

    // ---- R-423 (design): the source-over timestamp itself is the inline link ----

    @Test
    fun `R_423_buildInferredExplanationText_tags_exactly_the_time_label_span`() {
        val annotated = buildInferredExplanationText("SRC1", "02:14:07", OrtColors.accentGreen)

        assertEquals(
            "Not heard in this over. Matched by voice to 02:14:07, where the callsign was heard clearly.",
            annotated.text,
        )
        val linkStart = annotated.text.indexOf("02:14:07")
        val linkEnd = linkStart + "02:14:07".length
        assertEquals("SRC1", sourceIdAtOffset(annotated, linkStart))
        assertEquals("SRC1", sourceIdAtOffset(annotated, linkEnd - 1))
        assertEquals(null, sourceIdAtOffset(annotated, linkStart - 1))
        assertEquals(null, sourceIdAtOffset(annotated, linkEnd))
        val styled = annotated.spanStyles.single()
        assertEquals(linkStart, styled.start)
        assertEquals(linkEnd, styled.end)
    }

    @Test
    fun `R_423_buildInferredExplanationText_falls_back_to_the_generic_phrase_when_no_time_is_known`() {
        val annotated = buildInferredExplanationText("SRC1", null, OrtColors.accentGreen)

        assertTrue(annotated.text.contains("Matched by voice to the source over,"))
    }

    @Test
    fun `R_423_the_detail_screen_never_shows_a_separate_Open_the_source_over_line`() {
        composeTestRule.setContent {
            OrtTheme {
                TransmissionDetailScreen(
                    state = state(
                        detail(
                            attribution = Attribution.inferred(
                                "K7LWH",
                                0.82,
                                org.ort.core.TransmissionId.new(),
                            ),
                        ),
                    ),
                    player = FakeTransmissionAudioPlayer(),
                )
            }
        }

        composeTestRule.onNodeWithText("Open the source over").assertDoesNotExist()
        composeTestRule.onNodeWithText("the source over", substring = true).assertExists()
    }

    /**
     * R-196 (halt): a failed pass carries `AttributionState.UNKNOWN` (no pass ever finished to
     * resolve one) purely as an honest byproduct — `state.body` is genuinely
     * `DetailBodyViewState.Unknown` — but the failed-pass header already gives the real account of
     * what happened ("Pass B errored..."). D04's "What was tried"/"Why this callsign" sections speak
     * to a *resolver* that came up empty, which is not this over's story, and must not render
     * alongside the failed-pass header.
     */
    @Test
    fun `R_196_a_failed_pass_never_also_renders_the_unknown_attributions_what_was_tried_block`() {
        composeTestRule.setContent {
            OrtTheme { TransmissionDetailScreen(state = failedState(), player = FakeTransmissionAudioPlayer()) }
        }

        composeTestRule.onNodeWithText("What was tried", substring = true, ignoreCase = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Why this callsign", substring = true, ignoreCase = true).assertDoesNotExist()
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

    // ---- R-195, `Fail-Pass.dc.html`: the "Live partial, Pass A" label/caption and retry guidance ----

    @Test
    fun `R_195_the_failed_pass_partial_carries_its_own_label_and_not_attributed_caption`() {
        composeTestRule.setContent {
            OrtTheme { TransmissionDetailScreen(state = failedState(), player = FakeTransmissionAudioPlayer()) }
        }

        composeTestRule.onNodeWithText("Live partial, Pass A", ignoreCase = true, substring = true).assertExists()
        composeTestRule
            .onNodeWithText("Shown in the log in place of a final transcript. Not attributed — partials never are.")
            .assertExists()
    }

    @Test
    fun `R_195_the_header_carries_the_boards_retry_guidance_sentence`() {
        composeTestRule.setContent {
            OrtTheme { TransmissionDetailScreen(state = failedState(), player = FakeTransmissionAudioPlayer()) }
        }

        composeTestRule
            .onNodeWithText(
                "Retrying by hand runs it alone. If it fails again the error is recorded again, " +
                    "and the over stays exactly as it is.",
            )
            .assertExists()
    }

    // ---- R-564 (register): `Fail-Pass.dc.html` places "What went wrong" after the waveform card
    // and the Live-partial section, not before them (the board's own visual order — header, meta,
    // waveform, Live-partial, divider, "What went wrong · N attempts", closing health line, action
    // bar). Asserted by real on-screen position, not merely by presence, since presence alone
    // (`onNodeWithText`/`onNodeWithTag` on their own) cannot catch a section rendered in the wrong
    // place — the exact gap round 4's device review found. ----

    @Test
    fun `R_564_the_what_went_wrong_block_renders_after_the_waveform_card_and_the_live_partial_section`() {
        val attemptLog = listOf(
            PassAttemptViewState(timeLabel = "01:23:20", reasonLabel = "Out of memory in the decoder"),
            PassAttemptViewState(timeLabel = "01:24:05", reasonLabel = "Out of memory in the decoder"),
            PassAttemptViewState(timeLabel = "01:25:35", reasonLabel = "Timed out"),
        )
        composeTestRule.setContent {
            OrtTheme {
                TransmissionDetailScreen(
                    state = failedState(attemptLog = attemptLog),
                    player = FakeTransmissionAudioPlayer(),
                )
            }
        }

        val waveformTop = composeTestRule.onNodeWithTag("waveform-card").fetchSemanticsNode().boundsInRoot.top
        val livePartialTop = composeTestRule
            .onNodeWithText("Live partial, Pass A", ignoreCase = true, substring = true)
            .fetchSemanticsNode()
            .boundsInRoot
            .top
        val whatWentWrongTop = composeTestRule
            .onNodeWithTag("pass-failure-what-went-wrong")
            .fetchSemanticsNode()
            .boundsInRoot
            .top

        assertTrue(
            "waveform card ($waveformTop) must render above What went wrong ($whatWentWrongTop)",
            waveformTop < whatWentWrongTop,
        )
        assertTrue(
            "Live partial section ($livePartialTop) must render above What went wrong ($whatWentWrongTop)",
            livePartialTop < whatWentWrongTop,
        )
    }

    // ---- R-470 (design, round 13): `Fail-Pass.dc.html`'s own closing "Counted in tonight's health" line ----

    @Test
    fun `R_470_the_boards_closing_paragraph_names_the_real_session_failed_count`() {
        composeTestRule.setContent {
            OrtTheme {
                TransmissionDetailScreen(
                    state = failedState(sessionFailedCount = 1),
                    player = FakeTransmissionAudioPlayer(),
                )
            }
        }

        composeTestRule.onNodeWithTag("pass-failure-session-health").assertTextEquals(
            "Counted in tonight's health: 1 failed. A failed pass never blocks the queue and never " +
                "loses the audio — it just waits for you.",
        )
    }

    @Test
    fun `R_470_the_count_is_the_real_number_never_hardcoded_to_one`() {
        composeTestRule.setContent {
            OrtTheme {
                TransmissionDetailScreen(
                    state = failedState(sessionFailedCount = 4),
                    player = FakeTransmissionAudioPlayer(),
                )
            }
        }

        composeTestRule.onNodeWithText("Counted in tonight's health: 4 failed.", substring = true).assertExists()
    }
}
