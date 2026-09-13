package org.ort.app.ui.audio

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * C10 (`design/canvas/Transport-Bar.dc.html`): [TransportPlaybackController] is the shared state
 * the transport bar and `TransmissionDetailScreen`'s `PlaybackSection` both read, so navigating
 * away never stops playback — only [TransportPlaybackController.clear] or the over reaching its
 * own recorded end does (the R-1006 reversal). These tests exercise the controller in isolation,
 * independent of any Compose tree.
 */
class TransportPlaybackControllerTest {

    @Test
    fun `play success loads the transmission and reports playing`() = runTest {
        val controller = TransportPlaybackController(FakeTransmissionAudioPlayer())

        val outcome = controller.play("TX1")

        assertEquals(PlaybackOutcome.Played, outcome)
        assertEquals("TX1", controller.loadedTransmissionId)
        assertTrue(controller.isPlayingState)
        assertEquals(0f, controller.positionFractionState)
    }

    @Test
    fun `play failure does not load a transmission`() = runTest {
        val controller = TransportPlaybackController(
            FakeTransmissionAudioPlayer(script = mapOf("TX1" to PlaybackOutcome.Unavailable("no audio"))),
        )

        val outcome = controller.play("TX1")

        assertTrue(outcome is PlaybackOutcome.Unavailable)
        assertNull(controller.loadedTransmissionId)
        assertFalse(controller.isPlayingState)
    }

    @Test
    fun `setNowPlayingMeta after a successful play carries callsign and duration to the bar`() = runTest {
        val controller = TransportPlaybackController(FakeTransmissionAudioPlayer())
        controller.play("TX1")

        controller.setNowPlayingMeta("TX1", callsignLabel = "W7NPC", durationSeconds = 4.2)

        assertEquals("W7NPC", controller.callsignLabel)
        assertEquals(4.2, controller.durationSeconds)
    }

    @Test
    fun `setNowPlayingMeta for a stale transmission id is ignored`() = runTest {
        val controller = TransportPlaybackController(FakeTransmissionAudioPlayer())
        controller.play("TX1")
        controller.clear()

        controller.setNowPlayingMeta("TX1", callsignLabel = "W7NPC", durationSeconds = 4.2)

        assertNull(controller.callsignLabel)
    }

    @Test
    fun `pause and resume toggle isPlayingState without clearing the loaded transmission`() = runTest {
        val controller = TransportPlaybackController(FakeTransmissionAudioPlayer())
        controller.play("TX1")

        controller.pause()
        assertFalse(controller.isPlayingState)
        assertEquals("TX1", controller.loadedTransmissionId)

        controller.resume()
        assertTrue(controller.isPlayingState)
        assertEquals("TX1", controller.loadedTransmissionId)
    }

    @Test
    fun `seekToFraction clamps and updates positionFractionState immediately`() = runTest {
        val controller = TransportPlaybackController(FakeTransmissionAudioPlayer())
        controller.play("TX1")

        controller.seekToFraction(1.4f)

        assertEquals(1f, controller.positionFractionState)
    }

    @Test
    fun `clear (the bar's x) stops the underlying player and forgets everything about the over`() = runTest {
        val player = FakeTransmissionAudioPlayer()
        val controller = TransportPlaybackController(player)
        controller.play("TX1")
        controller.setNowPlayingMeta("TX1", "W7NPC", 4.2)

        controller.clear()

        assertEquals(1, player.stopCallCount)
        assertNull(controller.loadedTransmissionId)
        assertNull(controller.callsignLabel)
        assertEquals(0.0, controller.durationSeconds)
        assertFalse(controller.isPlayingState)
        assertEquals(0f, controller.positionFractionState)
    }

    @Test
    fun `poll refreshes position while playing`() = runTest {
        val player = FakeTransmissionAudioPlayer()
        val controller = TransportPlaybackController(player)
        controller.play("TX1")
        player.seekToFraction(0.5f)

        controller.poll()

        assertEquals(0.5f, controller.positionFractionState)
        assertTrue(controller.isPlayingState)
    }

    @Test
    fun `poll reaching the recorded end clears playback rather than reporting playing forever`() = runTest {
        val player = FakeTransmissionAudioPlayer()
        val controller = TransportPlaybackController(player)
        controller.play("TX1")
        player.seekToFraction(1f)

        controller.poll()

        assertNull(controller.loadedTransmissionId)
        assertFalse(controller.isPlayingState)
        assertEquals(1, player.stopCallCount)
    }

    @Test
    fun `poll is a no-op once paused`() = runTest {
        val player = FakeTransmissionAudioPlayer()
        val controller = TransportPlaybackController(player)
        controller.play("TX1")
        controller.pause()
        player.seekToFraction(0.9f)

        controller.poll()

        // Paused: the bar must not silently advance the scrub position behind the operator's back.
        assertEquals(0f, controller.positionFractionState)
    }

    @Test
    fun `a second play stops whatever was already loaded, one mode at a time`() = runTest {
        val player = FakeTransmissionAudioPlayer()
        val controller = TransportPlaybackController(player)
        controller.play("TX1")
        controller.setNowPlayingMeta("TX1", "W7NPC", 4.2)

        controller.play("TX2")

        assertEquals("TX2", controller.loadedTransmissionId)
        assertNull(controller.callsignLabel)
        assertEquals(0f, controller.positionFractionState)
    }
}
