package org.ort.core

/**
 * The one clock the whole application reads (technical design §3.2; FR-RUN-15).
 *
 * Monotonic and wall time are **separate readings with separate purposes**:
 *
 * - [monotonicNanos] drives every duration, interval, timeout, retention window and decay.
 *   It never goes backwards and is unaffected by the user or the network changing the date.
 * - [wallMillis] is for display and for the stored `startedAtUtc` only. It can jump.
 *
 * No production call site may read `System.currentTimeMillis()` or `SystemClock` directly —
 * a lint rule enforces it — because the thread-gap logic, the §97.119 ten-minute window,
 * retention and voiceprint decay are otherwise untestable (FR-TST-2 -> AC-91).
 */
public interface Clock {

    /** Nanoseconds from an arbitrary fixed origin. Monotonic. Maps to `elapsedRealtimeNanos()`. */
    public fun monotonicNanos(): Long

    /** Milliseconds since the Unix epoch, UTC. For display and storage, never for durations. */
    public fun wallMillis(): Long

    /** The device's current UTC offset in minutes, stored alongside every wall time (FR-RUN-18). */
    public fun utcOffsetMinutes(): Int
}
