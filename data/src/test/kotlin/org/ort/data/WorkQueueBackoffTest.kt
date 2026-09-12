package org.ort.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ort.testing.Requirement

/**
 * Register R-1002 (halt): [WorkQueueBackoff] is a derived formula (double, then cap), not a
 * hand-picked table — these tests pin the exact rungs a reader of the halt report would expect
 * (10s, 20s, 40s, 80s) and prove the cap and monotonicity hold generally, not just at those four
 * points.
 */
public class WorkQueueBackoffTest {

    @Test
    @Requirement("R-1002")
    public fun R_1002_the_first_four_rungs_double_each_time_starting_at_ten_seconds() {
        assertEquals(10_000L, WorkQueueBackoff.delayMillisFor(1))
        assertEquals(20_000L, WorkQueueBackoff.delayMillisFor(2))
        assertEquals(40_000L, WorkQueueBackoff.delayMillisFor(3))
        assertEquals(80_000L, WorkQueueBackoff.delayMillisFor(4))
    }

    @Test
    @Requirement("R-1002")
    public fun R_1002_the_ladder_is_capped_and_never_overflows_for_a_very_large_attempt_number() {
        val capped = WorkQueueBackoff.delayMillisFor(63)
        assertEquals(300_000L, capped)
        // Every attempt at or beyond the point the formula would exceed the cap must read the
        // same capped value -- never a negative number from a shifted-out-of-range Long.
        assertTrue(WorkQueueBackoff.delayMillisFor(1_000) in 1..300_000L)
    }

    @Test
    @Requirement("R-1002")
    public fun R_1002_the_ladder_never_decreases_as_the_attempt_number_grows() {
        var previous = 0L
        for (attempt in 1..40) {
            val delay = WorkQueueBackoff.delayMillisFor(attempt)
            assertTrue("attempt $attempt's delay ($delay) must be >= the previous rung ($previous)", delay >= previous)
            previous = delay
        }
    }

    @Test
    @Requirement("R-1002")
    public fun R_1002_attempt_below_one_is_rejected_rather_than_silently_coerced() {
        var threw = false
        try {
            WorkQueueBackoff.delayMillisFor(0)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("attempt 0 must be rejected, not silently treated as attempt 1", threw)
    }
}
