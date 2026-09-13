package org.ort.app.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.audio.FakeTransmissionAudioPlayer
import org.ort.app.ui.audio.TransportPlaybackController
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner

/**
 * C10 (`design/canvas/Transport-Bar.dc.html`). [TransportBarViewState.Live] is exercised already by
 * `LiveBarTest` (this composable just forwards to [LiveBar] unchanged); these tests cover
 * [TransportBarViewState.Playback] (states 4-6) and the mode dispatch itself.
 */
@RunWith(RobolectricTestRunner::class)
class TransportBarTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun playing(
        transmissionId: String = "TX1",
        callsignLabel: String? = "W7NPC",
        isPlaying: Boolean = true,
        positionFraction: Float = 0.33f,
        capturingDotVisible: Boolean = false,
    ) = TransportBarViewState.Playback(
        transmissionId = transmissionId,
        callsignLabel = callsignLabel,
        isPlaying = isPlaying,
        positionFraction = positionFraction,
        elapsedLabel = "0:04",
        totalLabel = "0:12",
        capturingDotVisible = capturingDotVisible,
    )

    @Test
    fun `hidden state renders nothing`() {
        composeTestRule.setContent {
            OrtTheme {
                TransportBar(
                    state = TransportBarViewState.Hidden,
                    actions = TransportBarActions(),
                    modifier = Modifier.testTag("bar"),
                )
            }
        }
        composeTestRule.onNodeWithTag("bar").assertDoesNotExist()
    }

    @Test
    fun `live state delegates to LiveBar and reports a click as the live tap`() {
        var tapped = false
        val live = LiveBarViewState(
            level = listOf(0.4f, 1f, 0.6f, 1f),
            partialText = "and we're clear",
            label = "Live",
            tone = LiveBarTone.NOMINAL,
        )
        composeTestRule.setContent {
            OrtTheme {
                TransportBar(
                    state = TransportBarViewState.Live(live),
                    actions = TransportBarActions(onTapLive = { tapped = true }),
                    modifier = Modifier.testTag("live-tap"),
                )
            }
        }
        composeTestRule.onNodeWithTag("live-bar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("live-tap").performClick()
        assertTrue("expected the live tap action to fire", tapped)
    }

    @Test
    fun `playing state shows the pause glyph, no clear control, and taps open that transmission`() {
        var openedId: String? = null
        composeTestRule.setContent {
            OrtTheme {
                TransportBar(
                    state = playing(isPlaying = true),
                    actions = TransportBarActions(onTapPlaying = { openedId = it }),
                )
            }
        }
        composeTestRule.onNodeWithTag("transport-bar-glyph-pause", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("transport-bar-clear").assertDoesNotExist()
        composeTestRule.onNodeWithTag("transport-bar-callsign").performClick()
        assertEquals("TX1", openedId)
    }

    @Test
    fun `paused state shows the play glyph and a clear control that fires onClear`() {
        var cleared = false
        composeTestRule.setContent {
            OrtTheme {
                TransportBar(
                    state = playing(isPlaying = false),
                    actions = TransportBarActions(onClear = { cleared = true }),
                )
            }
        }
        composeTestRule.onNodeWithTag("transport-bar-glyph-play", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("transport-bar-clear").assertIsDisplayed().performClick()
        assertTrue("expected onClear to fire from the x control", cleared)
    }

    @Test
    fun `the pause-play toggle fires onPlayPauseToggle, not the row tap`() {
        var toggled = false
        var openedId: String? = null
        composeTestRule.setContent {
            OrtTheme {
                TransportBar(
                    state = playing(isPlaying = true),
                    actions = TransportBarActions(
                        onPlayPauseToggle = { toggled = true },
                        onTapPlaying = { openedId = it },
                    ),
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("Pause").performClick()
        assertTrue("expected the toggle action, not the row tap", toggled)
        assertEquals(null, openedId)
    }

    @Test
    fun `the capturing dot is visible only when the state asks for it`() {
        composeTestRule.setContent {
            OrtTheme {
                TransportBar(state = playing(capturingDotVisible = true), actions = TransportBarActions())
            }
        }
        composeTestRule.onNodeWithTag("transport-bar-capturing-dot").assertIsDisplayed()
    }

    @Test
    fun `no capturing dot when the state does not ask for it`() {
        composeTestRule.setContent {
            OrtTheme {
                TransportBar(state = playing(capturingDotVisible = false), actions = TransportBarActions())
            }
        }
        composeTestRule.onNodeWithTag("transport-bar-capturing-dot").assertDoesNotExist()
    }

    @Test
    fun `a missing callsign renders Unknown rather than a blank space`() {
        composeTestRule.setContent {
            OrtTheme {
                TransportBar(state = playing(callsignLabel = null), actions = TransportBarActions())
            }
        }
        composeTestRule.onNodeWithTag("transport-bar-callsign").assertIsDisplayed()
        composeTestRule.onNodeWithText("Unknown").assertIsDisplayed()
    }

    @Test
    fun `scrubbing reports a 0f to 1f fraction of the track width`() {
        var scrubbed: Float? = null
        composeTestRule.setContent {
            OrtTheme {
                TransportBar(
                    state = playing(),
                    actions = TransportBarActions(onScrub = { scrubbed = it }),
                )
            }
        }
        composeTestRule.onNodeWithTag("transport-bar-scrub").performTouchInput { click() }
        assertTrue("expected a scrub callback", scrubbed != null)
        assertTrue("expected a mid-track fraction, got $scrubbed", (scrubbed ?: -1f) in 0f..1f)
    }

    @Test
    fun `formatTransportBarTime renders m colon ss, zero-padded, never locale-formatted`() {
        assertEquals("0:00", formatTransportBarTime(0.0))
        assertEquals("0:04", formatTransportBarTime(4.0))
        assertEquals("0:12", formatTransportBarTime(12.9))
        assertEquals("1:05", formatTransportBarTime(65.0))
        assertEquals("0:00", formatTransportBarTime(-3.0))
    }

    @Test
    fun `the pause-play control and the scrub track each carry a 44dp touch target`() {
        composeTestRule.setContent {
            OrtTheme {
                TransportBar(state = playing(), actions = TransportBarActions())
            }
        }
        composeTestRule.onNodeWithTag("transport-bar-toggle")
            .assertHeightIsAtLeast(44.dp)
            .assertWidthIsAtLeast(44.dp)
        composeTestRule.onNodeWithTag("transport-bar-scrub").assertHeightIsAtLeast(44.dp)
    }

    /**
     * Coordinator follow-up (R-1006's on-device proof): [TransportBarTest]'s own existing "paused
     * state shows the play glyph and a clear control that fires onClear" only proves the `×`
     * invokes whatever `onClear` lambda the test supplies — it says nothing about what happens
     * once that lambda is the *real* production one. This wires `actions.onClear` to a real
     * [TransportPlaybackController] exactly the way `OrtNavHost.kt` does
     * (`onClear = transportPlayback::clear`) and asserts the real, underlying
     * [FakeTransmissionAudioPlayer] actually stopped — not merely that the bar's own callback
     * fired. Discrimination: temporarily changed [TransportPlaybackController.clear] to update its
     * own fields without calling `delegate.stop()`, watched this fail (`stopCallCount` stayed `0`),
     * reverted.
     */
    @Test
    fun `x in Paused really stops the underlying player, not merely the bar's own callback`() = runTest {
        val player = FakeTransmissionAudioPlayer()
        val controller = TransportPlaybackController(player)
        controller.play("TX1")
        controller.pause()

        composeTestRule.setContent {
            OrtTheme {
                TransportBar(
                    state = playing(isPlaying = controller.isPlayingState),
                    actions = TransportBarActions(onClear = controller::clear),
                )
            }
        }

        composeTestRule.onNodeWithTag("transport-bar-clear").performClick()

        assertTrue(
            "expected the real player to have stopped, got stopCallCount=${player.stopCallCount}",
            player.stopCallCount >= 1,
        )
        assertEquals(null, controller.loadedTransmissionId)
    }
}
