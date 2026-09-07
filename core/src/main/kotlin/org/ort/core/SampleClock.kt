package org.ort.core

/**
 * Within a capture session the **sample position is the authoritative timeline** (FR-RUN-16).
 * Every transmission timestamp is derived from how many samples were read before it, not from
 * a wall-clock read at processing time.
 *
 * A `SampleClock` is created once, at session start, from an anchor captured at the first
 * successful `AudioRecord` read: the monotonic and wall readings *at that instant*, the UTC
 * offset, and the negotiated output sample rate (always 16 kHz post-resample, but taken as a
 * parameter so a test can use any rate).
 *
 * Because every conversion is linear from the anchor, **a wall-clock jump mid-session cannot
 * corrupt a stored timestamp** (F14): [wallMillisAt] never reads the wall clock again.
 *
 * @param sampleRate output frames per second. Must be positive.
 */
public class SampleClock(
    public val anchorMonotonicNanos: Long,
    public val anchorWallMillis: Long,
    public val anchorUtcOffsetMinutes: Int,
    public val sampleRate: Int = DEFAULT_SAMPLE_RATE,
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive, was $sampleRate" }
    }

    /** Nanoseconds since the anchor for a given sample position. Overflow-safe for multi-day runs. */
    public fun elapsedNanosAt(samplePosition: Long): Long {
        require(samplePosition >= 0) { "samplePosition must be >= 0, was $samplePosition" }
        val whole = samplePosition / sampleRate
        val rem = samplePosition % sampleRate
        return whole * NANOS_PER_SECOND + rem * NANOS_PER_SECOND / sampleRate
    }

    /** Nanoseconds since the anchor, as milliseconds. */
    public fun elapsedMillisAt(samplePosition: Long): Long = elapsedNanosAt(samplePosition) / 1_000_000

    /** Monotonic reading a processing pass should store for this transmission (FR-RUN-15). */
    public fun monotonicNanosAt(samplePosition: Long): Long = anchorMonotonicNanos + elapsedNanosAt(samplePosition)

    /**
     * UTC wall-clock millis for a sample position — computed purely from the anchor and the
     * sample count. Deliberately does not consult a [Clock]; that is what makes it survive a
     * date change during an 8-hour run.
     */
    public fun wallMillisAt(samplePosition: Long): Long = anchorWallMillis + elapsedMillisAt(samplePosition)

    /** The sample position closest to a monotonic reading (e.g. a rig event timestamped on receipt). */
    public fun samplePositionAtMonotonic(monotonicNanos: Long): Long {
        val delta = monotonicNanos - anchorMonotonicNanos
        require(delta >= 0) { "monotonic reading $monotonicNanos precedes the session anchor" }
        val whole = delta / NANOS_PER_SECOND
        val rem = delta % NANOS_PER_SECOND
        return whole * sampleRate + rem * sampleRate / NANOS_PER_SECOND
    }

    /** The full timestamp tuple persisted on every transmission (FR-RUN-15, FR-RUN-18 -> F14). */
    public fun timestampsAt(samplePosition: Long): TransmissionTimestamps = TransmissionTimestamps(
        samplePosition = samplePosition,
        monotonicStartNanos = monotonicNanosAt(samplePosition),
        startedAtUtcMillis = wallMillisAt(samplePosition),
        utcOffsetMinutes = anchorUtcOffsetMinutes,
    )

    public companion object {
        public const val DEFAULT_SAMPLE_RATE: Int = 16_000
        private const val NANOS_PER_SECOND: Long = 1_000_000_000L

        /** Build a [SampleClock] by reading [clock] once, now — at the first audio frame. */
        public fun anchoredNow(clock: Clock, sampleRate: Int = DEFAULT_SAMPLE_RATE): SampleClock = SampleClock(
            anchorMonotonicNanos = clock.monotonicNanos(),
            anchorWallMillis = clock.wallMillis(),
            anchorUtcOffsetMinutes = clock.utcOffsetMinutes(),
            sampleRate = sampleRate,
        )
    }
}

/** Every transmission stores all four (FR-RUN-15, FR-RUN-16, FR-RUN-18). */
public data class TransmissionTimestamps(
    val samplePosition: Long,
    val monotonicStartNanos: Long,
    val startedAtUtcMillis: Long,
    val utcOffsetMinutes: Int,
)
