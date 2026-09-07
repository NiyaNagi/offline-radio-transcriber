package org.ort.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SampleClockTest {

    private val clock = FixedClock(monotonic = 5_000_000_000L, wall = 1_700_000_000_000L, offsetMinutes = -420)

    @Test
    fun `FR_RUN_16 sample position maps to time linearly from the session anchor`() {
        val sc = SampleClock.anchoredNow(clock, sampleRate = 16_000)

        // one second of audio = 16000 samples
        assertEquals(1_000L, sc.elapsedMillisAt(16_000))
        assertEquals(clock.wall + 1_000L, sc.wallMillisAt(16_000))
        assertEquals(clock.monotonic + 1_000_000_000L, sc.monotonicNanosAt(16_000))
    }

    @Test
    fun `FR_RUN_15 monotonic and wall start values are both persisted`() {
        val sc = SampleClock.anchoredNow(clock)
        val ts = sc.timestampsAt(samplePosition = 48_000)
        assertEquals(48_000L, ts.samplePosition)
        assertEquals(clock.monotonic + 3_000_000_000L, ts.monotonicStartNanos)
        assertEquals(clock.wall + 3_000L, ts.startedAtUtcMillis)
        assertEquals(-420, ts.utcOffsetMinutes)
    }

    @Test
    fun `AC_91 F14 a wall-clock jump mid-session does not move an already-derived timestamp`() {
        val sc = SampleClock.anchoredNow(clock)
        val before = sc.wallMillisAt(160_000) // 10 s in

        // the OS date jumps forward two hours while the session runs
        clock.wall += 2 * 60 * 60 * 1000L

        val after = sc.wallMillisAt(160_000)
        assertEquals(before, after, "sample-derived wall time must not consult the live clock again")

        // and monotonic-derived durations are likewise unaffected
        assertEquals(10_000L, sc.elapsedMillisAt(160_000))
    }

    @Test
    fun `FR_RUN_17 a monotonic rig timestamp resolves to a sample position within skew`() {
        val sc = SampleClock.anchoredNow(clock, sampleRate = 16_000)
        val rigEventNanos = clock.monotonic + 250_000_000L // 250 ms after anchor
        assertEquals(4_000L, sc.samplePositionAtMonotonic(rigEventNanos))
    }

    @Test
    fun `elapsed nanos are overflow-safe for a multi-day session`() {
        val sc = SampleClock(0, 0, 0, sampleRate = 16_000)
        val threeDaysOfSamples = 3L * 24 * 60 * 60 * 16_000
        assertTrue(sc.elapsedNanosAt(threeDaysOfSamples) > 0)
        assertEquals(3L * 24 * 60 * 60 * 1000, sc.elapsedMillisAt(threeDaysOfSamples))
    }

    @Test
    fun `two anchors taken at different instants differ`() {
        val a = SampleClock.anchoredNow(clock)
        clock.monotonic += 1
        clock.wall += 1
        val b = SampleClock.anchoredNow(clock)
        assertNotEquals(a.anchorMonotonicNanos, b.anchorMonotonicNanos)
    }
}
