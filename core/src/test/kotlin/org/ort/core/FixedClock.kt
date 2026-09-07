package org.ort.core

/**
 * A local manual clock for `:core`'s own tests. The shared `TestClock` lives in `:testing`,
 * which depends on `:core` — so `:core` cannot depend on it without a cycle. This is the
 * fifteen lines that avoids one.
 */
internal class FixedClock(var monotonic: Long = 0L, var wall: Long = 1_600_000_000_000L, var offsetMinutes: Int = 0) :
    Clock {
    override fun monotonicNanos(): Long = monotonic
    override fun wallMillis(): Long = wall
    override fun utcOffsetMinutes(): Int = offsetMinutes
}
