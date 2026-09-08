package org.ort.app.ui.data

/**
 * FR-UI-12's closed, three-state set — never a boolean with a footnote. An hour with no matching
 * transmission is ambiguous unless [org.ort.data.entity.CaptureGapEntity] rows and the session's
 * own start/end are consulted: it may be that nothing was transmitted ([SILENT_WHILE_LISTENING])
 * or that the app was not listening at all ([NOT_LISTENING]). Presenting the second as the first
 * is a fabricated absence (constitution I) — exactly the failure build-plan P17 names as the one
 * most likely to be silently got wrong.
 */
public enum class HourActivityState { HEARD, SILENT_WHILE_LISTENING, NOT_LISTENING }

/** One hour-of-day bucket (UTC) of an activity pattern (FR-UI-11), aggregated across every session/day recorded. */
public data class HourActivityBucket(
    val hourOfDayUtc: Int,
    val state: HourActivityState,
    /**
     * How many matching transmissions fell in this hour-of-day, across every day recorded. Zero
     * unless [state] is [HourActivityState.HEARD].
     */
    val heardCount: Int,
)

/**
 * One session's listening window and the gaps recorded within it (build-plan P17:
 * `SessionDao`/`CaptureGapDao` already record this — this is just the shape [ActivityPatternMapper]
 * needs).
 */
public data class SessionWindow(
    val startedAtUtc: Long,
    /** Null exactly when the session has not ended yet — treated as listening up to `nowMillis`, not forever. */
    val endedAtUtc: Long?,
    val gaps: List<GapWindow>,
)

/** One [org.ort.data.entity.CaptureGapEntity]'s window — silence that was never listened to (FR-RUN-12). */
public data class GapWindow(
    val startedAt: Long,
    /**
     * Null exactly when the gap was never recorded as recovered — treated as not-listening up to
     * the session's own end, not forever.
     */
    val endedAt: Long?,
)

/**
 * Builds the FR-UI-11 hour-of-day activity pattern for one station or frequency (build-plan
 * P17), structurally distinguishing "not heard" from "not listening" per FR-UI-12.
 *
 * Every real-world hour across every [sessions] window is classified once: [HourActivityState.HEARD]
 * if a matching transmission's timestamp falls in it (audio is the source of truth — constitution
 * III — so a real transmission always wins, even over a gap a data inconsistency might otherwise
 * mark not-listening); else [HourActivityState.SILENT_WHILE_LISTENING] if the session was
 * listening through it (in [SessionWindow.startedAtUtc]..[SessionWindow.endedAtUtc], outside every
 * [SessionWindow.gaps] window); else [HourActivityState.NOT_LISTENING] — either genuinely inside a
 * gap, or outside every session's window entirely (an hour never captured on any day at all,
 * which must never read as "quiet" just because nothing is known about it).
 *
 * Real hours are then folded onto the 24 hour-of-day buckets this function always returns, one
 * per hour 0..23 UTC — day-of-week patterning (FR-UI-11 also asks for "by day of week") is left
 * for a follow-up; see build-plan P17's CHANGELOG entry.
 */
public object ActivityPatternMapper {

    private const val HOUR_MILLIS = 3_600_000L
    private const val HOURS_PER_DAY = 24

    public fun buildPattern(
        sessions: List<SessionWindow>,
        matchingTransmissionTimestamps: List<Long>,
        nowMillis: Long,
    ): List<HourActivityBucket> {
        val heardRealHours = matchingTransmissionTimestamps.map { epochHour(it) }.toSet()
        val listeningRealHours = mutableSetOf<Long>()
        val notListeningRealHours = mutableSetOf<Long>()

        for (session in sessions) {
            val sessionEnd = session.endedAtUtc ?: nowMillis
            if (sessionEnd <= session.startedAtUtc) continue
            val listeningIntervals = subtractGaps(session.startedAtUtc, sessionEnd, session.gaps)
            listeningIntervals.forEach { (start, end) -> addRealHours(start, end, listeningRealHours) }
            session.gaps.forEach { gap ->
                val gapStart = maxOf(gap.startedAt, session.startedAtUtc)
                val gapEnd = minOf(gap.endedAt ?: sessionEnd, sessionEnd)
                addRealHours(gapStart, gapEnd, notListeningRealHours)
            }
        }

        // Priority order, matching the doc comment: heard, then listening-but-silent, then not-listening.
        listeningRealHours.removeAll(heardRealHours)
        notListeningRealHours.removeAll(heardRealHours)
        notListeningRealHours.removeAll(listeningRealHours)

        val heardCountByHourOfDay = IntArray(HOURS_PER_DAY)
        matchingTransmissionTimestamps.forEach { heardCountByHourOfDay[hourOfDay(epochHour(it))]++ }

        val heardHoursOfDay = heardRealHours.map { hourOfDay(it) }.toSet()
        val listeningHoursOfDay = listeningRealHours.map { hourOfDay(it) }.toSet()

        return (0 until HOURS_PER_DAY).map { hod ->
            val state = when {
                hod in heardHoursOfDay -> HourActivityState.HEARD
                hod in listeningHoursOfDay -> HourActivityState.SILENT_WHILE_LISTENING
                else -> HourActivityState.NOT_LISTENING
            }
            HourActivityBucket(hourOfDayUtc = hod, state = state, heardCount = heardCountByHourOfDay[hod])
        }
    }

    private fun epochHour(millis: Long): Long = Math.floorDiv(millis, HOUR_MILLIS)

    private fun hourOfDay(epochHour: Long): Int = Math.floorMod(epochHour, HOURS_PER_DAY.toLong()).toInt()

    /** Every real-world hour touched, even partially, by `[start, end)`. */
    private fun addRealHours(start: Long, end: Long, into: MutableSet<Long>) {
        if (end <= start) return
        val first = epochHour(start)
        val last = epochHour(end - 1)
        for (h in first..last) into.add(h)
    }

    /** `[start, end)` with every gap window (clipped to `[start, end)`) removed. */
    private fun subtractGaps(start: Long, end: Long, gaps: List<GapWindow>): List<Pair<Long, Long>> {
        val clipped = gaps
            .map { g -> maxOf(g.startedAt, start) to minOf(g.endedAt ?: end, end) }
            .filter { it.second > it.first }
            .sortedBy { it.first }
        val result = mutableListOf<Pair<Long, Long>>()
        var cursor = start
        for ((gapStart, gapEnd) in clipped) {
            if (gapStart > cursor) result.add(cursor to gapStart)
            cursor = maxOf(cursor, gapEnd)
        }
        if (cursor < end) result.add(cursor to end)
        return result
    }
}
