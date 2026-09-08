package org.ort.pipeline.latency

import kotlin.math.ceil

/**
 * AC-73's *mechanism* (build-plan P11, NFR-2): segment-close-to-visible latency, at the 95th
 * percentile over a session, "not as a mean" (functional spec §14.8e) — a mean hides the slow
 * tail a real operator experiences as "it's stuck". This class only computes the percentile
 * correctly from whatever it is fed; it does not — and, without the reference device, cannot —
 * assert that AC-73's 2-second bound actually holds on real hardware (constitution VI: "no
 * number without its fold, machine and provider" applies to latency numbers exactly as it does
 * to accuracy ones — see CHANGELOG's "left open").
 *
 * Not thread-safe by design: one recorder per single-threaded processing run, matching how
 * [org.ort.pipeline.PassDrainRunner] already drains a batch sequentially.
 */
public class LatencyRecorder {
    private val samplesMillis = mutableListOf<Long>()

    /** Records one already-computed segment-close-to-visible duration, in milliseconds. */
    public fun record(durationMillis: Long) {
        require(durationMillis >= 0) { "a latency duration cannot be negative, was $durationMillis" }
        samplesMillis += durationMillis
    }

    /** Convenience: derives the duration from two [org.ort.core.Clock.monotonicNanos] readings. */
    public fun recordSpan(segmentCloseNanos: Long, visibleNanos: Long) {
        record((visibleNanos - segmentCloseNanos) / 1_000_000)
    }

    /**
     * The 95th percentile by the standard nearest-rank method, or `null` with zero samples —
     * never a fabricated zero, which would silently read as "instant" (constitution I).
     */
    public fun p95Millis(): Long? {
        if (samplesMillis.isEmpty()) return null
        val sorted = samplesMillis.sorted()
        val rank = ceil(PERCENTILE * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    /** Whether the current p95 is at or under [targetMillis]. `false` (not `true`) with no samples yet. */
    public fun meetsTarget(targetMillis: Long): Boolean {
        val p95 = p95Millis() ?: return false
        return p95 <= targetMillis
    }

    public val sampleCount: Int get() = samplesMillis.size

    private companion object {
        const val PERCENTILE = 0.95
    }
}
