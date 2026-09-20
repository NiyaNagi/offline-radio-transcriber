package org.ort.app.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.CorrectionScope
import org.ort.app.ui.data.CorrectionTier
import org.ort.app.ui.data.RankedCandidateViewState
import org.ort.app.ui.data.StationSearchOutcome
import org.ort.app.ui.data.StationSearchRow
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner

/**
 * R-052/R-058/R-185, `Detail-Correct-A/B/C.dc.html`: the three tiers in order. Tier A/B name an
 * already-known identity and propagate by default (`Flow-Correct.dc.html`'s own example); tier C
 * is recorded unverified and asks for scope explicitly.
 */
@RunWith(RobolectricTestRunner::class)
class CorrectionSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val noStations: suspend (String) -> StationSearchOutcome = { StationSearchOutcome(emptyList(), 0, 0) }

    @Test
    fun `R_052 picking a resolver candidate applies PICK_CANDIDATE, propagated by default`() {
        var applied: Triple<String, CorrectionTier, CorrectionScope>? = null
        composeTestRule.setContent {
            OrtTheme {
                CorrectionSheet(
                    currentCallsign = "K7LWH",
                    candidates = listOf(
                        RankedCandidateViewState("KA7LWH", "score 4.4", chosen = false),
                        RankedCandidateViewState("K7LVH", "score 3.1", chosen = false),
                    ),
                    everyOverSameCallsignCount = 6,
                    onSearchStations = noStations,
                    onApply = { callsign, tier, scope -> applied = Triple(callsign, tier, scope) },
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Correct to KA7LWH", substring = true).performClick()

        assert(applied == Triple("KA7LWH", CorrectionTier.PICK_CANDIDATE, CorrectionScope.EVERY_OVER_SAME_CALLSIGN)) {
            "got $applied"
        }
    }

    /**
     * R-185 (halt): Tier B searches stations *this device has heard* — distinct from Tier C's
     * grammar validator, and from the true lexicon search audit F-018 gave the old call site (see
     * `CorrectionPolling.searchHeardStations`'s own doc comment). The board's own evidence
     * ("heard N times", "voice on file") and "Matches · N of M" both render from the real
     * `StationSearchOutcome`, never a placeholder.
     */
    @Test
    fun `R_185 searching heard stations shows real evidence and applies SEARCH_LEXICON`() {
        var applied: Triple<String, CorrectionTier, CorrectionScope>? = null
        var searched: String? = null
        composeTestRule.setContent {
            OrtTheme {
                CorrectionSheet(
                    currentCallsign = "K7LWH",
                    candidates = emptyList(),
                    everyOverSameCallsignCount = 1,
                    onSearchStations = { query ->
                        searched = query
                        StationSearchOutcome(
                            rows = listOf(
                                StationSearchRow(
                                    stationId = "K9ZZZ",
                                    evidence = "heard 4 times · voice on file",
                                    hasVoiceOnFile = true,
                                    userName = null,
                                ),
                            ),
                            matchCount = 1,
                            totalCount = 112,
                        )
                    },
                    onApply = { callsign, tier, scope -> applied = Triple(callsign, tier, scope) },
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("A station heard before").performClick()
        composeTestRule.waitForIdle()
        // SectionHeader uppercases its label — matched case-insensitively rather than assuming the exact case.
        composeTestRule.onNodeWithText("Matches", substring = true, ignoreCase = true).assertExists()
        composeTestRule.onNodeWithText("1 of 112", substring = true, ignoreCase = true).assertExists()
        composeTestRule.onNodeWithText("heard 4 times", substring = true).assertExists()
        composeTestRule.onNodeWithContentDescription("Search stations heard").performTextInput("K9")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Correct to K9ZZZ", substring = true).performClick()

        assert(searched == "K9")
        assert(applied == Triple("K9ZZZ", CorrectionTier.SEARCH_LEXICON, CorrectionScope.EVERY_OVER_SAME_CALLSIGN))
    }

    @Test
    fun `R_185 not here routes from Tier B to Tier C`() {
        composeTestRule.setContent {
            OrtTheme {
                CorrectionSheet(
                    currentCallsign = "K7LWH",
                    candidates = emptyList(),
                    everyOverSameCallsignCount = 1,
                    onSearchStations = noStations,
                    onApply = { _, _, _ -> },
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("A station heard before").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Not here — type a callsign instead").performClick()

        composeTestRule.onNodeWithContentDescription("Typed callsign").assertExists()
    }

    @Test
    fun `R_052_R_058 typing a callsign defaults to this-over-only and is recorded FREE_TEXT`() {
        var applied: Triple<String, CorrectionTier, CorrectionScope>? = null
        composeTestRule.setContent {
            OrtTheme {
                CorrectionSheet(
                    currentCallsign = "K7LWH",
                    candidates = emptyList(),
                    everyOverSameCallsignCount = 6,
                    onSearchStations = noStations,
                    onApply = { callsign, tier, scope -> applied = Triple(callsign, tier, scope) },
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Type a callsign").performClick()
        composeTestRule.onNodeWithContentDescription("Typed callsign").performTextInput("N0CALL")
        composeTestRule.onNodeWithText("Save unverified correction").performClick()

        assert(applied == Triple("N0CALL", CorrectionTier.FREE_TEXT, CorrectionScope.THIS_OVER_ONLY)) { "got $applied" }
    }

    /**
     * D45: the radio row's own label is the user-facing assertion this relabel is about — it must
     * say "callsign," never "voice," because `voiceprintId` is null on every transmission this
     * build writes and the scope has always actually matched by callsign (see [CorrectionScope]'s
     * own doc comment). Asserted on the real string content this once, per constitution II's own
     * carve-out for "a factual claim about what propagates" rather than prose a designer owns.
     */
    @Test
    fun `D45 R_058 the every-over-same-callsign option is chooseable and carries the real count`() {
        var applied: Triple<String, CorrectionTier, CorrectionScope>? = null
        composeTestRule.setContent {
            OrtTheme {
                CorrectionSheet(
                    currentCallsign = "K7LWH",
                    candidates = emptyList(),
                    everyOverSameCallsignCount = 6,
                    onSearchStations = noStations,
                    onApply = { callsign, tier, scope -> applied = Triple(callsign, tier, scope) },
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Type a callsign").performClick()
        composeTestRule.onNodeWithContentDescription("Typed callsign").performTextInput("N0CALL")
        composeTestRule.onNodeWithText("Every over with the same callsign").performClick()
        composeTestRule.onNodeWithText("Save unverified correction").performClick()

        assert(applied?.third == CorrectionScope.EVERY_OVER_SAME_CALLSIGN) { "got $applied" }
    }

    /** D45: no surface in this sheet may say "voice" where the propagation is callsign-based. */
    @Test
    fun `D45 no correction-scope copy in this sheet says voice`() {
        composeTestRule.setContent {
            OrtTheme {
                CorrectionSheet(
                    currentCallsign = "K7LWH",
                    candidates = emptyList(),
                    everyOverSameCallsignCount = 6,
                    onSearchStations = noStations,
                    onApply = { _, _, _ -> },
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Type a callsign").performClick()
        composeTestRule.onNodeWithText("Every over matched to this voice").assertDoesNotExist()
        composeTestRule.onNodeWithText("Every over with the same callsign").assertExists()
    }
}
