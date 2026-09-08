package org.ort.app.ui.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * R-181, `Detail-Playback.dc.html`: the pure bucketing arithmetic behind
 * [TransmissionAudioPlayer.waveformSummary]'s real implementation — no Robolectric, no database,
 * no codec, exactly [WaveformSummaryComputer]'s own doc comment's point in building it this way.
 */
class WaveformSummaryComputerTest {

    /** The exact same 440 Hz tone shape `ScenarioFixtures.writeAudioFixture` writes for every
     * scenario that needs real, decodable retained audio, at float amplitude instead of PCM16. */
    private fun tone(sampleCount: Int, hz: Double = 440.0, sampleRateHz: Int = 16_000): FloatArray =
        FloatArray(sampleCount) { i -> (0.6 * sin(2.0 * PI * hz * i / sampleRateHz)).toFloat() }

    @Test
    fun R_181_a_synthetic_tone_yields_non_uniform_buckets() {
        // One second of a 440 Hz tone at 16 kHz — many full cycles per bucket at 96 buckets, but
        // the tone's own envelope (ramping from index 0) and window-boundary phase still produce
        // real, unequal peak reads across buckets, not a flat line.
        val samples = tone(sampleCount = 16_000)

        val summary = WaveformSummaryComputer.summarize(samples)

        requireNotNull(summary)
        assertEquals(WaveformSummaryComputer.BUCKET_COUNT, summary.bars.size)
        val distinctHeights = summary.bars.map { it.heightFraction }.distinct()
        assertTrue(
            "expected non-uniform bucket heights, got only $distinctHeights",
            distinctHeights.size > 1,
        )
        // The loudest bucket always renders at full height — the real normalisation contract.
        assertEquals(1f, summary.bars.maxOf { it.heightFraction })
    }

    @Test
    fun R_181_every_bar_height_is_clamped_into_the_real_visible_range() {
        val samples = tone(sampleCount = 8_000)

        val summary = WaveformSummaryComputer.summarize(samples)

        requireNotNull(summary)
        summary.bars.forEach { bar ->
            assertTrue("heightFraction ${bar.heightFraction} out of 0..1", bar.heightFraction in 0f..1f)
        }
    }

    @Test
    fun R_181_total_silence_is_a_real_flat_shape_not_a_null_or_a_crash() {
        val silence = FloatArray(4_000)

        val summary = WaveformSummaryComputer.summarize(silence)

        requireNotNull(summary)
        assertEquals(WaveformSummaryComputer.BUCKET_COUNT, summary.bars.size)
        assertTrue(summary.bars.all { !it.isSpeech })
    }

    @Test
    fun R_181_an_empty_sample_buffer_yields_null_without_throwing() {
        assertNull(WaveformSummaryComputer.summarize(FloatArray(0)))
    }
}
