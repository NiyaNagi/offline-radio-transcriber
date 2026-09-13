package org.ort.app.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.audio.FakeTransmissionAudioPlayer
import org.ort.app.ui.data.DetailViewStateMapper
import org.ort.app.ui.data.InspectionViewState
import org.ort.app.ui.data.TransmissionDetailViewState
import org.ort.app.ui.theme.OrtTheme
import org.ort.core.Attribution
import org.robolectric.RobolectricTestRunner

/**
 * R-1006 (register): `stop()` exists and is correct
 * (`RealTransmissionAudioPlayer.kt:69-76` — pause/resume likewise), but nothing ever called it.
 * Leaving the screen mid-playback, or opening a different over from the same screen, left the
 * `AudioTrack` running with no control left on screen able to reach it — the operator's own
 * report ("no way for me to stop it... had to listen to it going through the whole thing"). Split
 * out of `TransmissionDetailScreenTest.kt` for detekt's `LargeClass` (the same split that file's
 * own R-242/R-153 doc comments already document for `RejectedDetailScreenTest.kt`/
 * `PassFailureDetailScreenTest.kt`).
 *
 * **C10 (`design/canvas/Transport-Bar.dc.html`) reverses this row's own original fix.** The
 * artboard's own rule: "the bar owns playback... leaving a screen never stops the audio; × or the
 * end of the over does." The `DisposableEffect(detail.id) { onDispose { player.stop() } }` this
 * row originally added is exactly what "leaving a screen" meant here — a back navigation, or (as
 * the second test below covered) a drill-in reusing the same composable slot for a different over
 * — so both of that fix's own tests are replaced below with the opposite assertion: playback
 * survives both kinds of navigation. What still stops it is unchanged and still tested further
 * down this file: reaching the recorded end of the over, and (in `TransportBarTest.kt`, C10's own
 * suite) the bar's own `×`. `TransportPlaybackController` (`ui/audio/`) is what now owns the
 * stop-on-leave decision instead of this screen — see that class's own doc comment.
 */
@RunWith(RobolectricTestRunner::class)
class PlaybackControlDetailScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun detail(attribution: Attribution = Attribution.inferred("K7LWH", 0.82), hasAudio: Boolean = true) =
        TransmissionDetailViewState(
            id = "TX1",
            timeLabel = "02:14:22",
            frequencyLabel = "145.230",
            durationLabel = "4.2s",
            signalLabel = "S5",
            attribution = attribution,
            transcriptText = "roger that, good copy on the repeater this morning",
            revisionHistory = emptyList(),
            hasAudio = hasAudio,
            inspection = InspectionViewState.EMPTY,
        )

    private fun state(detail: TransmissionDetailViewState = detail()) = DetailViewStateMapper.from(detail)

    @Test
    fun `R_1006_leaving_the_screen_mid_playback_does_not_stop_the_players_audio`() {
        val player = FakeTransmissionAudioPlayer()
        var showScreen by mutableStateOf(true)
        composeTestRule.setContent {
            OrtTheme {
                if (showScreen) {
                    TransmissionDetailScreen(state = state(), player = player)
                }
            }
        }
        composeTestRule.onNodeWithContentDescription("Play retained audio").performClick()
        composeTestRule.waitForIdle()
        assertEquals("setup: expected play(\"TX1\")", listOf("TX1"), player.playCalls)

        showScreen = false
        composeTestRule.waitForIdle()

        assertEquals(
            "C10: the bar owns playback now — leaving this screen must not stop the audio; " +
                "only the bar's x or the end of the over does",
            0,
            player.stopCallCount,
        )
    }

    @Test
    fun `R_1006_showing_a_different_over_without_playing_it_does_not_stop_the_first_ones_playback`() {
        val player = FakeTransmissionAudioPlayer()
        var current by mutableStateOf(state())
        composeTestRule.setContent {
            OrtTheme { TransmissionDetailScreen(state = current, player = player) }
        }
        composeTestRule.onNodeWithContentDescription("Play retained audio").performClick()
        composeTestRule.waitForIdle()
        assertEquals("setup: expected play(\"TX1\")", listOf("TX1"), player.playCalls)

        // Merely showing a different over's detail — never tapping its own play control — is the
        // same "leaving a screen" shape C10 reverses: the bar, not this screen, decides whether
        // TX1 keeps playing.
        current = state(detail().copy(id = "TX2"))
        composeTestRule.waitForIdle()

        assertEquals(
            "C10: showing a different over's detail must not stop the first one's playback " +
                "unless its own play control is actually tapped",
            0,
            player.stopCallCount,
        )
    }

    /**
     * Android's own `AudioTrack` never flips `playState` away from `PLAYSTATE_PLAYING` just
     * because a static buffer finished playing — only an explicit `pause()`/`stop()` call changes
     * it (there is no completion callback wired here). Left unhandled, `isPlaying()` would report
     * `true` forever after the over finished, so the control would keep reading "Pause" and the
     * operator would have no visible way back to "Play" even though nothing is actually
     * happening — the same "UI lies about state forever" this register row calls out.
     * [FakeTransmissionAudioPlayer.seekToFraction] models exactly the split that makes this real:
     * it moves the position without touching `isPlaying()`, the same way the real position and
     * the real `playState` are two independent facts on a real `AudioTrack`.
     */
    @Test
    fun `R_1006_reaching_the_recorded_end_reverts_the_control_to_play_rather_than_lying_forever`() {
        composeTestRule.mainClock.autoAdvance = false
        val player = FakeTransmissionAudioPlayer()
        composeTestRule.setContent {
            OrtTheme { TransmissionDetailScreen(state = state(), player = player) }
        }
        composeTestRule.onNodeWithContentDescription("Play retained audio").performClick()
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Pause").assertExists()

        player.seekToFraction(1f)
        composeTestRule.mainClock.advanceTimeBy(500)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Play retained audio").assertExists()
        composeTestRule.onNodeWithContentDescription("Pause").assertDoesNotExist()
    }
}
