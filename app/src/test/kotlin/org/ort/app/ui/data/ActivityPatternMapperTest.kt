package org.ort.app.ui.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.ZoneId
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

    // --- R-075: buildPattern buckets by the caller's zone, defaulting to UTC only for the copy
    // ReaderPolling (out of this package's ownership) still calls without one. ---

    @Test
    fun `R_075 buildPattern buckets by the zone the caller passes, not always UTC`() {
        // A 48h session guarantees every local hour-of-day is covered regardless of zone offset.
        // The one transmission, at epoch hour 0 (1970-01-01T00:00Z), is hour-of-day 0 in UTC but
        // hour-of-day 19 the day before in UTC-5 (America/New_York's January — no DST — offset).
        val session = SessionWindow(startedAtUtc = 0L, endedAtUtc = 48 * hour, gaps = emptyList())
        val newYork = ZoneId.of("America/New_York")

        val utcPattern = ActivityPatternMapper.buildPattern(
            sessions = listOf(session),
            matchingTransmissionTimestamps = listOf(0L),
            nowMillis = 48 * hour,
        )
        val localPattern = ActivityPatternMapper.buildPattern(
            sessions = listOf(session),
            matchingTransmissionTimestamps = listOf(0L),
            nowMillis = 48 * hour,
            zone = newYork,
        )

        assertEquals(HourActivityState.HEARD, utcPattern.single { it.hourOfDayUtc == 0 }.state)
        assertEquals(HourActivityState.HEARD, localPattern.single { it.hourOfDayUtc == 19 }.state)
        // The same real transmission never buckets as HEARD in both the UTC hour-of-day and a
        // different local one at once — the zone genuinely changed which bucket it fell in.
        assertEquals(HourActivityState.SILENT_WHILE_LISTENING, localPattern.single { it.hourOfDayUtc == 0 }.state)
        assertEquals(1, localPattern.single { it.hourOfDayUtc == 19 }.heardCount)
    }

    @Test
    fun `R_075 buildPattern defaults to UTC, matching ReaderPolling's unmodified three-arg call`() {
        val session = SessionWindow(startedAtUtc = 0L, endedAtUtc = hour, gaps = emptyList())

        val defaulted = ActivityPatternMapper.buildPattern(listOf(session), listOf(hour / 2), hour)
        val explicitUtc = ActivityPatternMapper.buildPattern(listOf(session), listOf(hour / 2), hour, ZoneId.of("UTC"))

        assertEquals(explicitUtc, defaulted)
    }

    // --- R-072: the hour x day grid ---

    @Test
    fun `R_072 buildHourByDayPattern folds a transmission onto its local day-of-week and hour`() {
        val session =
            SessionWindow(startedAtUtc = mondayStart, endedAtUtc = mondayStart + 7 * dayMillis, gaps = emptyList())

        val cells = ActivityPatternMapper.buildHourByDayPattern(
            sessions = listOf(session),
            matchingTransmissionTimestamps = listOf(tuesdayStart + hour * 19),
            nowMillis = mondayStart + 7 * dayMillis,
            zone = ZoneOffset.UTC,
        )

        assertEquals(24 * 7, cells.size)
        val cell = cells.single { it.dayOfWeek == DayOfWeek.TUESDAY && it.hourOfDay == 19 }
        assertEquals(HourActivityState.HEARD, cell.state)
        assertEquals(1, cell.heardCount)
    }

    @Test
    fun `R_072 buildHourByDayPattern cell never touched by any session is NOT_LISTENING`() {
        val session =
            SessionWindow(startedAtUtc = mondayStart, endedAtUtc = mondayStart + dayMillis, gaps = emptyList())

        val cells = ActivityPatternMapper.buildHourByDayPattern(
            sessions = listOf(session),
            matchingTransmissionTimestamps = emptyList(),
            nowMillis = mondayStart + 7 * dayMillis,
            zone = ZoneOffset.UTC,
        )

        val cell = cells.single { it.dayOfWeek == DayOfWeek.SATURDAY && it.hourOfDay == 12 }
        assertEquals(HourActivityState.NOT_LISTENING, cell.state)
    }

    // --- R-074: the 14-night sequence and the shared "busier than usual" test ---

    @Test
    fun `R_074 buildNightlySequence returns exactly the requested number of nights, oldest first`() {
        // 2am on "today" — well inside the calendar day, so the exclusive session-end boundary
        // does not accidentally exclude today itself from the listening window.
        val now = mondayStart + 20 * dayMillis + hour * 2
        val session = SessionWindow(startedAtUtc = now - 10 * dayMillis, endedAtUtc = now, gaps = emptyList())

        val nights = ActivityPatternMapper.buildNightlySequence(
            sessions = listOf(session),
            matchingTransmissionTimestamps = emptyList(),
            nowMillis = now,
            nights = 14,
            zone = ZoneOffset.UTC,
        )

        assertEquals(14, nights.size)
        // The most recent 10 of 14 nights were listened to; the oldest 4 were not.
        assertEquals(HourActivityState.NOT_LISTENING, nights.first().state)
        assertEquals(HourActivityState.SILENT_WHILE_LISTENING, nights.last().state)
    }

    @Test
    fun `R_074 a night with a matching transmission is HEARD with the real count`() {
        val now = mondayStart + 3 * dayMillis
        val session = SessionWindow(startedAtUtc = mondayStart, endedAtUtc = now + hour, gaps = emptyList())

        val nights = ActivityPatternMapper.buildNightlySequence(
            sessions = listOf(session),
            matchingTransmissionTimestamps = listOf(now, now + 60_000L, now + 120_000L),
            nowMillis = now,
            nights = 4,
            zone = ZoneOffset.UTC,
        )

        val tonight = nights.last()
        assertEquals(HourActivityState.HEARD, tonight.state)
        assertEquals(3, tonight.heardCount)
    }

    @Test
    fun `R_074 isBusierThanUsual is true when tonight is more than double the average of the other listened nights`() {
        val nights = listOf(
            NightActivity(epochDay = 1, state = HourActivityState.HEARD, heardCount = 4),
            NightActivity(epochDay = 2, state = HourActivityState.HEARD, heardCount = 6),
            NightActivity(epochDay = 3, state = HourActivityState.NOT_LISTENING, heardCount = 0),
            NightActivity(epochDay = 4, state = HourActivityState.HEARD, heardCount = 94),
        )

        assertTrue(NightlyDeparture.isBusierThanUsual(nights))
    }

    @Test
    fun `R_074 isBusierThanUsual is false when tonight was not listened to at all`() {
        val nights = listOf(
            NightActivity(epochDay = 1, state = HourActivityState.HEARD, heardCount = 4),
            NightActivity(epochDay = 2, state = HourActivityState.NOT_LISTENING, heardCount = 0),
        )

        assertFalse(NightlyDeparture.isBusierThanUsual(nights))
    }

    @Test
    fun `R_074 isBusierThanUsual is false with no other listened night, never a fabricated departure`() {
        val nights = listOf(
            NightActivity(epochDay = 1, state = HourActivityState.NOT_LISTENING, heardCount = 0),
            NightActivity(epochDay = 2, state = HourActivityState.HEARD, heardCount = 40),
        )

        assertFalse(NightlyDeparture.isBusierThanUsual(nights))
    }

    // --- R-072: "What this says" ---

    @Test
    fun `R_072 PatternInsights names an unknown day explicitly rather than folding it into quiet`() {
        val dayOfWeekPattern = DayOfWeek.entries.map { dow ->
            DayOfWeekActivityBucket(
                dayOfWeek = dow,
                state = if (dow == DayOfWeek.SATURDAY) HourActivityState.NOT_LISTENING else HourActivityState.HEARD,
                heardCount = if (dow == DayOfWeek.SATURDAY) 0 else 3,
            )
        }
        val hourPattern = (0 until 24).map {
            HourActivityBucket(hourOfDayUtc = it, state = HourActivityState.SILENT_WHILE_LISTENING, heardCount = 0)
        }

        val lines = PatternInsights.build(hourPattern, dayOfWeekPattern)

        assertTrue(lines.any { it.contains("Sat") && it.contains("unknown") })
    }

    @Test
    fun `R_072 PatternInsights names an unknown hour range explicitly`() {
        val hourPattern = (0 until 24).map { hod ->
            val state = if (hod in 16..17) HourActivityState.NOT_LISTENING else HourActivityState.SILENT_WHILE_LISTENING
            HourActivityBucket(hourOfDayUtc = hod, state = state, heardCount = 0)
        }

        val lines = PatternInsights.build(hourPattern, emptyList())

        assertTrue(lines.any { it.contains("16:00") && it.contains("unknown") })
    }

    @Test
    fun `R_072 PatternInsights names the peak hour from real heard counts`() {
        val hourPattern = (0 until 24).map { hod ->
            HourActivityBucket(
                hourOfDayUtc = hod,
                state = if (hod == 21) HourActivityState.HEARD else HourActivityState.SILENT_WHILE_LISTENING,
                heardCount = if (hod == 21) 12 else 0,
            )
        }

        val lines = PatternInsights.build(hourPattern, emptyList())

        assertTrue(lines.any { it.contains("21:00") })
    }
}
