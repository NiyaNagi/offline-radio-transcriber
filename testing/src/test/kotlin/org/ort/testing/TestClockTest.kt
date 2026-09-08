package org.ort.testing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.ort.core.SampleClock

class TestClockTest {

    @Test
    fun `FR_TST_2 AC_91 the test clock advances only when a test advances it`() {
        val clock = TestClock(startMonotonicNanos = 0, startWallMillis = 1_000)
        assertEquals(0, clock.monotonicNanos())
        clock.advance(millis = 250)
        assertEquals(250_000_000, clock.monotonicNanos())
        assertEquals(1_250, clock.wallMillis())
    }

    @Test
    fun `AC_91 F14 a wall-clock jump does not touch the monotonic timeline`() {
        val clock = TestClock(startMonotonicNanos = 10, startWallMillis = 1_000)
        clock.advance(100)
        val monotonicBefore = clock.monotonicNanos()

        clock.jumpWallBy(-3_600_000) // clock goes backwards an hour

        assertEquals(monotonicBefore, clock.monotonicNanos())
        assertEquals(1_000 + 100 - 3_600_000, clock.wallMillis())
    }

    @Test
    fun `the test clock drives a SampleClock deterministically`() {
        val clock = TestClock(startMonotonicNanos = 0, startWallMillis = 2_000)
        val sc = SampleClock.anchoredNow(clock, sampleRate = 16_000)
        assertEquals(2_500, sc.wallMillisAt(8_000))
    }
}
