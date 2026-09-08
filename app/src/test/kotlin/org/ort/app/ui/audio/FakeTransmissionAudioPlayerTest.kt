package org.ort.app.ui.audio

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * ui-conformance WP6 (R-054), `Detail-Playback.dc.html`: idle / playing (position, scrub,
 * 1x/0.75x/0.5x) / no-audio / unavailable. [TransmissionAudioPlayer]'s behavioural fake (constitution
 * II: ships in the same change as the interface extension) proves the position/rate/scrub contract
 * a Compose test can drive without a real `AudioTrack`.
 */
class FakeTransmissionAudioPlayerTest {

    @Test
    fun `R_054 playing sets isPlaying and resets position to the start`(): Unit = runTest {
        val player = FakeTransmissionAudioPlayer()

        player.play("TX1")

        assertTrue(player.isPlaying())
        assertEquals(0f, player.positionFraction())
    }

    @Test
    fun `R_054 pause and resume toggle isPlaying without losing position`(): Unit = runTest {
        val player = FakeTransmissionAudioPlayer()
        player.play("TX1")
        player.seekToFraction(0.4f)

        player.pause()
        assertFalse(player.isPlaying())
        assertEquals(0.4f, player.positionFraction())

        player.resume()
        assertTrue(player.isPlaying())
        assertEquals(0.4f, player.positionFraction())
    }

    @Test
    fun `R_054 seekToFraction clamps to 0 and 1`(): Unit = runTest {
        val player = FakeTransmissionAudioPlayer()

        player.seekToFraction(-0.5f)
        assertEquals(0f, player.positionFraction())

        player.seekToFraction(1.5f)
        assertEquals(1f, player.positionFraction())
    }

    @Test
    fun `R_054 seekToFraction records every call so a Compose test can assert on it`(): Unit = runTest {
        val player = FakeTransmissionAudioPlayer()

        player.seekToFraction(0.3f)
        player.seekToFraction(0.6f)

        assertEquals(listOf(0.3f, 0.6f), player.seekCalls)
    }

    @Test
    fun `R_054 setRate is readable back, defaulting to normal speed`(): Unit = runTest {
        val player = FakeTransmissionAudioPlayer()
        assertEquals(PlaybackRate.NORMAL, player.rate)

        player.setRate(PlaybackRate.HALF)

        assertEquals(PlaybackRate.HALF, player.rate)
    }

    @Test
    fun `R_054 an unavailable outcome never claims to be playing`(): Unit = runTest {
        val player = FakeTransmissionAudioPlayer(mapOf("TX1" to PlaybackOutcome.Unavailable("no retained audio")))

        player.play("TX1")

        assertFalse(player.isPlaying())
    }

    @Test
    fun `R_054 stop resets both position and playing state`(): Unit = runTest {
        val player = FakeTransmissionAudioPlayer()
        player.play("TX1")
        player.seekToFraction(0.7f)

        player.stop()

        assertFalse(player.isPlaying())
        assertEquals(0f, player.positionFraction())
    }
}
