package org.ort.captureapi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.testing.Requirement

class RingBufferTest {

    private val rate = 16_000

    @Test
    @Requirement("AC-3", "FR-CAP-4")
    fun `AC_3 pre-roll snapshot returns audio from before the trigger point`() {
        val ring = RingBuffer(rate * 8) // 8 s
        // write 3 s of a ramp: sample value == its index (mod)
        val total = rate * 3
        val chunk = 512
        var i = 0
        while (i < total) {
            val n = minOf(chunk, total - i)
            ring.write(ShortArray(n) { (i + it).toShort() })
            i += n
        }
        // "VAD triggers now" — grab the 1200 ms before it
        val preRoll = ring.snapshotPreRoll(millisBack = 1200, sampleRate = rate)
        assertEquals(rate * 1200 / 1000, preRoll.size)

        val firstPreRollSample = total - preRoll.size
        // the snapshot is the audio immediately preceding the trigger, in order
        assertEquals(firstPreRollSample.toShort(), preRoll.first())
        assertEquals((total - 1).toShort(), preRoll.last())
    }

    @Test
    @Requirement("AC-3", "NFR-4")
    fun `AC_3 overrun is detectable rather than silent`() {
        val ring = RingBuffer(1024)
        assertEquals(1024, ring.capacity)

        ring.write(ShortArray(1000))
        assertFalse(ring.hasOverrun())
        assertEquals(0L, ring.droppedSamples())

        // writer laps a reader that never drained
        ring.write(ShortArray(500))
        assertTrue(ring.hasOverrun())
        assertEquals(476L, ring.droppedSamples())
    }

    @Test
    fun `a reader that keeps up sees every sample and never reports overrun`() {
        val ring = RingBuffer(2048)
        val out = ArrayList<Short>()
        val dst = ShortArray(300)
        repeat(20) { round ->
            ring.write(ShortArray(200) { (round * 200 + it).toShort() })
            var n = ring.read(dst)
            while (n > 0) {
                for (k in 0 until n) out.add(dst[k])
                n = ring.read(dst)
            }
        }
        assertFalse(ring.hasOverrun())
        assertEquals(List(4000) { it.toShort() }, out)
    }

    @Test
    @Requirement("AC-3")
    fun `a pre-roll window larger than the buffer is flagged and truncated, not faked`() {
        val ring = RingBuffer(rate) // 1 s
        ring.write(ShortArray(rate))
        assertTrue(ring.preRollOverrun(millisBack = 2000, sampleRate = rate))
        assertEquals(rate, ring.snapshotPreRoll(2000, rate).size) // capped at capacity, not zero-padded to 2 s
    }

    @Test
    fun `capacity rounds up to a power of two`() {
        assertEquals(1024, RingBuffer(1000).capacity)
        assertEquals(131_072, RingBuffer(rate * 8).capacity)
        assertEquals(2048, RingBuffer(2048).capacity)
    }
}
