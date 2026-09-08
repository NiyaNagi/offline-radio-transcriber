package org.ort.app.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.AffectedOverViewState
import org.ort.app.ui.data.PriorAdjustmentOutcome
import org.ort.app.ui.data.PropagationOutcome
import org.ort.app.ui.data.VoiceprintRebindOutcome
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner

/**
 * R-052, `Detail-Propagated.dc.html`: the counts line reads from the real
 * [PropagationOutcome.voiceprintRebind]/`.priorAdjustments` [org.ort.app.ui.data.CorrectionPolling]
 * now writes through `StationIdentityDao`, not a fixed placeholder.
 */
@RunWith(RobolectricTestRunner::class)
class PropagatedScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun outcome(
        voiceprintRebind: VoiceprintRebindOutcome? = null,
        priorAdjustments: List<PriorAdjustmentOutcome> = emptyList(),
    ) = PropagationOutcome(
        newCallsign = "KA7LWH",
        previousCallsign = "K7LWH",
        overCount = 6,
        voiceprintRebind = voiceprintRebind,
        priorAdjustments = priorAdjustments,
        deletedCount = 0,
        affected = listOf(
            AffectedOverViewState(
                transmissionId = "TX1",
                timeLabel = "02:14:22",
                transcriptExcerpt = "roger that",
                oldCallsign = "K7LWH",
                newCallsign = "KA7LWH",
            ),
        ),
    )

    @Test
    fun `R_052 a verified propagation names the real priors it adjusted`() {
        composeTestRule.setContent {
            OrtTheme {
                PropagatedScreen(
                    outcome = outcome(
                        voiceprintRebind = VoiceprintRebindOutcome(
                            voiceprintId = "V1",
                            previousStationId = "K7LWH",
                            previousBindingConfidence = 0.6,
                            previousBindingSource = null,
                            newStationId = "KA7LWH",
                        ),
                        priorAdjustments = listOf(
                            PriorAdjustmentOutcome("KA7LWH", "on_this_repeater", 0.0, 0.15),
                            PriorAdjustmentOutcome("KA7LWH", "recent_corrections", 0.0, 0.15),
                        ),
                    ),
                    onUndoAll = {},
                    onBackToOver = {},
                    onDone = {},
                )
            }
        }

        composeTestRule.onNodeWithText("2 priors updated — on this repeater and recent corrections").assertExists()
        composeTestRule.onNodeWithText("1 voiceprint now belongs to KA7LWH").assertExists()
    }

    @Test
    fun `R_052 an unverified correction shows zero, real counts, never a fabricated one`() {
        composeTestRule.setContent {
            OrtTheme {
                PropagatedScreen(outcome = outcome(), onUndoAll = {}, onBackToOver = {}, onDone = {})
            }
        }

        composeTestRule.onNodeWithText("0 priors updated").assertExists()
        composeTestRule.onNodeWithText("0 records deleted — every earlier attribution is kept").assertExists()
    }

    /**
     * R-190 (design): a genuinely zero voiceprint reassignment must not phrase it as if the
     * voiceprint went somewhere ("0 voiceprint now belongs to KA7LWH" reads as a claim it did,
     * with a zero standing in front of it) — no station is named when none received it.
     */
    @Test
    fun `R_190 a zero voiceprint reassignment never names a station it did not move to`() {
        composeTestRule.setContent {
            OrtTheme {
                PropagatedScreen(outcome = outcome(), onUndoAll = {}, onBackToOver = {}, onDone = {})
            }
        }

        composeTestRule.onNodeWithText("0 voiceprint unchanged").assertExists()
        composeTestRule.onNodeWithText("0 voiceprint now belongs to KA7LWH").assertDoesNotExist()
    }

    /** R-190 (design): the shared plural helper — "1 over"/"6 overs", never "1 overs". */
    @Test
    fun `R_190 over counts use the shared plural helper`() {
        composeTestRule.setContent {
            OrtTheme {
                PropagatedScreen(
                    outcome = outcome().copy(overCount = 1),
                    onUndoAll = {},
                    onBackToOver = {},
                    onDone = {},
                )
            }
        }

        composeTestRule.onNodeWithText("1 over re-attributed").assertExists()
        composeTestRule.onNodeWithText("1 overs re-attributed").assertDoesNotExist()
    }

    /** R-191 (design): the subtitle names the real correction method and time, not just the old callsign. */
    @Test
    fun `R_191 the subtitle names the real correction method and time`() {
        composeTestRule.setContent {
            OrtTheme {
                PropagatedScreen(
                    outcome = outcome().copy(
                        tier = org.ort.app.ui.data.CorrectionTier.PICK_CANDIDATE,
                        correctedAtMillis = 6 * 3_600_000L + 20 * 60_000L,
                    ),
                    onUndoAll = {},
                    onBackToOver = {},
                    onDone = {},
                )
            }
        }

        composeTestRule
            .onNodeWithText(
                "Was K7LWH · picked from the resolver's candidates · 06:20",
                substring = true,
            )
            .assertExists()
    }

    /** R-191 (design): each affected row carries a marker and a `CORRECTED` badge. */
    @Test
    fun `R_191 each affected row carries a corrected badge`() {
        composeTestRule.setContent {
            OrtTheme {
                PropagatedScreen(outcome = outcome(), onUndoAll = {}, onBackToOver = {}, onDone = {})
            }
        }

        composeTestRule.onNodeWithText("CORRECTED").assertExists()
    }
}
