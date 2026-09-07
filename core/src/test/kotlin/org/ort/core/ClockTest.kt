package org.ort.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClockTest {

    @Test
    fun `AC_91 a clock separates the monotonic timeline from the wall timeline`() {
        val clock = FixedClock(monotonic = 100, wall = 1_000)
        clock.monotonic += 5_000
        // wall did not move just because monotonic did
        assertEquals(1_000, clock.wallMillis())
        assertEquals(5_100, clock.monotonicNanos())
    }

    @Test
    fun `SystemClock is monotonic non-decreasing`() {
        val a = SystemClock.monotonicNanos()
        val b = SystemClock.monotonicNanos()
        assertTrue(b >= a)
    }

    @Test
    fun `SystemClock reports an offset consistent with a wall reading`() {
        // just assert it is a plausible minute offset, not a specific zone
        assertTrue(SystemClock.utcOffsetMinutes() in -14 * 60..14 * 60)
    }
}
