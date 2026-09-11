package org.ort.pipeline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.ort.capture.android.BackoffLadder

/** F23/F9: [reconnectLadderPositionAt] against the real [BackoffLadder] steps (1s, 2s, 5s, 10s, 30s). */
class ReconnectLadderTest {

    private val ofTotal = 5

    @Test
    fun `at zero elapsed time the first attempt is pending with its full delay remaining`() {
        val position = reconnectLadderPositionAt(0L, ofTotal, BackoffLadder::delayMillisFor)
        assertEquals(ReconnectLadderPosition(attempt = 1, ofTotal = 5, nextRetryInMillis = 1_000L), position)
    }

    @Test
    fun `partway through the first step reports the remaining time, not the full delay`() {
        val position = reconnectLadderPositionAt(400L, ofTotal, BackoffLadder::delayMillisFor)
        assertEquals(ReconnectLadderPosition(attempt = 1, ofTotal = 5, nextRetryInMillis = 600L), position)
    }

    @Test
    fun `once the first step elapses the position moves to attempt 2`() {
        // 1_000ms (step 1) + 500ms into step 2 (2_000ms).
        val position = reconnectLadderPositionAt(1_500L, ofTotal, BackoffLadder::delayMillisFor)
        assertEquals(ReconnectLadderPosition(attempt = 2, ofTotal = 5, nextRetryInMillis = 1_500L), position)
    }

    @Test
    fun `past the final step the position holds at the last attempt, never exceeding ofTotal`() {
        // 1_000 + 2_000 + 5_000 + 10_000 + 30_000 = 48_000ms clears every step.
        val position = reconnectLadderPositionAt(100_000L, ofTotal, BackoffLadder::delayMillisFor)
        assertEquals(5, position.attempt)
        assertEquals(5, position.ofTotal)
    }

    @Test
    fun `exactly at a step boundary the position has already advanced to the next attempt`() {
        val position = reconnectLadderPositionAt(1_000L, ofTotal, BackoffLadder::delayMillisFor)
        assertEquals(ReconnectLadderPosition(attempt = 2, ofTotal = 5, nextRetryInMillis = 2_000L), position)
    }
}
