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
import org.ort.app.ui.theme.OrtTheme
import org.ort.pipeline.passb.LexiconMatch
import org.robolectric.RobolectricTestRunner

/**
 * R-052/R-058, `Detail-Correct-A/B/C.dc.html`: the three tiers in order. Tier A/B name an
 * already-known identity and propagate by default (`Flow-Correct.dc.html`'s own example); tier C
 * is recorded unverified and asks for scope explicitly.
 */
@RunWith(RobolectricTestRunner::class)
class CorrectionSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

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
                    everyOverSameVoiceCount = 6,
                    onSearchLexicon = { emptyList() },
                    onApply = { callsign, tier, scope -> applied = Triple(callsign, tier, scope) },
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Correct to KA7LWH", substring = true).performClick()

        assert(applied == Triple("KA7LWH", CorrectionTier.PICK_CANDIDATE, CorrectionScope.EVERY_OVER_SAME_VOICE)) {
            "got $applied"
        }
    }

    @Test
    fun `R_052 searching the lexicon and picking a result applies SEARCH_LEXICON`() {
        var applied: Triple<String, CorrectionTier, CorrectionScope>? = null
        var searched: String? = null
        composeTestRule.setContent {
            OrtTheme {
                CorrectionSheet(
                    currentCallsign = "K7LWH",
                    candidates = emptyList(),
                    everyOverSameVoiceCount = 1,
                    onSearchLexicon = { query ->
                        searched = query
                        listOf(LexiconMatch("K9ZZZ", ituPrefix = "K", ituCountry = "United States", ituIso = "US"))
                    },
                    onApply = { callsign, tier, scope -> applied = Triple(callsign, tier, scope) },
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("A station heard before").performClick()
        composeTestRule.onNodeWithContentDescription("Search the lexicon").performTextInput("K9")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Correct to K9ZZZ", substring = true).performClick()

        assert(searched == "K9")
        assert(applied == Triple("K9ZZZ", CorrectionTier.SEARCH_LEXICON, CorrectionScope.EVERY_OVER_SAME_VOICE))
    }

    @Test
    fun `R_052_R_058 typing a callsign defaults to this-over-only and is recorded FREE_TEXT`() {
        var applied: Triple<String, CorrectionTier, CorrectionScope>? = null
        composeTestRule.setContent {
            OrtTheme {
                CorrectionSheet(
                    currentCallsign = "K7LWH",
                    candidates = emptyList(),
                    everyOverSameVoiceCount = 6,
                    onSearchLexicon = { emptyList() },
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

    @Test
    fun `R_058 the every-over-same-voice option is chooseable and carries the real count`() {
        var applied: Triple<String, CorrectionTier, CorrectionScope>? = null
        composeTestRule.setContent {
            OrtTheme {
                CorrectionSheet(
                    currentCallsign = "K7LWH",
                    candidates = emptyList(),
                    everyOverSameVoiceCount = 6,
                    onSearchLexicon = { emptyList() },
                    onApply = { callsign, tier, scope -> applied = Triple(callsign, tier, scope) },
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Type a callsign").performClick()
        composeTestRule.onNodeWithContentDescription("Typed callsign").performTextInput("N0CALL")
        composeTestRule.onNodeWithText("Every over matched to this voice").performClick()
        composeTestRule.onNodeWithText("Save unverified correction").performClick()

        assert(applied?.third == CorrectionScope.EVERY_OVER_SAME_VOICE) { "got $applied" }
    }
}
