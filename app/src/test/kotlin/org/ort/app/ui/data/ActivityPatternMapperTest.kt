package org.ort.app.ui.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.ZoneOffset

/**
 * FR-UI-12, the requirement build-plan P17 calls out by name as "the one that matters": a pattern
 * display must distinguish **"not heard" from "not listening"**. An hour with no matching
 * transmission looks identical either way unless [org.ort.data.entity.CaptureGapEntity] rows and
 * the session's own start/end are consulted — presenting a not-listening hour as though the
 * frequency/station was simply quiet is a fabricated absence (constitution I). The three states
 * are structural ([HourActivityState], a closed enum), never a boolean with a footnote.
 *
 * AC-126 ("station and frequency views show activity patterns that distinguish 'not heard' from
 * 'not listening', verified against a session containing a capture gap") is the same requirement
 * exercised from the acceptance-criteria side: the `FR_UI_12 an hour covered entirely by a
 * capture gap` test below is that verification — a real [GapWindow] on a [SessionWindow] read
 * back as [HourActivityState.NOT_LISTENING]. This mapper output only reaches the station/frequency
 * views through [org.ort.app.ui.components.ActivityPatternChart], proven separately by
 * `ActivityPatternChartTest`; only the day-of-week half of FR-UI-11 is not built (this mapper
 * buckets by hour-of-day only — no `dayOfWeek` field or bucketing exists anywhere in this file or
 * `HourActivityBucket`).
 */
class ActivityPatternMapperTest {

    private val hour = 3_600_000L

    @Test
    fun `an hour with a matching transmission is HEARD, even though the session was listening the whole time`() {
        val session = SessionWindow(startedAtUtc = 0L, endedAtUtc = hour, gaps = emptyList())

        val pattern = ActivityPatternMapper.buildPattern(
            sessions = listOf(session),
            matchingTransmissionTimestamps = listOf(hour / 2),
            nowMillis = hour,
        )

        assertEquals(HourActivityState.HEARD, pattern.single { it.hourOfDayUtc == 0 }.state)
    }

    @Test
    fun `an hour the session was listening through, with no matching transmission, is SILENT_WHILE_LISTENING`() {
        val session = SessionWindow(startedAtUtc = 0L, endedAtUtc = hour, gaps = emptyList())

        val pattern = ActivityPatternMapper.buildPattern(
            sessions = listOf(session),
            matchingTransmissionTimestamps = emptyList(),
            nowMillis = hour,
        )

        assertEquals(HourActivityState.SILENT_WHILE_LISTENING, pattern.single { it.hourOfDayUtc == 0 }.state)
    }

    @Test
    fun `AC_126_FR_UI_12 an hour covered entirely by a capture gap is NOT_LISTENING, never presented as silence`() {
        val session = SessionWindow(
            startedAtUtc = 0L,
            endedAtUtc = hour,
            gaps = listOf(GapWindow(startedAt = 0L, endedAt = hour)),
        )

        val pattern = ActivityPatternMapper.buildPattern(
            sessions = listOf(session),
            matchingTransmissionTimestamps = emptyList(),
            nowMillis = hour,
        )

        assertEquals(HourActivityState.NOT_LISTENING, pattern.single { it.hourOfDayUtc == 0 }.state)
    }

    @Test
    fun `FR_UI_12 an hour with no session ever active is NOT_LISTENING, never SILENT_WHILE_LISTENING`() {
        // No session at all covers hour-of-day 5 — this must never read as "quiet".
        val session = SessionWindow(startedAtUtc = 0L, endedAtUtc = hour, gaps = emptyList())

        val pattern = ActivityPatternMapper.buildPattern(
            sessions = listOf(session),
            matchingTransmissionTimestamps = emptyList(),
            nowMillis = hour,
        )

        assertEquals(HourActivityState.NOT_LISTENING, pattern.single { it.hourOfDayUtc == 5 }.state)
    }

    @Test
    fun `a real transmission overrides a gap that a data inconsistency would otherwise mark not-listening`() {
        // Audio is the source of truth (constitution III): if we actually heard something, that
        // hour is HEARD even if a gap row overlaps it — never contradict what was really captured.
        val session = SessionWindow(
            startedAtUtc = 0L,
            endedAtUtc = hour,
            gaps = listOf(GapWindow(startedAt = 0L, endedAt = hour)),
        )

        val pattern = ActivityPatternMapper.buildPattern(
            sessions = listOf(session),
            matchingTransmissionTimestamps = listOf(hour / 2),
            nowMillis = hour,
        )

        assertEquals(HourActivityState.HEARD, pattern.single { it.hourOfDayUtc == 0 }.state)
    }

    @Test
    fun `a session with no endedAt is treated as listening up to nowMillis, not forever`() {
        val session = SessionWindow(startedAtUtc = 0L, endedAtUtc = null, gaps = emptyList())

        val pattern = ActivityPatternMapper.buildPattern(
            sessions = listOf(session),
            matchingTransmissionTimestamps = emptyList(),
            nowMillis = hour,
        )

        assertEquals(HourActivityState.SILENT_WHILE_LISTENING, pattern.single { it.hourOfDayUtc == 0 }.state)
        // Nothing beyond `nowMillis` is claimed as listened-to.
        assertEquals(HourActivityState.NOT_LISTENING, pattern.single { it.hourOfDayUtc == 1 }.state)
    }

    @Test
    fun `an open-ended gap is treated as not-listening up to the session end, not forever`() {
        val session = SessionWindow(
            startedAtUtc = 0L,
            endedAtUtc = hour,
            gaps = listOf(GapWindow(startedAt = 0L, endedAt = null)),
        )

        val pattern = ActivityPatternMapper.buildPattern(
            sessions = listOf(session),
            matchingTransmissionTimestamps = emptyList(),
            nowMillis = hour,
        )

        assertEquals(HourActivityState.NOT_LISTENING, pattern.single { it.hourOfDayUtc == 0 }.state)
    }

    @Test
    fun `the pattern always has exactly 24 hour-of-day buckets`() {
        val pattern = ActivityPatternMapper.buildPattern(
            sessions = emptyList(),
            matchingTransmissionTimestamps = emptyList(),
            nowMillis = 0L,
        )

        assertEquals((0..23).toList(), pattern.map { it.hourOfDayUtc })
        pattern.forEach { assertEquals(HourActivityState.NOT_LISTENING, it.state) }
    }

    @Test
    fun `heardCount aggregates every matching transmission that falls in that hour-of-day, across days`() {
        val dayMillis = 24 * hour
        val session = SessionWindow(startedAtUtc = 0L, endedAtUtc = dayMillis * 2, gaps = emptyList())

        val pattern = ActivityPatternMapper.buildPattern(
            sessions = listOf(session),
            // Two transmissions a day apart, both in hour-of-day 0.
            matchingTransmissionTimestamps = listOf(0L, dayMillis),
            nowMillis = dayMillis * 2,
        )

        assertEquals(2, pattern.single { it.hourOfDayUtc == 0 }.heardCount)
    }

    // --- FR-UI-11 day-of-week and week-over-week (audit F-019) ---

    private val dayMillis = 24 * hour

    // Epoch day 0 (1970-01-01) is a Thursday; epoch day 4 (1970-01-05) is therefore a Monday, and
    // epoch day 5 a Tuesday — used as fixed, zone-independent (UTC) reference points below.
    private val mondayStart = dayMillis * 4
    private val tuesdayStart = dayMillis * 5

    @Test
    fun `FR_UI_11_day_of_week a transmission on a Tuesday lands in the Tuesday bucket as HEARD`() {
        val session =
            SessionWindow(startedAtUtc = mondayStart, endedAtUtc = mondayStart + 7 * dayMillis, gaps = emptyList())

        val pattern = ActivityPatternMapper.buildDayOfWeekPattern(
            sessions = listOf(session),
            matchingTransmissionTimestamps = listOf(tuesdayStart + hour),
            nowMillis = mondayStart + 7 * dayMillis,
            zone = ZoneOffset.UTC,
        )

        assertEquals(HourActivityState.HEARD, pattern.single { it.dayOfWeek == DayOfWeek.TUESDAY }.state)
        assertEquals(1, pattern.single { it.dayOfWeek == DayOfWeek.TUESDAY }.heardCount)
    }

    @Test
    fun `FR_UI_11_day_of_week a weekday with a session but no transmissions is SILENT_WHILE_LISTENING`() {
        val session =
            SessionWindow(startedAtUtc = mondayStart, endedAtUtc = mondayStart + 7 * dayMillis, gaps = emptyList())

        val pattern = ActivityPatternMapper.buildDayOfWeekPattern(
            sessions = listOf(session),
            matchingTransmissionTimestamps = emptyList(),
            nowMillis = mondayStart + 7 * dayMillis,
            zone = ZoneOffset.UTC,
        )

        assertEquals(
            HourActivityState.SILENT_WHILE_LISTENING,
            pattern.single { it.dayOfWeek == DayOfWeek.TUESDAY }.state,
        )
    }

    @Test
    fun `AC_126_FR_UI_11_day_of_week a weekday never listened to is NOT_LISTENING, never silence`() {
        // No session at all ever covers a Sunday.
        val session =
            SessionWindow(startedAtUtc = mondayStart, endedAtUtc = mondayStart + 6 * dayMillis, gaps = emptyList())

        val pattern = ActivityPatternMapper.buildDayOfWeekPattern(
            sessions = listOf(session),
            matchingTransmissionTimestamps = emptyList(),
            nowMillis = mondayStart + 6 * dayMillis,
            zone = ZoneOffset.UTC,
        )

        assertEquals(HourActivityState.NOT_LISTENING, pattern.single { it.dayOfWeek == DayOfWeek.SUNDAY }.state)
    }

    @Test
    fun `FR_UI_11_week_over_week reports no-data when the prior window had no listening time`() {
        val now = mondayStart + 14 * dayMillis
        // Only the most recent 7-day window (from now-7d to now) has a session; the prior 7-day
        // window (now-14d to now-7d) has none at all.
        val session = SessionWindow(startedAtUtc = now - 7 * dayMillis, endedAtUtc = now, gaps = emptyList())

        val comparison = ActivityPatternMapper.buildWeekOverWeekComparison(
            sessions = listOf(session),
            matchingTransmissionTimestamps = listOf(now - hour),
            nowMillis = now,
            zone = ZoneOffset.UTC,
        )

        comparison.forEach { bucket -> assertEquals(WeekTrend.NO_DATA, bucket.trend) }
    }

    @Test
    fun `FR_UI_11_week_over_week reports UP when the current window heard more than the prior window`() {
        val now = mondayStart + 14 * dayMillis
        val session = SessionWindow(startedAtUtc = now - 14 * dayMillis, endedAtUtc = now, gaps = emptyList())
        // One transmission on "last week"'s Tuesday, two on "this week"'s Tuesday.
        val lastWeekTuesday = now - 14 * dayMillis + (tuesdayStart - mondayStart) + hour
        val thisWeekTuesday = now - 7 * dayMillis + (tuesdayStart - mondayStart) + hour

        val comparison = ActivityPatternMapper.buildWeekOverWeekComparison(
            sessions = listOf(session),
            matchingTransmissionTimestamps = listOf(lastWeekTuesday, thisWeekTuesday, thisWeekTuesday + 60_000L),
            nowMillis = now,
            zone = ZoneOffset.UTC,
        )

        val tuesday = comparison.single { it.dayOfWeek == DayOfWeek.TUESDAY }
        assertEquals(WeekTrend.UP, tuesday.trend)
        assertEquals(2, tuesday.currentHeardCount)
        assertEquals(1, tuesday.previousHeardCount)
    }
}
