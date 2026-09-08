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
        composeTestRule.onNodeWithText("0 voiceprint now belongs to KA7LWH").assertExists()
        composeTestRule.onNodeWithText("0 records deleted — every earlier attribution is kept").assertExists()
    }
}
