package org.ort.app.ui.digest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.app.ui.data.GapWindow
import org.ort.app.ui.data.HourActivityState
import org.ort.app.ui.data.SessionWindow
import org.ort.testing.Requirement

/**
 * R-1069 (register, halt, constitution I): [SessionCoverageMapper.buildSegments] places and sizes
 * every gap segment from its own real start and duration on the session's own elapsed-time axis —
 * never quantized to a clock hour, the defect the register's own capture showed (a 22-minute gap in
 * a 3-hour session hatching two-thirds of the bar because `ActivityPatternMapper
 * .buildSessionElapsedPattern`'s whole-hour buckets each hatch unconditionally on any real overlap).
 *
 * Every fraction here is asserted to a tolerance far tighter than a pixel at any real screen width
 * (390dp at 3x density is ~1170px — [FRACTION_TOLERANCE] resolves to noise even there), so a test
 * passing here is real evidence the segment's own numbers are right, independent of how
 * [ActivityPatternChart][org.ort.app.ui.components.ActivityPatternChart] then turns a weight into
 * pixels.
 */
class SessionCoverageMapperTest {

    private val minute = 60_000L

    @Test
    @Requirement("R-1069")
    fun `R_1069 a single gap is placed and sized from its own real start and duration, not a whole hour`() {
        // The register's own repro, in relative terms: a 180-minute session, one 22-minute gap
        // starting 40 minutes in (03:47 on a 03:07-06:07 night) — expected start fraction 40/180,
        // width 22/180. The pre-fix code (whole-hour buckets) hatched buckets 0 and 1 of 3 entirely
        // (fractions 0 to 2/3) because the gap merely touched both of them.
        val sessionStart = 0L
        val gapStart = 40 * minute
        val gapEnd = gapStart + 22 * minute
        val sessionEnd = 180 * minute
        val window = SessionWindow(
            startedAtUtc = sessionStart,
            endedAtUtc = sessionEnd,
            gaps = listOf(GapWindow(startedAt = gapStart, endedAt = gapEnd)),
        )

        val segments = SessionCoverageMapper.buildSegments(
            window = window,
            matchingTransmissionTimestamps = emptyList(),
            nowMillis = sessionEnd,
        )

        assertEquals(3, segments.size) { "expected listening, gap, listening — got ${segments.size}: $segments" }
        val gapSegment = segments.single { it.state == HourActivityState.NOT_LISTENING }
        val expectedStart = 40f / 180f
        val expectedWidth = 22f / 180f
        assertTrue(
            abs(gapSegment.fractionStart - expectedStart) < FRACTION_TOLERANCE,
            "expected gap start fraction ~$expectedStart, got ${gapSegment.fractionStart}",
        )
        val actualWidth = gapSegment.fractionEnd - gapSegment.fractionStart
        assertTrue(
            abs(actualWidth - expectedWidth) < FRACTION_TOLERANCE,
            "expected gap width fraction ~$expectedWidth, got $actualWidth",
        )
        // The old bug's own shape, disproved directly: the hatched region must NOT extend to 2/3 of
        // the bar (fraction 0.667) the way the whole-hour-bucket code produced for this exact case.
        assertTrue(
            gapSegment.fractionEnd < 0.4f,
            "expected the gap to end well before two-thirds of the bar, got fractionEnd=${gapSegment.fractionEnd}",
        )
    }

    @Test
    @Requirement("R-1069")
    fun `R_1069 a session with no real gap at all is one single listening segment, never hatched`() {
        val window = SessionWindow(startedAtUtc = 0L, endedAtUtc = 3 * 3_600_000L, gaps = emptyList())

        val segments = SessionCoverageMapper.buildSegments(
            window = window,
            matchingTransmissionTimestamps = listOf(100_000L),
            nowMillis = 3 * 3_600_000L,
        )

        assertEquals(1, segments.size)
        assertEquals(HourActivityState.HEARD, segments.single().state)
        assertEquals(0f, segments.single().fractionStart)
        assertEquals(1f, segments.single().fractionEnd)
    }

    @Test
    @Requirement("R-1069")
    fun `R_1069 multiple gaps each get their own correctly placed segment`() {
        // A 4-hour (240-minute) session with two short, well-separated gaps: 10-20 min and
        // 200-210 min. Expect five segments: listening, gap, listening, gap, listening.
        val sessionEnd = 240 * minute
        val window = SessionWindow(
            startedAtUtc = 0L,
            endedAtUtc = sessionEnd,
            gaps = listOf(
                GapWindow(startedAt = 10 * minute, endedAt = 20 * minute),
                GapWindow(startedAt = 200 * minute, endedAt = 210 * minute),
            ),
        )

        val segments = SessionCoverageMapper.buildSegments(
            window = window,
            matchingTransmissionTimestamps = emptyList(),
            nowMillis = sessionEnd,
        )

        assertEquals(5, segments.size) { "got $segments" }
        val states = segments.map { it.state }
        assertEquals(
            listOf(
                HourActivityState.SILENT_WHILE_LISTENING,
                HourActivityState.NOT_LISTENING,
                HourActivityState.SILENT_WHILE_LISTENING,
                HourActivityState.NOT_LISTENING,
                HourActivityState.SILENT_WHILE_LISTENING,
            ),
            states,
        )
        val firstGap = segments[1]
        assertTrue(abs(firstGap.fractionStart - 10f / 240f) < FRACTION_TOLERANCE)
        assertTrue(abs(firstGap.fractionEnd - 20f / 240f) < FRACTION_TOLERANCE)
        val secondGap = segments[3]
        assertTrue(abs(secondGap.fractionStart - 200f / 240f) < FRACTION_TOLERANCE)
        assertTrue(abs(secondGap.fractionEnd - 210f / 240f) < FRACTION_TOLERANCE)
    }

    @Test
    @Requirement("R-1069")
    fun `R_1069 a gap at the very start produces no zero-width leading segment`() {
        val sessionEnd = 60 * minute
        val window = SessionWindow(
            startedAtUtc = 0L,
            endedAtUtc = sessionEnd,
            gaps = listOf(GapWindow(startedAt = 0L, endedAt = 5 * minute)),
        )

        val segments = SessionCoverageMapper.buildSegments(
            window = window,
            matchingTransmissionTimestamps = emptyList(),
            nowMillis = sessionEnd,
        )

        assertEquals(2, segments.size) { "expected gap then listening, no leading zero-width segment — got $segments" }
        assertEquals(HourActivityState.NOT_LISTENING, segments[0].state)
        assertEquals(0f, segments[0].fractionStart)
        assertTrue(abs(segments[0].fractionEnd - 5f / 60f) < FRACTION_TOLERANCE)
        assertEquals(HourActivityState.SILENT_WHILE_LISTENING, segments[1].state)
    }

    @Test
    @Requirement("R-1069")
    fun `R_1069 a gap running to the session's own end produces no trailing zero-width segment`() {
        val sessionEnd = 60 * minute
        val window = SessionWindow(
            startedAtUtc = 0L,
            endedAtUtc = sessionEnd,
            gaps = listOf(GapWindow(startedAt = 45 * minute, endedAt = sessionEnd)),
        )

        val segments = SessionCoverageMapper.buildSegments(
            window = window,
            matchingTransmissionTimestamps = emptyList(),
            nowMillis = sessionEnd,
        )

        assertEquals(2, segments.size) { "expected listening then gap, no trailing zero-width segment — got $segments" }
        assertEquals(HourActivityState.NOT_LISTENING, segments[1].state)
        assertTrue(abs(segments[1].fractionStart - 45f / 60f) < FRACTION_TOLERANCE)
        assertEquals(1f, segments[1].fractionEnd)
    }

    @Test
    @Requirement("R-1069")
    fun `R_1069 a still-open gap (never recovered) is not-listening through nowMillis, not forever`() {
        val window = SessionWindow(
            startedAtUtc = 0L,
            endedAtUtc = null,
            gaps = listOf(GapWindow(startedAt = 10 * minute, endedAt = null)),
        )

        val segments = SessionCoverageMapper.buildSegments(
            window = window,
            matchingTransmissionTimestamps = emptyList(),
            nowMillis = 20 * minute,
        )

        assertEquals(2, segments.size)
        assertEquals(HourActivityState.NOT_LISTENING, segments[1].state)
        assertEquals(1f, segments[1].fractionEnd)
    }

    @Test
    @Requirement("R-1069")
    fun `R_1069 the chart weights are each segment's own real fraction of the session's span`() {
        val sessionEnd = 180 * minute
        val window = SessionWindow(
            startedAtUtc = 0L,
            endedAtUtc = sessionEnd,
            gaps = listOf(GapWindow(startedAt = 40 * minute, endedAt = 62 * minute)),
        )

        val segments = SessionCoverageMapper.buildSegments(window, emptyList(), sessionEnd)
        val weights = segments.asChartWeights()

        assertEquals(segments.size, weights.size)
        weights.forEachIndexed { index, weight ->
            val expected = segments[index].fractionEnd - segments[index].fractionStart
            assertTrue(abs(weight - expected) < FRACTION_TOLERANCE, "segment $index: expected $expected, got $weight")
        }
    }

    private fun abs(value: Float): Float = if (value < 0f) -value else value

    private companion object {
        /** Far tighter than a pixel at any real screen width (see this file's own top kdoc). */
        const val FRACTION_TOLERANCE = 0.0005f
    }
}
