package org.ort.capture.android

import org.ort.captureapi.CaptureEvent
import org.ort.core.Clock

/**
 * One capture gap. Bounds are read from [Clock] at the instant the [CaptureEvent.Interrupted] and
 * [CaptureEvent.Resumed] events are observed — monotonic for durations, wall time for storage
 * and display, matching `CaptureGapEntity`'s `startedAt`/`endedAt` (functional spec §8). A live
 * device produces no [CaptureEvent.Frames] during an outage at all, so sample position cannot
 * express its duration; the clock can (FR-RUN-15).
 */
public data class GapRecord(
    val startMonotonicNanos: Long,
    val endMonotonicNanos: Long,
    val startWallMillis: Long,
    val endWallMillis: Long,
    val cause: String,
)

/**
 * Derives [GapRecord]s from a [CaptureSource][org.ort.captureapi.CaptureSource]'s event stream
 * (FR-RUN-11, FR-RUN-12 → AC-48, AC-49). `:capture-android` cannot depend on `:data` (module
 * graph), so this yields a plain [GapRecord]; `:pipeline` maps it onto `CaptureGapEntity` for
 * storage.
 *
 * The distinguishing fact this makes possible: a gap is a *recorded, bounded* absence — a
 * [GapRecord] with a nonzero duration and a cause — structurally different from genuine captured
 * silence, which is ordinary [CaptureEvent.Frames] (however low their amplitude) with no
 * [GapRecord] at all (AC-49).
 *
 * One [CaptureEvent.Interrupted] shape is closed immediately instead of waiting for a paired
 * [CaptureEvent.Resumed]: a [DroppedSpanCause]-encoded cause from [AudioRecordSource], reporting
 * a stalled downstream collector or an `AudioRecord` shortfall it has already measured in full by
 * the time it is reported (AC-3). The [CaptureEvent.Resumed] that follows it is a no-op here —
 * nothing was left open for it to close.
 */
public class GapTracker(private val clock: Clock) {

    private companion object {
        private const val NANOS_PER_MILLI: Long = 1_000_000L
    }

    private var openStartMonotonic: Long? = null
    private var openStartWall: Long = 0L
    private var openCause: String = "unknown"
    private val mutableGaps = mutableListOf<GapRecord>()

    public val gaps: List<GapRecord> get() = mutableGaps

    public fun onEvent(event: CaptureEvent) {
        when (event) {
            is CaptureEvent.Interrupted -> {
                val droppedSpanMillis = DroppedSpanCause.durationMillisOrNull(event.cause)
                if (droppedSpanMillis != null) {
                    // AudioRecordSource already knows the whole span by the time it reports
                    // this — it is detected after the fact, not observed as it opens — so the
                    // gap is recorded closed immediately rather than waiting for a paired
                    // Resumed (AC-3).
                    val endWall = clock.wallMillis()
                    val endMonotonic = clock.monotonicNanos()
                    mutableGaps.add(
                        GapRecord(
                            startMonotonicNanos = endMonotonic - droppedSpanMillis * NANOS_PER_MILLI,
                            endMonotonicNanos = endMonotonic,
                            startWallMillis = endWall - droppedSpanMillis,
                            endWallMillis = endWall,
                            cause = event.cause,
                        ),
                    )
                } else if (openStartMonotonic == null) {
                    openStartMonotonic = clock.monotonicNanos()
                    openStartWall = clock.wallMillis()
                    openCause = event.cause
                }
            }
            CaptureEvent.Resumed -> {
                val start = openStartMonotonic
                if (start != null) {
                    mutableGaps.add(
                        GapRecord(
                            startMonotonicNanos = start,
                            endMonotonicNanos = clock.monotonicNanos(),
                            startWallMillis = openStartWall,
                            endWallMillis = clock.wallMillis(),
                            cause = openCause,
                        ),
                    )
                    openStartMonotonic = null
                }
            }
            else -> Unit
        }
    }
}
