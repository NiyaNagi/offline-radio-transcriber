package org.ort.app.ui.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * FR-UI-12, the requirement build-plan P17 calls out by name as "the one that matters": a pattern
 * display must distinguish **"not heard" from "not listening"**. An hour with no matching
 * transmission looks identical either way unless [org.ort.data.entity.CaptureGapEntity] rows and
 * the session's own start/end are consulted — presenting a not-listening hour as though the
 * frequency/station was simply quiet is a fabricated absence (constitution I). The three states
 * are structural ([HourActivityState], a closed enum), never a boolean with a footnote.
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
    fun `FR_UI_12 an hour covered entirely by a capture gap is NOT_LISTENING, never presented as silence`() {
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
}
