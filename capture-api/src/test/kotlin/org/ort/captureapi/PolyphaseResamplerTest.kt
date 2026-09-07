package org.ort.captureapi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.testing.Requirement
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class PolyphaseResamplerTest {

    private fun tone(rate: Int, seconds: Double, hz: Double, amp: Double = 0.4): ShortArray {
        val n = (rate * seconds).toInt()
        return ShortArray(n) { i -> (sin(2 * PI * hz * i / rate) * amp * Short.MAX_VALUE).toInt().toShort() }
    }

    @Test
    @Requirement("AC-97", "FR-CAP-2a")
    fun `AC_97 a 48 kHz input resamples deterministically to 16 kHz`() {
        val input = tone(48_000, 0.5, hz = 440.0)
        val a = PolyphaseResampler(48_000, 16_000).resample(input)
        val b = PolyphaseResampler(48_000, 16_000).resample(input)

        assertTrue(a.contentEquals(b), "two independent runs must be byte-identical")
        // 3:1 decimation — output length is one third (± the final partial sample)
        assertEquals(input.size / 3, a.size)
    }

    @Test
    @Requirement("AC-97", "FR-CAP-2a")
    fun `AC_97 the resampler identity is recorded and pins the coefficient table`() {
        val id = PolyphaseResampler(48_000, 16_000).identity
        assertEquals("windowed-sinc-polyphase-q30", id.algorithm)
        assertEquals(1, id.interpolation)
        assertEquals(3, id.decimation)
        assertEquals(64, id.coefficientHash.length) // hex SHA-256
        // a different conversion is a different identity
        assertNotEquals(id, PolyphaseResampler(44_100, 16_000).identity)
        // same conversion, same identity
        assertEquals(id, PolyphaseResampler(48_000, 16_000).identity)
    }

    @Test
    @Requirement("AC-97")
    fun `44100 to 16000 is a rational 441 to 160 conversion`() {
        val r = PolyphaseResampler(44_100, 16_000)
        assertEquals(160, r.interpolation)
        assertEquals(441, r.decimation)
        val input = tone(44_100, 0.2, hz = 300.0)
        val out = r.resample(input)
        // ~16000/44100 of the samples
        assertEquals((input.size.toLong() * 160 / 441).toInt(), out.size)
    }

    @Test
    fun `equal rates are the identity function`() {
        val r = PolyphaseResampler(16_000, 16_000)
        assertTrue(r.isPassthrough)
        val input = tone(16_000, 0.1, hz = 200.0)
        assertTrue(r.resample(input).contentEquals(input))
    }

    @Test
    fun `a low-frequency tone survives 48 to 16 kHz with its amplitude roughly intact`() {
        val input = tone(48_000, 0.25, hz = 200.0, amp = 0.5)
        val out = PolyphaseResampler(48_000, 16_000).resample(input)
        val peakIn = input.maxOf { abs(it.toInt()) }
        val peakOut = out.drop(64).dropLast(64).maxOf { abs(it.toInt()) } // ignore filter edge transient
        assertTrue(abs(peakOut - peakIn) < peakIn * 0.1, "peak $peakOut vs $peakIn")
    }
}
