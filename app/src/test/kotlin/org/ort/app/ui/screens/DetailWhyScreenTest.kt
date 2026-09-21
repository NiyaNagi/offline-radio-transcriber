package org.ort.app.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.DetailWhyViewState
import org.ort.app.ui.data.RankedCandidateViewState
import org.ort.app.ui.data.SlotDetailViewState
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner

/**
 * Register R-320 (schema v5), `Detail-Why.dc.html` section 1: the exhaustive Why screen's own
 * real per-slot lattice grid, and the honest fallback for a record with none.
 */
@RunWith(RobolectricTestRunner::class)
class DetailWhyScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun why(
        winningSlots: List<SlotDetailViewState> = emptyList(),
        winningGrammarValid: Boolean? = true,
        latticeSummary: String? = "ACOUSTIC · model whisper-small",
    ) = DetailWhyViewState(
        hasData = true,
        latticeSummary = latticeSummary,
        candidates = listOf(RankedCandidateViewState(callsign = "K7LWH", scoreLabel = "score 8.6", chosen = true)),
        priors = emptyList(),
        runnerUp = null,
        winningSlots = winningSlots,
        winningGrammarValid = winningGrammarValid,
    )

    @Test
    fun `R_320_a_real_per_slot_grid_renders_unit_score_and_kept_alternate`() {
        val slots = listOf(
            SlotDetailViewState(unit = "K", score = 0.96, keptAlternate = "C", belowThreshold = false),
            SlotDetailViewState(unit = "W", score = 0.64, keptAlternate = "V", belowThreshold = true),
        )
        composeTestRule.setContent {
            OrtTheme { DetailWhyScreen(callsignLabel = "K7LWH", why = why(winningSlots = slots), onBack = {}) }
        }

        composeTestRule.onNodeWithText("K", substring = false).assertExists()
        composeTestRule.onNodeWithText("W", substring = false).assertExists()
        // `LatticeSlot`'s own score render strips the leading zero ("0.64" -> ".64").
        composeTestRule.onNodeWithText(".64").assertExists()
        composeTestRule.onNodeWithText("C").assertExists()
        composeTestRule.onNodeWithText("V").assertExists()
    }

    @Test
    fun `R_320_no_slot_rows_reads_honestly_not_recorded_rather_than_a_placeholder_grid`() {
        composeTestRule.setContent {
            OrtTheme { DetailWhyScreen(callsignLabel = "K7LWH", why = why(winningSlots = emptyList()), onBack = {}) }
        }

        composeTestRule.onNodeWithText("Per-slot detail is not recorded for this over.").assertExists()
    }

    @Test
    fun `R_320_the_grammar_line_shows_the_winning_candidates_real_bit_not_a_fabricated_parse`() {
        composeTestRule.setContent {
            OrtTheme {
                DetailWhyScreen(callsignLabel = "K7LWH", why = why(winningGrammarValid = true), onBack = {})
            }
        }

        composeTestRule.onNodeWithText("Parsed as a valid callsign grammar.").assertExists()
        // Never the board's own fabricated per-component breakdown this package has no data for.
        composeTestRule.onNodeWithText("region", substring = true, ignoreCase = true).assertDoesNotExist()
    }

    @Test
    fun `R_320_an_invalid_grammar_reads_that_honestly_too`() {
        composeTestRule.setContent {
            OrtTheme {
                DetailWhyScreen(callsignLabel = "K7LWH", why = why(winningGrammarValid = false), onBack = {})
            }
        }

        composeTestRule.onNodeWithText("Did not parse as a valid callsign grammar.").assertExists()
    }

    /** R-1117 (register): `why.runnerUp` is always one of the entries `why.candidates` already
     * carries in full (`DetailViewStateMapper.whyFor`'s own construction) — a second, separate
     * "Runner-up · ..." line duplicated a row the candidates list had just drawn, a defect against
     * `Detail-Why.dc.html`, which lists every surviving candidate once and has no separate
     * runner-up callout at all. */
    @Test
    fun `R_1117_a_runner_up_candidate_is_never_rendered_twice`() {
        val runnerUp = RankedCandidateViewState(callsign = "KA7LWH", scoreLabel = "score 4.4", chosen = false)
        val whyWithRunnerUp = DetailWhyViewState(
            hasData = true,
            latticeSummary = "ACOUSTIC · model whisper-small",
            candidates = listOf(
                RankedCandidateViewState(callsign = "K7LWH", scoreLabel = "score 8.6", chosen = true),
                runnerUp,
            ),
            priors = emptyList(),
            runnerUp = runnerUp,
            winningSlots = emptyList(),
            winningGrammarValid = true,
        )
        composeTestRule.setContent {
            OrtTheme { DetailWhyScreen(callsignLabel = "K7LWH", why = whyWithRunnerUp, onBack = {}) }
        }

        composeTestRule.onAllNodesWithText("KA7LWH", substring = true).assertCountEquals(1)
        composeTestRule.onNodeWithText("Runner-up", substring = true).assertDoesNotExist()
    }

    /**
     * Register R-1144, `Detail-Why.dc.html`'s own bottom bar: reused, not a hand-rolled pair of
     * buttons — real clicks reach the real callbacks a caller supplies.
     */
    @Test
    fun `R_1144_the_bottom_action_bar_renders_and_reaches_its_real_callbacks`() {
        var notRightTapped = false
        var confirmTapped = false
        composeTestRule.setContent {
            OrtTheme {
                DetailWhyScreen(
                    callsignLabel = "K7LWH",
                    why = why(),
                    onBack = {},
                    onNotRight = { notRightTapped = true },
                    onConfirm = { confirmTapped = true },
                )
            }
        }

        composeTestRule.onNodeWithText("Not right?").performClick()
        composeTestRule.onNodeWithText("Confirm").performClick()

        assert(notRightTapped) { "expected onNotRight to have been invoked" }
        assert(confirmTapped) { "expected onConfirm to have been invoked" }
    }

    /**
     * Register R-1144: every caller before this row (every other test in this file included) never
     * passes [DetailWhyScreen.onNotRight]/[DetailWhyScreen.onConfirm] at all — the bar must not
     * appear unless a caller wires *both*, never a half-wired guess.
     */
    @Test
    fun `R_1144_no_action_bar_when_the_caller_supplies_neither_callback`() {
        composeTestRule.setContent {
            OrtTheme { DetailWhyScreen(callsignLabel = "K7LWH", why = why(), onBack = {}) }
        }

        composeTestRule.onNodeWithText("Not right?").assertDoesNotExist()
        composeTestRule.onNodeWithText("Confirm").assertDoesNotExist()
    }
}
