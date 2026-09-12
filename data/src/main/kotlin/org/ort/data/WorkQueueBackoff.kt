package org.ort.data

/**
 * Register R-1002 (halt): the retry ladder between a `READY`-again attempt and the next lease.
 * Before this, [WorkQueue.failPass] returned a failed item straight to `READY` with no delay at
 * all — the operator's own device burned all five [WorkQueue.DEFAULT_MAX_ATTEMPTS] attempts at a
 * missing native ASR library within about eight seconds (04:58:56 through 04:59:04), which is a
 * burst, not a retry ladder, and for a genuinely transient fault (a decoder OOM, a momentary
 * thermal throttle) gives the system no real chance to recover between attempts before declaring
 * it permanently `FAILED`.
 *
 * Deliberately a *derived* formula (double the delay each attempt, capped) rather than a
 * hand-picked table of five numbers "tuned on one machine" (constitution, "machine-specific
 * tuning is derived, not hardcoded" — the same reasoning applied here to wall-clock delays rather
 * than fork counts): [BASE_DELAY_MILLIS] and [MAX_DELAY_MILLIS] are the only two constants, and
 * every rung of the ladder falls out of them mechanically. At the default
 * [WorkQueue.DEFAULT_MAX_ATTEMPTS] = 5, the four delays actually used before the fifth failure
 * goes terminal are 10s, 20s, 40s, 80s (150s total) — long enough that a hung decoder or a thermal
 * throttle has genuinely had a chance to clear, short enough that a real transient fault still
 * resolves inside a single short capture session.
 */
public object WorkQueueBackoff {
    private const val BASE_DELAY_MILLIS: Long = 10_000L
    private const val MAX_DELAY_MILLIS: Long = 300_000L

    /**
     * Delay before retry attempt [attempt] (1-based: the first retry, after the first failure, is
     * attempt 1) may be leased again — `BASE_DELAY_MILLIS * 2^(attempt-1)`, capped at
     * [MAX_DELAY_MILLIS] so an unexpectedly high [WorkQueue.DEFAULT_MAX_ATTEMPTS] (or a caller's
     * override) can never overflow or produce an unbounded wait.
     */
    public fun delayMillisFor(attempt: Int): Long {
        require(attempt >= 1) { "attempt must be >= 1, was $attempt" }
        // attempt - 1 capped well below 63 so the shift can never overflow into a negative Long.
        val shift = (attempt - 1).coerceAtMost(32)
        val doubled = BASE_DELAY_MILLIS shl shift
        return if (doubled < 0 || doubled > MAX_DELAY_MILLIS) MAX_DELAY_MILLIS else doubled
    }
}
