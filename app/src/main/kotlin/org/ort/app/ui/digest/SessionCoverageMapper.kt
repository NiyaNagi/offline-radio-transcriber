package org.ort.app.ui.digest

import org.ort.app.ui.data.ActivityBucket
import org.ort.app.ui.data.GapWindow
import org.ort.app.ui.data.HourActivityState
import org.ort.app.ui.data.SessionWindow

/**
 * R-1069 (register, halt, constitution I): one real-time segment of `Session.dc.html`'s coverage
 * bar. [fractionStart]/[fractionEnd] are this segment's own position on the session's real elapsed
 * span — `0f` at [SessionWindow.startedAtUtc], `1f` at the session's real end — computed directly
 * from the gap/session timestamps that produced it, **never** quantized to a clock hour. This is a
 * deliberate departure from [org.ort.app.ui.data.ActivityPatternMapper.buildSessionElapsedPattern],
 * which is still correct and unchanged for `Now`'s own live chart (`NowViewStateMapper.active`),
 * whose R-1041 tap-to-filter targets genuinely need whole-hour buckets (`hourFilterWindow`) —
 * DG04's coverage bar has no such tap target and only needs to be honest about where a gap really
 * falls (the register's own capture: a 22-minute gap in a 3-hour session hatched two-thirds of the
 * bar because it touched two whole-hour buckets).
 *
 * Implements [ActivityBucket] so it can be handed straight to
 * [org.ort.app.ui.components.ActivityPatternChart] alongside [asChartWeights] — the same three-way
 * heard/silent/not-listening rendering, just no longer forced into equal-width slots.
 */
public data class SessionCoverageSegment(
    val fractionStart: Float,
    val fractionEnd: Float,
    override val state: HourActivityState,
    override val heardCount: Int,
) : ActivityBucket

/**
 * Builds [SessionCoverageSegment]s for `Session.dc.html`'s coverage bar (R-1069, halt): the
 * session's own span is cut only at its own real gap boundaries — never at a fixed clock-hour grid
 * — so a short gap produces a short, correctly-positioned hatched segment instead of hatching a
 * whole hour-wide bucket around it. A session with no gaps at all gets exactly one segment
 * spanning its whole real span; a session that ends (or is still running) with `nowMillis` after
 * its last gap gets a trailing listening segment; a gap running to the session's own end produces
 * no trailing segment at all (there is nothing after it to show).
 */
public object SessionCoverageMapper {

    public fun buildSegments(
        window: SessionWindow,
        matchingTransmissionTimestamps: List<Long>,
        nowMillis: Long,
    ): List<SessionCoverageSegment> {
        val sessionStart = window.startedAtUtc
        val sessionEnd = window.endedAtUtc ?: nowMillis
        if (sessionEnd <= sessionStart) return emptyList()
        val totalDurationMillis = (sessionEnd - sessionStart).toDouble()

        fun fractionOf(atMillis: Long): Float =
            ((atMillis - sessionStart).toDouble() / totalDurationMillis).toFloat().coerceIn(0f, 1f)

        val segments = mutableListOf<SessionCoverageSegment>()
        var cursor = sessionStart

        fun addListeningSegment(start: Long, end: Long) {
            if (end <= start) return
            val heardCount = matchingTransmissionTimestamps.count { it in start until end }
            val state = if (heardCount > 0) HourActivityState.HEARD else HourActivityState.SILENT_WHILE_LISTENING
            segments += SessionCoverageSegment(fractionOf(start), fractionOf(end), state, heardCount)
        }

        for ((gapStart, gapEnd) in mergedGaps(window.gaps, sessionStart, sessionEnd)) {
            addListeningSegment(cursor, gapStart)
            segments += SessionCoverageSegment(
                fractionStart = fractionOf(gapStart),
                fractionEnd = fractionOf(gapEnd),
                state = HourActivityState.NOT_LISTENING,
                heardCount = 0,
            )
            cursor = gapEnd
        }
        addListeningSegment(cursor, sessionEnd)
        return segments
    }

    /**
     * Every real [GapWindow], clipped to `[sessionStart, sessionEnd)` and merged where two overlap
     * or touch, sorted by start — so two overlapping/adjacent gap rows never produce two hatched
     * segments that overlap each other on the bar, and the walk in [buildSegments] can assume a
     * disjoint, ordered sequence.
     */
    private fun mergedGaps(gaps: List<GapWindow>, sessionStart: Long, sessionEnd: Long): List<Pair<Long, Long>> {
        val clipped = gaps
            .map { gap -> maxOf(gap.startedAt, sessionStart) to minOf(gap.endedAt ?: sessionEnd, sessionEnd) }
            .filter { (start, end) -> end > start }
            .sortedBy { (start, _) -> start }

        val merged = mutableListOf<Pair<Long, Long>>()
        for (gap in clipped) {
            val last = merged.lastOrNull()
            if (last != null && gap.first <= last.second) {
                merged[merged.lastIndex] = last.first to maxOf(last.second, gap.second)
            } else {
                merged += gap
            }
        }
        return merged
    }
}

/**
 * The per-bar weights [org.ort.app.ui.components.ActivityPatternChart] needs to give each
 * [SessionCoverageSegment] its own real width — its fraction of the session's total elapsed span
 * (R-1069), never an equal share the way every hour-of-day/day-of-week caller still gets. A
 * strictly-positive floor keeps a genuinely zero-width segment (a gap starting exactly on another
 * gap's end) from crashing Compose's `Modifier.weight`, which requires a positive value.
 */
public fun List<SessionCoverageSegment>.asChartWeights(): List<Float> =
    map { (it.fractionEnd - it.fractionStart).coerceAtLeast(MIN_SEGMENT_WEIGHT) }

private const val MIN_SEGMENT_WEIGHT = 0.0005f
