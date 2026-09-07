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
 */
public class GapTracker(private val clock: Clock) {

    private var openStartMonotonic: Long? = null
    private var openStartWall: Long = 0L
    private var openCause: String = "unknown"
    private val mutableGaps = mutableListOf<GapRecord>()

    public val gaps: List<GapRecord> get() = mutableGaps

    public fun onEvent(event: CaptureEvent) {
        when (event) {
            is CaptureEvent.Interrupted -> {
                if (openStartMonotonic == null) {
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
