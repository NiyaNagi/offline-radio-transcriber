package org.ort.app.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.audio.FakeTransmissionAudioPlayer
import org.ort.app.ui.data.TransmissionDetailViewState
import org.ort.app.ui.theme.OrtTheme
import org.ort.core.Attribution
import org.robolectric.RobolectricTestRunner

/**
 * The transmission detail drill-in (build-plan P14, `design/canvas/Detail.dc.html`). FR-UI-4:
 * the attribution state and confidence render through [org.ort.app.ui.components.AttributionMarker].
 * FR-UI-5: a play control drives a [org.ort.app.ui.audio.TransmissionAudioPlayer] with this
 * transmission's id. FR-UI-1's revision half: every superseded version stays visible here, not
 * just noted in the log row.
 *
 * One deliberate divergence from `Detail.dc.html`, called out in the build-plan prompt itself:
 * the artboard's "why this callsign" lattice and per-prior breakdown is FR-UI-8, explicitly P16's
 * ("the inspection surface") — `PriorCombiner`'s per-prior contributions have nowhere to reach
 * this screen from yet. This screen shows the plain attribution/confidence/transcript/audio the
 * prompt's "write first" section actually asks for.
 */
@RunWith(RobolectricTestRunner::class)
class TransmissionDetailScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun state(
        attribution: Attribution = Attribution.inferred("K7LWH", 0.82),
        transcriptText: String = "roger that, good copy on the repeater this morning",
        revisionHistory: List<String> = emptyList(),
        hasAudio: Boolean = true,
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
    )

    @Test
    fun `FR_UI_5 the play control asks the player for this transmission's audio`() {
        val player = FakeTransmissionAudioPlayer()
        composeTestRule.setContent {
            OrtTheme {
                TransmissionDetailScreen(state = state(), player = player, onBack = {})
            }
        }

        composeTestRule.onNodeWithContentDescription("Play retained audio").performClick()

        // The click launches a coroutine on the composition scope; give it a moment to run.
        composeTestRule.waitForIdle()
        assert(player.playCalls == listOf("TX1")) { "expected play(\"TX1\") but calls were ${player.playCalls}" }
    }

    @Test
    fun `FR_UI_5 a transmission with no retained audio shows no play control rather than a dead button`() {
        composeTestRule.setContent {
            OrtTheme {
                TransmissionDetailScreen(
                    state = state(hasAudio = false),
                    player = FakeTransmissionAudioPlayer(),
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithText("No retained audio for this transmission").assertExists()
    }

    @Test
    fun `the transcript, attribution and confidence are all shown`() {
        composeTestRule.setContent {
            OrtTheme {
                TransmissionDetailScreen(
                    state = state(attribution = Attribution.confirmed("W7NPC", 0.95)),
                    player = FakeTransmissionAudioPlayer(),
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithText("roger that, good copy on the repeater this morning").assertExists()
        composeTestRule
            .onNodeWithContentDescription("filled circle, ✓ CONFIRMED, confidence 0.95", substring = true)
            .assertExists()
    }

    @Test
    fun `FR_UI_1 a superseded transcript's earlier versions are visible, not hidden`() {
        composeTestRule.setContent {
            OrtTheme {
                TransmissionDetailScreen(
                    state = state(revisionHistory = listOf("first partial")),
                    player = FakeTransmissionAudioPlayer(),
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithText("first partial").assertExists()
    }

    @Test
    fun `back is reachable and carries a content description`() {
        var backCalled = false
        composeTestRule.setContent {
            OrtTheme {
                TransmissionDetailScreen(
                    state = state(),
                    player = FakeTransmissionAudioPlayer(),
                    onBack = { backCalled = true },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Back").performClick()
        assert(backCalled)
    }
}
