package org.ort.capture.android

/**
 * The interruption/route-loss retry ladder (technical design §5.5 → AC-48): 1s, 2s, 5s, 10s,
 * 30s, then holding at 30s. Capture keeps retrying forever — audio is never dropped for
 * processing pressure, and a device that comes back after five minutes must still resume
 * automatically (constitution IV).
 */
public object BackoffLadder {
    private val stepsMillis = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000)

    /** Delay before retry attempt [attempt] (1-based, first retry is attempt 1). */
    public fun delayMillisFor(attempt: Int): Long {
        require(attempt >= 1) { "attempt must be >= 1, was $attempt" }
        val idx = (attempt - 1).coerceAtMost(stepsMillis.lastIndex)
        return stepsMillis[idx]
    }
}
