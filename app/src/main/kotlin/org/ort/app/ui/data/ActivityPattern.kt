package org.ort.app.ui.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * FR-UI-12's closed, three-state set — never a boolean with a footnote. An hour with no matching
 * transmission is ambiguous unless [org.ort.data.entity.CaptureGapEntity] rows and the session's
 * own start/end are consulted: it may be that nothing was transmitted ([SILENT_WHILE_LISTENING])
 * or that the app was not listening at all ([NOT_LISTENING]). Presenting the second as the first
 * is a fabricated absence (constitution I) — exactly the failure build-plan P17 names as the one
 * most likely to be silently got wrong.
 */
public enum class HourActivityState { HEARD, SILENT_WHILE_LISTENING, NOT_LISTENING }

/** Common shape shared by every activity-pattern bucket, so [org.ort.app.ui.components.ActivityPatternChart]
 * can render any of them (hour-of-day, day-of-week, ...) without knowing which. */
public interface ActivityBucket {
    public val state: HourActivityState
    public val heardCount: Int
}

/** One hour-of-day bucket (UTC) of an activity pattern (FR-UI-11), aggregated across every session/day recorded. */
public data class HourActivityBucket(
    val hourOfDayUtc: Int,
    override val state: HourActivityState,
    /**
     * How many matching transmissions fell in this hour-of-day, across every day recorded. Zero
     * unless [state] is [HourActivityState.HEARD].
     */
    override val heardCount: Int,
) : ActivityBucket

/**
 * One day-of-week bucket (Monday-first, ISO — [java.time.DayOfWeek]'s own order) of an activity
 * pattern (FR-UI-11's other half), aggregated across every calendar day recorded, in the
 * **device's own time zone** (F-001/F-019: real wall-clock timestamps now exist; a day boundary is
 * a local-calendar fact, never a sample position or a UTC accident).
 */
public data class DayOfWeekActivityBucket(
    val dayOfWeek: DayOfWeek,
    override val state: HourActivityState,
    /** How many matching transmissions fell on this weekday, across every day recorded. */
    override val heardCount: Int,
) : ActivityBucket

/**
 * FR-UI-11's "and how that has changed": the cheapest reading of a trend that does not fabricate
 * one — the most recent 7-day window's count for this weekday against the 7 days before it.
 * [NO_DATA] whenever *either* window had no listening time at all for this weekday (constitution
 * I: a trend is only ever reported between two real measurements, never invented from an absence).
 */
public enum class WeekTrend { UP, DOWN, FLAT, NO_DATA }

/** One weekday's week-over-week comparison (FR-UI-11). */
public data class WeekOverWeekBucket(
    val dayOfWeek: DayOfWeek,
    val trend: WeekTrend,
    val currentHeardCount: Int,
    val previousHeardCount: Int,
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
 * per hour 0..23 UTC. [buildDayOfWeekPattern] does the same by calendar day for FR-UI-11's other
 * half ("by day of week"), and [buildWeekOverWeekComparison] for "how that has changed" (audit
 * F-019).
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

    /**
     * The FR-UI-11 day-of-week pattern, structurally identical in its three-state semantics to
     * [buildPattern] but bucketed by calendar day, in [zone] (the device's own zone — never a
     * sample position, per F-001), and folded onto the 7 ISO weekdays ([DayOfWeek.values], already
     * Monday-first) this function always returns.
     */
    public fun buildDayOfWeekPattern(
        sessions: List<SessionWindow>,
        matchingTransmissionTimestamps: List<Long>,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<DayOfWeekActivityBucket> {
        val classified = classifyDays(sessions, matchingTransmissionTimestamps, nowMillis, zone)

        val heardCountByDow = mutableMapOf<DayOfWeek, Int>()
        matchingTransmissionTimestamps.forEach { ts ->
            val dow = dayOfWeekOf(epochDay(ts, zone))
            heardCountByDow[dow] = (heardCountByDow[dow] ?: 0) + 1
        }
        val heardDows = classified.heardDays.map { dayOfWeekOf(it) }.toSet()
        val listeningDows = classified.listeningDays.map { dayOfWeekOf(it) }.toSet()

        return DayOfWeek.values().map { dow ->
            val state = when {
                dow in heardDows -> HourActivityState.HEARD
                dow in listeningDows -> HourActivityState.SILENT_WHILE_LISTENING
                else -> HourActivityState.NOT_LISTENING
            }
            DayOfWeekActivityBucket(dayOfWeek = dow, state = state, heardCount = heardCountByDow[dow] ?: 0)
        }
    }

    /**
     * FR-UI-11's "how that has changed" (audit F-019): compares the most recent 7-day window
     * ending at [nowMillis] against the 7 days before it, per weekday. A weekday reports
     * [WeekTrend.NO_DATA] — never a fabricated up/down — whenever either window had no session
     * listening through it at all for that weekday.
     */
    public fun buildWeekOverWeekComparison(
        sessions: List<SessionWindow>,
        matchingTransmissionTimestamps: List<Long>,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<WeekOverWeekBucket> {
        val dayMillis = HOURS_PER_DAY * HOUR_MILLIS
        val currentStart = nowMillis - 7 * dayMillis
        val previousStart = nowMillis - 14 * dayMillis

        val current = perWeekdayState(sessions, matchingTransmissionTimestamps, zone, currentStart, nowMillis)
        val previous = perWeekdayState(sessions, matchingTransmissionTimestamps, zone, previousStart, currentStart)

        return DayOfWeek.values().map { dow ->
            val currentState = current.getValue(dow)
            val previousState = previous.getValue(dow)
            val trend = when {
                currentState.state == HourActivityState.NOT_LISTENING ||
                    previousState.state == HourActivityState.NOT_LISTENING -> WeekTrend.NO_DATA
                currentState.heardCount > previousState.heardCount -> WeekTrend.UP
                currentState.heardCount < previousState.heardCount -> WeekTrend.DOWN
                else -> WeekTrend.FLAT
            }
            WeekOverWeekBucket(
                dayOfWeek = dow,
                trend = trend,
                currentHeardCount = currentState.heardCount,
                previousHeardCount = previousState.heardCount,
            )
        }
    }

    private data class DayState(val state: HourActivityState, val heardCount: Int)

    private data class DayClassification(val heardDays: Set<Long>, val listeningDays: Set<Long>)

    /** [DayState] per ISO weekday, considering only the slice of [sessions]/[matchingTransmissionTimestamps]
     * within `[rangeStart, rangeEndExclusive)` — the building block [buildWeekOverWeekComparison] compares
     * two windows of. A weekday with nothing in range defaults to [HourActivityState.NOT_LISTENING],
     * exactly as an untouched hour does in [buildPattern] — absence of data and absence of listening
     * read identically here on purpose (constitution I: never invent a distinction the data can't support). */
    private fun perWeekdayState(
        sessions: List<SessionWindow>,
        matchingTransmissionTimestamps: List<Long>,
        zone: ZoneId,
        rangeStart: Long,
        rangeEndExclusive: Long,
    ): Map<DayOfWeek, DayState> {
        val clippedTimestamps = matchingTransmissionTimestamps.filter { it in rangeStart until rangeEndExclusive }
        val clippedSessions = sessions.mapNotNull { session ->
            val sessionEnd = session.endedAtUtc ?: rangeEndExclusive
            val start = maxOf(session.startedAtUtc, rangeStart)
            val end = minOf(sessionEnd, rangeEndExclusive)
            if (end <= start) return@mapNotNull null
            val gaps = session.gaps.mapNotNull { gap ->
                val gapEndRaw = gap.endedAt ?: sessionEnd
                val gapStart = maxOf(gap.startedAt, start)
                val gapEnd = minOf(gapEndRaw, end)
                if (gapEnd <= gapStart) null else GapWindow(startedAt = gapStart, endedAt = gapEnd)
            }
            SessionWindow(startedAtUtc = start, endedAtUtc = end, gaps = gaps)
        }

        val classified = classifyDays(clippedSessions, clippedTimestamps, rangeEndExclusive, zone)
        val heardCountByDow = mutableMapOf<DayOfWeek, Int>()
        clippedTimestamps.forEach { ts ->
            val dow = dayOfWeekOf(epochDay(ts, zone))
            heardCountByDow[dow] = (heardCountByDow[dow] ?: 0) + 1
        }
        val heardDows = classified.heardDays.map { dayOfWeekOf(it) }.toSet()
        val listeningDows = classified.listeningDays.map { dayOfWeekOf(it) }.toSet()

        return DayOfWeek.values().associateWith { dow ->
            val state = when {
                dow in heardDows -> HourActivityState.HEARD
                dow in listeningDows -> HourActivityState.SILENT_WHILE_LISTENING
                else -> HourActivityState.NOT_LISTENING
            }
            DayState(state, heardCountByDow[dow] ?: 0)
        }
    }

    /** [buildPattern]'s heard/listening classification, at calendar-day (rather than hour) granularity. */
    private fun classifyDays(
        sessions: List<SessionWindow>,
        matchingTransmissionTimestamps: List<Long>,
        nowMillis: Long,
        zone: ZoneId,
    ): DayClassification {
        val heardRealDays = matchingTransmissionTimestamps.map { epochDay(it, zone) }.toSet()
        val listeningRealDays = mutableSetOf<Long>()
        val notListeningRealDays = mutableSetOf<Long>()

        for (session in sessions) {
            val sessionEnd = session.endedAtUtc ?: nowMillis
            if (sessionEnd <= session.startedAtUtc) continue
            val listeningIntervals = subtractGaps(session.startedAtUtc, sessionEnd, session.gaps)
            listeningIntervals.forEach { (start, end) -> addRealDays(start, end, zone, listeningRealDays) }
            session.gaps.forEach { gap ->
                val gapStart = maxOf(gap.startedAt, session.startedAtUtc)
                val gapEnd = minOf(gap.endedAt ?: sessionEnd, sessionEnd)
                addRealDays(gapStart, gapEnd, zone, notListeningRealDays)
            }
        }

        listeningRealDays.removeAll(heardRealDays)
        notListeningRealDays.removeAll(heardRealDays)
        notListeningRealDays.removeAll(listeningRealDays)

        return DayClassification(heardDays = heardRealDays, listeningDays = listeningRealDays)
    }

    private fun epochHour(millis: Long): Long = Math.floorDiv(millis, HOUR_MILLIS)

    private fun hourOfDay(epochHour: Long): Int = Math.floorMod(epochHour, HOURS_PER_DAY.toLong()).toInt()

    private fun epochDay(millis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay()

    private fun dayOfWeekOf(epochDay: Long): DayOfWeek = LocalDate.ofEpochDay(epochDay).dayOfWeek

    /** Every real calendar day, in [zone], touched even partially by `[start, end)`. */
    private fun addRealDays(start: Long, end: Long, zone: ZoneId, into: MutableSet<Long>) {
        if (end <= start) return
        val first = epochDay(start, zone)
        val last = epochDay(end - 1, zone)
        for (d in first..last) into.add(d)
    }

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
