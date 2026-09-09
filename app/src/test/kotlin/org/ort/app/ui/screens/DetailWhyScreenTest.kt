package org.ort.app.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
}
