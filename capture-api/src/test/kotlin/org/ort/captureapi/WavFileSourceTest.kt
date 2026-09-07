package org.ort.captureapi

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.testing.Requirement
import kotlin.math.PI
import kotlin.math.sin

class WavFileSourceTest {

    private fun tone(rate: Int, seconds: Double, hz: Double): ShortArray {
        val n = (rate * seconds).toInt()
        return ShortArray(n) { i -> (sin(2 * PI * hz * i / rate) * 0.4 * Short.MAX_VALUE).toInt().toShort() }
    }

    private fun frames(events: List<CaptureEvent>): ShortArray {
        val out = ArrayList<Short>()
        for (e in events) if (e is CaptureEvent.Frames) e.pcm.forEach { out.add(it) }
        return out.toShortArray()
    }

    @Test
    @Requirement("AC-89", "FR-TST-1")
    fun `AC_89 real-time and as-fast-as-possible replay produce identical results`() = runTest {
        val wav = WavAudio(tone(16_000, 1.0, hz = 300.0), 16_000)
        val fast = WavFileSource(wav, realTime = false).start().toList()
        val realTime = WavFileSource(wav, realTime = true).start().toList()

        assertEquals(fast, realTime)
        assertTrue(fast.last() is CaptureEvent.EndOfStream)
        assertTrue(frames(fast).contentEquals(wav.samples))
    }

    @Test
    @Requirement("AC-89")
    fun `AC_89 a WAV replays faster than real time`() {
        val wav = WavAudio(tone(16_000, 3.0, hz = 220.0), 16_000)
        val elapsed = kotlin.system.measureTimeMillis {
            runBlocking { WavFileSource(wav, realTime = false).start().toList() }
        }
        assertTrue(elapsed < 1_000, "3 s of audio replayed in ${elapsed}ms")
    }

    @Test
    @Requirement("AC-89", "AC-97")
    fun `AC_89 framePositions are contiguous and cover the whole output`() = runTest {
        val wav = WavAudio(tone(48_000, 0.5, hz = 440.0), 48_000)
        val src = WavFileSource(wav, frameSize = 512)
        val events = src.start().toList()

        var expected = 0L
        for (e in events) {
            if (e is CaptureEvent.Frames) {
                assertEquals(expected, e.framePosition)
                expected += e.pcm.size
            }
        }
        assertEquals(src.output.size.toLong(), expected)
        assertEquals(16_000, src.outputFormat.sampleRate)
        assertEquals(48_000, src.deviceFormat.sampleRate)
        assertEquals(48_000, src.resamplerIdentity?.inputRate)
    }

    @Test
    @Requirement("AC-97")
    fun `a source already at the output rate records no resampler and copies through`() = runTest {
        val wav = WavAudio(tone(16_000, 0.2, hz = 100.0), 16_000)
        val src = WavFileSource(wav)
        assertNull(src.resamplerIdentity)
        val events = src.start().toList()
        assertTrue(frames(events).contentEquals(wav.samples))
    }

    @Test
    fun `cancelling the collector halts replay before end of stream`() = runTest {
        val src = WavFileSource(WavAudio(tone(16_000, 2.0, hz = 100.0), 16_000))
        val first = src.start().take(2).toList()
        assertEquals(2, first.size)
        assertTrue(first.none { it is CaptureEvent.EndOfStream })
    }

    @Test
    @Requirement("AC-89")
    fun `WAV round-trips through the encoder`() {
        val wav = WavAudio(tone(16_000, 0.1, hz = 500.0), 16_000)
        assertEquals(wav, WavIo.read(WavIo.encode(wav)))
    }
}
