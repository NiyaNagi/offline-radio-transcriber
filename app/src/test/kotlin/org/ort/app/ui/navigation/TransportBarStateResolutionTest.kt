package org.ort.app.ui.navigation

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.app.ui.audio.FakeTransmissionAudioPlayer
import org.ort.app.ui.audio.TransportPlaybackController
import org.ort.app.ui.components.LiveBarTone
import org.ort.app.ui.components.LiveBarViewState
import org.ort.app.ui.components.TransportBarViewState

/**
 * C10 (`design/canvas/Transport-Bar.dc.html`): [resolveTransportBarState] is [OrtNavHost.kt]'s own
 * decision of which bar mode to show — pulled out to a plain function (this file's own doc
 * comment) so it is testable without composing the whole host, the same discipline `LiveBar.kt`'s
 * own `meterColorFor`/`liveBarLabelColor` already established for tone resolution.
 */
class TransportBarStateResolutionTest {

    private val live = LiveBarViewState(
        level = listOf(0.4f, 1f, 0.6f, 1f),
        partialText = "and we're clear",
        label = "Live",
        tone = LiveBarTone.NOMINAL,
    )

    private fun resolve(
        controller: TransportPlaybackController,
        liveBar: LiveBarViewState? = null,
        embedsOwnLiveBar: Boolean = false,
        openTransmissionId: String? = null,
    ): TransportBarViewState = resolveTransportBarState(
        transportPlayback = controller,
        liveBar = liveBar,
        embedsOwnLiveBar = embedsOwnLiveBar,
        openTransmissionId = openTransmissionId,
    )

    @Test
    fun `nothing loaded and no live session hides the bar`() {
        val controller = TransportPlaybackController(FakeTransmissionAudioPlayer())

        val state = resolve(controller)

        assertEquals(TransportBarViewState.Hidden, state)
    }

    @Test
    fun `a live session shows the live bar when nothing is loaded for playback`() {
        val controller = TransportPlaybackController(FakeTransmissionAudioPlayer())

        val state = resolve(controller, liveBar = live)

        assertEquals(TransportBarViewState.Live(live), state)
    }

    @Test
    fun `IA-2 - Now and Capture embed their own live bar, so the host hides its copy`() {
        val controller = TransportPlaybackController(FakeTransmissionAudioPlayer())

        val state = resolve(controller, liveBar = live, embedsOwnLiveBar = true)

        assertEquals(TransportBarViewState.Hidden, state)
    }

    @Test
    fun `playback wins over live - one mode at a time`() = runTest {
        val controller = TransportPlaybackController(FakeTransmissionAudioPlayer())
        controller.play("TX1")

        val state = resolve(controller, liveBar = live)

        assertTrue(state is TransportBarViewState.Playback, "expected Playback, got $state")
        assertEquals("TX1", (state as TransportBarViewState.Playback).transmissionId)
    }

    @Test
    fun `playback shows even on Now or Capture, unlike live - it is a different mode`() = runTest {
        val controller = TransportPlaybackController(FakeTransmissionAudioPlayer())
        controller.play("TX1")

        val state = resolve(controller, liveBar = live, embedsOwnLiveBar = true)

        val message = "expected Playback even with embedsOwnLiveBar=true, got $state"
        assertTrue(state is TransportBarViewState.Playback, message)
    }

    @Test
    fun `the playing transmission's own detail screen hides the bar that would just reopen it`() = runTest {
        val controller = TransportPlaybackController(FakeTransmissionAudioPlayer())
        controller.play("TX1")

        val state = resolve(controller, openTransmissionId = "TX1")

        assertEquals(TransportBarViewState.Hidden, state)
    }

    @Test
    fun `a different open transmission still shows the playback bar`() = runTest {
        val controller = TransportPlaybackController(FakeTransmissionAudioPlayer())
        controller.play("TX1")

        val state = resolve(controller, openTransmissionId = "TX2")

        assertTrue(state is TransportBarViewState.Playback, "expected Playback, got $state")
    }

    @Test
    fun `carries callsign, position and the capturing dot through to the view state`() = runTest {
        val controller = TransportPlaybackController(FakeTransmissionAudioPlayer())
        controller.play("TX1")
        controller.setNowPlayingMeta("TX1", callsignLabel = "W7NPC", durationSeconds = 12.0)
        controller.seekToFraction(0.5f)
        controller.capturingNow = true

        val state = resolve(controller) as TransportBarViewState.Playback

        assertEquals("W7NPC", state.callsignLabel)
        assertEquals(0.5f, state.positionFraction)
        assertEquals("0:06", state.elapsedLabel)
        assertEquals("0:12", state.totalLabel)
        assertTrue(state.capturingDotVisible, "expected the capturing dot while playing during a live session")
    }

    @Test
    fun `no capturing dot when paused, even during a live session`() = runTest {
        val controller = TransportPlaybackController(FakeTransmissionAudioPlayer())
        controller.play("TX1")
        controller.pause()
        controller.capturingNow = true

        val state = resolve(controller) as TransportBarViewState.Playback

        assertTrue(!state.capturingDotVisible, "expected no capturing dot while paused")
    }

    /**
     * Coordinator follow-up (R-1006's on-device proof): "a finished over clears itself" (the
     * artboard's own rule) means the *bar*, not only the controller's own fields, reverts once
     * playback reaches its recorded end — [TransportPlaybackControllerTest]'s own
     * "poll reaching the recorded end clears playback" proves [TransportPlaybackController
     * .loadedTransmissionId] goes `null`; this proves [resolveTransportBarState] then actually
     * shows `Live` again once a real session is running. Discrimination: temporarily made
     * [TransportPlaybackController.poll] skip the `stop()` call on end-of-track (report the
     * position without clearing), watched this fail (`state` stayed `Playback`), reverted.
     */
    @Test
    fun `a finished over's poll tick clears the bar back to Live when a session is running`() = runTest {
        val player = FakeTransmissionAudioPlayer()
        val controller = TransportPlaybackController(player)
        controller.play("TX1")
        player.seekToFraction(1f)

        controller.poll()
        val state = resolve(controller, liveBar = live)

        assertEquals(TransportBarViewState.Live(live), state)
    }

    /** Same as above, but with no live session at all — the bar goes fully `Hidden`, never a
     * stale `Playback` for an over that has finished. */
    @Test
    fun `a finished over's poll tick clears the bar back to Hidden when no session is running`() = runTest {
        val player = FakeTransmissionAudioPlayer()
        val controller = TransportPlaybackController(player)
        controller.play("TX1")
        player.seekToFraction(1f)

        controller.poll()
        val state = resolve(controller)

        assertEquals(TransportBarViewState.Hidden, state)
    }
}
