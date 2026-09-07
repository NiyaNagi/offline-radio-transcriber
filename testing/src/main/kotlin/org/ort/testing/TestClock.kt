package org.ort.testing

import org.ort.core.Clock

/**
 * A [Clock] whose time only moves when a test moves it (test-plan §4; AC-91). Monotonic and
 * wall time advance **independently** so a test can simulate a wall-clock jump — a date
 * change, an NTP correction — without touching the monotonic timeline the durations use (F14).
 */
public class TestClock(
    startMonotonicNanos: Long = 0L,
    startWallMillis: Long = 1_600_000_000_000L,
    private var utcOffsetMinutes: Int = 0,
) : Clock {

    private var monotonicNanos: Long = startMonotonicNanos
    private var wallMillis: Long = startWallMillis

    override fun monotonicNanos(): Long = monotonicNanos

    override fun wallMillis(): Long = wallMillis

    override fun utcOffsetMinutes(): Int = utcOffsetMinutes

    /** Advance both clocks by the same real duration — the ordinary passage of time. */
    public fun advance(millis: Long) {
        require(millis >= 0) { "time does not run backwards; use jumpWallTo for that" }
        monotonicNanos += millis * 1_000_000
        wallMillis += millis
    }

    public fun advanceNanos(nanos: Long) {
        require(nanos >= 0) { "monotonic time does not run backwards" }
        monotonicNanos += nanos
        wallMillis += nanos / 1_000_000
    }

    /** Move ONLY the wall clock — the monotonic timeline is untouched. Models an OS date change. */
    public fun jumpWallTo(newWallMillis: Long) {
        wallMillis = newWallMillis
    }

    public fun jumpWallBy(deltaMillis: Long) {
        wallMillis += deltaMillis
    }

    public fun setUtcOffsetMinutes(minutes: Int) {
        utcOffsetMinutes = minutes
    }
}
