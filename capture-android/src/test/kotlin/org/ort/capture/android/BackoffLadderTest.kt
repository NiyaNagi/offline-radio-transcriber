package org.ort.capture.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.ort.testing.Requirement

class BackoffLadderTest {

    @Test
    @Requirement("AC-48", "FR-RUN-11")
    fun `AC_48 the retry ladder is 1s 2s 5s 10s 30s then holds at 30s`() {
        assertEquals(1_000L, BackoffLadder.delayMillisFor(1))
        assertEquals(2_000L, BackoffLadder.delayMillisFor(2))
        assertEquals(5_000L, BackoffLadder.delayMillisFor(3))
        assertEquals(10_000L, BackoffLadder.delayMillisFor(4))
        assertEquals(30_000L, BackoffLadder.delayMillisFor(5))
        assertEquals(30_000L, BackoffLadder.delayMillisFor(6))
        assertEquals(30_000L, BackoffLadder.delayMillisFor(50))
    }
}
