package org.ort.app.ui.screens

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.audio.FakeTransmissionAudioPlayer
import org.ort.app.ui.data.CandidateInspectionViewState
import org.ort.app.ui.data.CorrectionPolling
import org.ort.app.ui.data.DetailViewStateMapper
import org.ort.app.ui.data.InspectionViewState
import org.ort.app.ui.data.LatticeInspectionViewState
import org.ort.app.ui.data.PassFailureViewState
import org.ort.app.ui.data.PriorContributionViewState
import org.ort.app.ui.data.TransmissionDetailViewState
import org.ort.app.ui.theme.OrtColors
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

    /**
     * Register R-423 (design), narrower than the "never omitted" test above's own general rule:
     * `Detail.dc.html` (INFERRED's own real board) carries no "Confidence 0.82." clause at all —
     * confirmed by reading its own markup directly. The chip alone carries the number for this
     * state; CONFIRMED (that test, above) is unaffected.
     */
    @Test
    fun `FR_UI_4 INFERRED carries its confidence only in the score chip, never duplicated in prose`() {
        composeTestRule.setContent {
            OrtTheme {
                TransmissionDetailScreen(
                    state = state(detail(attribution = Attribution.inferred("K7LWH", 0.82))),
                    player = FakeTransmissionAudioPlayer(),
                )
            }
        }

        composeTestRule.onNodeWithText("Confidence 0.82", substring = true).assertDoesNotExist()
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

        // R-180: the primary candidate's own callsign now also appears a second time, in the real
        // "why this callsign" candidate row this package's own R-180 fix added — `onAllNodesWithText`
        // rather than `onNodeWithText`, since this test only needs to prove the title row shows it
        // at all, not that it is the *only* place on screen that does.
        composeTestRule.onAllNodesWithText("KE7QRS").onFirst().assertExists()
        composeTestRule.onNodeWithText("or KE7QRF").assertExists()
    }

    /**
     * R-186 (design): `Attribution.ambiguous()` carries no `stationId` at all (constitution I), so
     * the title row's own callsign — "KE7QRS" — must come from the resolver's top-ranked candidate,
     * not `attribution.stationId`, or the header reads only "or KE7QRF" with nothing before it.
     */
    @Test
    fun `R_186_the_ambiguous_title_names_the_primary_candidate_not_just_the_alternate`() {
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

        // The title row's own marker + callsign — the full "half-filled circle, Ambiguous, ..."
        // description is unique to `TitleAttributionRow`'s own merged node, distinct from the (also
        // real, also present) inline "why" preview row and the ambiguous chooser row.
        composeTestRule
            .onNodeWithContentDescription("half-filled circle, Ambiguous, KE7QRS", substring = true)
            .assertExists()
    }

    /**
     * R-187 (spec): each ambiguous candidate is one evidence-bearing, clickable row (real ITU
     * country when the candidate has one — never a fabricated per-repeater heard-count this mapper
     * cannot back), not a display row plus a separate "Choose X" link.
     */
    @Test
    fun `R_187_each_ambiguous_candidate_is_one_evidence_bearing_row_with_a_visible_score`() {
        val inspection = InspectionViewState(
            lattice = null,
            candidates = listOf(
                CandidateInspectionViewState(
                    "KE7QRS",
                    0,
                    0.51,
                    grammarValid = true,
                    databaseHit = true,
                    selected = false,
                    priorContributions = emptyList(),
                    ituCountry = "United States",
                ),
                CandidateInspectionViewState(
                    "KE7QRF",
                    1,
                    0.46,
                    grammarValid = true,
                    databaseHit = false,
                    selected = false,
                    priorContributions = emptyList(),
                ),
            ),
        )
        var chosen: String? = null
        composeTestRule.setContent {
            OrtTheme {
                TransmissionDetailScreen(
                    state = state(detail(attribution = Attribution.ambiguous(), inspection = inspection)),
                    player = FakeTransmissionAudioPlayer(),
                    onChooseCandidate = { chosen = it },
                )
            }
        }

        composeTestRule.onNodeWithText("0.51").assertExists()
        composeTestRule.onNodeWithText("a known station · United States", substring = true).assertExists()
        composeTestRule.onNodeWithText("never heard before").assertExists()
        composeTestRule
            .onNodeWithContentDescription("Choose KE7QRS", substring = true)
            .performClick()
        assert(chosen == "KE7QRS") { "got $chosen" }
    }

    /**
     * Register R-425: with real, per-candidate evidence looked up, the two chooser rows read
     * *distinct* facts — before this fix both rows read the identical "a known station · United
     * States" whenever the two candidates shared an ITU country (the common case).
     */
    @Test
    fun `R_425_ambiguous_candidates_carry_distinct_real_evidence_when_it_is_looked_up`() {
        val inspection = InspectionViewState(
            lattice = null,
            candidates = listOf(
                CandidateInspectionViewState(
                    "KE7QRS",
                    0,
                    0.51,
                    grammarValid = true,
                    databaseHit = true,
                    selected = false,
                    priorContributions = emptyList(),
                    ituCountry = "United States",
                ),
                CandidateInspectionViewState(
                    "KE7QRF",
                    1,
                    0.46,
                    grammarValid = true,
                    databaseHit = false,
                    selected = false,
                    priorContributions = emptyList(),
                    ituCountry = "United States",
                ),
            ),
        )
        val evidence = mapOf(
            "KE7QRS" to CorrectionPolling.AmbiguousCandidateEvidenceViewState(
                heardCount = 4,
                lastHeardLabel = "01:12:00",
                voiceOnFile = true,
            ),
            "KE7QRF" to CorrectionPolling.AmbiguousCandidateEvidenceViewState(
                heardCount = 0,
                lastHeardLabel = null,
                voiceOnFile = false,
            ),
        )
        val viewState = DetailViewStateMapper.from(
            detail(attribution = Attribution.ambiguous(), inspection = inspection),
            ambiguousEvidence = evidence,
        )
        composeTestRule.setContent {
            OrtTheme { TransmissionDetailScreen(state = viewState, player = FakeTransmissionAudioPlayer()) }
        }

        // Real, distinct evidence for each row — never the identical "a known station · United
        // States" both rows shared before this fix.
        composeTestRule.onNodeWithText("heard 4 times", substring = true).assertExists()
        composeTestRule.onNodeWithText("last 01:12:00", substring = true).assertExists()
        composeTestRule.onNodeWithText("voice match", substring = true).assertExists()
        composeTestRule.onNodeWithText("never heard before", substring = true).assertExists()
        composeTestRule.onNodeWithText("a known station", substring = true).assertDoesNotExist()
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

    /** Matches any node whose visible text looks like a two-decimal confidence value (e.g. "0.82"). */
    private val looksLikeAConfidenceNumber = SemanticsMatcher("has text matching a confidence number (0.NN)") { node ->
        val texts = node.config.getOrNull(SemanticsProperties.Text).orEmpty()
        texts.any { Regex("""^\d\.\d\d$""").matches(it.text) }
    }

    // ---- R-193, `Detail-Playback.dc.html`'s no-audio card: never-captured vs deleted-by-retention ----

    @Test
    fun `R_193_a_transmission_that_was_never_processed_reads_as_never_retained`() {
        composeTestRule.setContent {
            OrtTheme {
                TransmissionDetailScreen(
                    state = state(detail(attribution = Attribution.unknown(), hasAudio = false)),
                    player = FakeTransmissionAudioPlayer(),
                )
            }
        }

        composeTestRule.onNodeWithText("Audio was never retained for this over.").assertExists()
        composeTestRule.onNodeWithText("Audio deleted by retention", substring = true).assertDoesNotExist()
    }

    @Test
    fun `R_193_a_transmission_with_a_real_attribution_reads_as_deleted_by_retention_with_the_lattice_kept_clause`() {
        composeTestRule.setContent {
            OrtTheme {
                TransmissionDetailScreen(
                    state = state(detail(attribution = Attribution.confirmed("W7NPC", 0.95), hasAudio = false)),
                    player = FakeTransmissionAudioPlayer(),
                )
            }
        }

        composeTestRule
            .onNodeWithText("Audio deleted by retention. Transcript, attribution and lattice were kept.")
            .assertExists()
        composeTestRule.onNodeWithText("Audio was never retained", substring = true).assertDoesNotExist()
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

    // ---- R-188, `Detail.dc.html`: the transcript-confidence caption ----

    @Test
    fun `R_188_a_real_recorded_confidence_renders_as_a_plain_caption_below_the_transcript`() {
        composeTestRule.setContent {
            OrtTheme {
                TransmissionDetailScreen(
                    state = DetailViewStateMapper.from(detail(), transcriptConfidence = 0.61),
                    player = FakeTransmissionAudioPlayer(),
                )
            }
        }

        composeTestRule.onNodeWithText("Transcript confidence 0.61").assertExists()
    }

    @Test
    fun `R_188_no_recorded_confidence_renders_no_caption_rather_than_a_fabricated_number`() {
        composeTestRule.setContent {
            OrtTheme {
                TransmissionDetailScreen(
                    state = DetailViewStateMapper.from(detail(), transcriptConfidence = null),
                    player = FakeTransmissionAudioPlayer(),
                )
            }
        }

        composeTestRule.onNodeWithText("Transcript confidence", substring = true).assertDoesNotExist()
    }

    // R-242/R-331 (F04 `Fail-Hallucination.dc.html`, the rejected detail state) moved to
    // `RejectedDetailScreenTest.kt` — detekt's `LargeClass` finding, this file's own size after
    // this round's R-423/R-425/R-426 tests; the same fix `RowsTest.kt`'s own `NavRowTest.kt` split
    // already used.

    // ---- R-182, `highlightedTranscript`: the real char span, falling back to a literal indexOf ----

    @Test
    fun `R_182_highlightedTranscript_highlights_the_real_span_for_a_phonetic_spelling`() {
        val text = "this is kilo seven lima whiskey hotel, monitoring"
        val span = 8 until 32 // "kilo seven lima whiskey hotel"

        val result = highlightedTranscript(text, "K7LWH", span, OrtColors.highlightAmber)

        assertEquals(text, result.text)
        val styled = result.spanStyles.single()
        assertEquals(8, styled.start)
        assertEquals(32, styled.end)
    }

    @Test
    fun `R_182_highlightedTranscript_falls_back_to_indexOf_when_no_span_is_recorded`() {
        val text = "this is K7LWH, monitoring"

        val result = highlightedTranscript(text, "K7LWH", null, OrtColors.highlightAmber)

        val styled = result.spanStyles.single()
        assertEquals(text.indexOf("K7LWH"), styled.start)
        assertEquals(text.indexOf("K7LWH") + "K7LWH".length, styled.end)
    }

    @Test
    fun `R_182_highlightedTranscript_highlights_nothing_when_neither_a_span_nor_a_literal_match_exists`() {
        val text = "this is kilo seven lima whiskey hotel, monitoring"

        val result = highlightedTranscript(text, "K7LWH", null, OrtColors.highlightAmber)

        assertTrue(result.spanStyles.isEmpty())
    }

    @Test
    fun `R_182_highlightedTranscript_ignores_an_out_of_bounds_span_and_falls_back_to_indexOf`() {
        val text = "K7LWH here"
        val badSpan = 100 until 105

        val result = highlightedTranscript(text, "K7LWH", badSpan, OrtColors.highlightAmber)

        val styled = result.spanStyles.single()
        assertEquals(0, styled.start)
        assertEquals(5, styled.end)
    }
}
