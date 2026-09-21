package org.ort.app.ui.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.core.Attribution
import org.ort.testing.Requirement

/** `Main.dc.html`/`Now-First.dc.html`/`Now-Idle.dc.html`'s real facts, pure (ui-conformance-plan
 * WP4, R-030/R-033/R-036/R-037). */
class NowViewStateMapperTest {

    private fun detail(id: String, attribution: Attribution, startedAtUtcMillis: Long = 0L) = TransmissionDetail(
        id = id,
        startedAtUtcMillis = startedAtUtcMillis,
        frequencyHz = null,
        durationMs = 0L,
        signalStrength = null,
        attribution = attribution,
        currentTranscriptText = null,
        supersededTranscriptTexts = emptyList(),
        hasAudio = false,
    )

    private fun missingModel() = MissingModelFacts(
        title = "No transcription model installed",
        body = "Audio is being captured and kept.",
        actionLabel = "Install a model",
    )

    @Test
    @Requirement("FR-UI-9")
    fun `R_030 the session title reads Tonight while nothing has been heard yet, Overnight once it has`() {
        val empty = NowViewStateMapper.active(
            details = emptyList(),
            gaps = emptyList(),
            sessionStartedAtUtc = 0L,
            sessionEndedAtUtc = null,
            nowMillis = 0L,
            firstHeardStationIds = emptySet(),
            asrAvailable = true,
            missingModel = missingModel(),
            listeningOnLabel = null,
        )
        assertEquals("Tonight", empty.sessionTitle)

        val populated = NowViewStateMapper.active(
            details = listOf(detail("TX1", Attribution.unknown())),
            gaps = emptyList(),
            sessionStartedAtUtc = 0L,
            sessionEndedAtUtc = null,
            nowMillis = 0L,
            firstHeardStationIds = emptySet(),
            asrAvailable = true,
            missingModel = missingModel(),
            listeningOnLabel = null,
        )
        assertEquals("Overnight", populated.sessionTitle)
    }

    @Test
    @Requirement("R-913")
    fun `R_913 a short live session's chart hatches only real gaps, never every hour-of-day untouched`() {
        // R-913 (register, halt): `ActivityPatternMapper.buildPattern` folds onto 24 *hour-of-day*
        // buckets (correct for `Station`/`Frequencies`' multi-night patterns, wrong for one
        // session's own short span — the exact class of bug `DigestPolling.sessionCoverageBuckets`
        // (R-449) already fixed for DG04's own coverage bar) — before this fix, a 20-minute session
        // starting at epoch 0 folded onto hour-of-day 0 alone as real, leaving the other 23 (which
        // this one session obviously never had a chance to touch) hatched `NOT_LISTENING`, the same
        // "hatches almost the whole window" shape N01b's own report describes. This session records
        // no gap at all, so a chart honestly scoped to its own elapsed span must show nothing but
        // real listening.
        val view = NowViewStateMapper.active(
            details = emptyList(),
            gaps = emptyList(),
            sessionStartedAtUtc = 0L,
            sessionEndedAtUtc = null,
            nowMillis = 20 * 60_000L,
            firstHeardStationIds = emptySet(),
            asrAvailable = true,
            missingModel = missingModel(),
            listeningOnLabel = null,
        )
        assertEquals(1, view.activityPattern.size)
        assertTrue(view.activityPattern.none { it.state == HourActivityState.NOT_LISTENING })
    }

    @Test
    fun `R_033 the summary never fabricates a band count`() {
        val view = NowViewStateMapper.active(
            details = listOf(
                detail("TX1", Attribution.confirmed("W7NPC", 0.9)),
                detail("TX2", Attribution.confirmed("W7NPC", 0.9)),
                detail("TX3", Attribution.inferred("K7LWH", 0.8)),
            ),
            gaps = emptyList(),
            sessionStartedAtUtc = 0L,
            sessionEndedAtUtc = null,
            nowMillis = 0L,
            firstHeardStationIds = emptySet(),
            asrAvailable = true,
            missingModel = missingModel(),
            listeningOnLabel = null,
        )
        assertEquals("3 overs · 2 stations", view.summaryLabel)
        assertTrue(!view.summaryLabel.contains("band"))
    }

    @Test
    @Requirement("R-034")
    fun `R_034 the failed missing-model block appears only when ASR is unavailable`() {
        val unavailable = NowViewStateMapper.active(
            details = emptyList(),
            gaps = emptyList(),
            sessionStartedAtUtc = 0L,
            sessionEndedAtUtc = null,
            nowMillis = 0L,
            firstHeardStationIds = emptySet(),
            asrAvailable = false,
            missingModel = missingModel(),
            listeningOnLabel = null,
        )
        assertEquals(missingModel(), unavailable.missingModel)

        val available = NowViewStateMapper.active(
            details = emptyList(),
            gaps = emptyList(),
            sessionStartedAtUtc = 0L,
            sessionEndedAtUtc = null,
            nowMillis = 0L,
            firstHeardStationIds = emptySet(),
            asrAvailable = true,
            missingModel = missingModel(),
            listeningOnLabel = null,
        )
        assertNull(available.missingModel)
    }

    @Test
    fun `R_030 worth knowing names a first-time-heard station, an ambiguous count and the longest gap`() {
        val view = NowViewStateMapper.active(
            details = listOf(
                detail("TX1", Attribution.confirmed("WA7HJR", 0.9), startedAtUtcMillis = 2 * 3_600_000L + 17 * 60_000L),
                detail("TX2", Attribution.ambiguous()),
                detail("TX3", Attribution.unknown()),
            ),
            gaps = listOf(GapWindow(startedAt = 0L, endedAt = 38_000L)),
            sessionStartedAtUtc = 0L,
            sessionEndedAtUtc = null,
            nowMillis = 0L,
            firstHeardStationIds = setOf("WA7HJR"),
            asrAvailable = true,
            missingModel = missingModel(),
            listeningOnLabel = null,
        )

        assertEquals(3, view.worthKnowing.size)
        assertTrue(view.worthKnowing.any { it.headline.contains("WA7HJR") && it.headline.contains("first time") })
        assertTrue(view.worthKnowing.any { it.headline.contains("2 overs could not be attributed") })
        assertTrue(view.worthKnowing.any { it.headline.contains("A gap of 38s") })
    }

    @Test
    fun `R_037 worth knowing is an honest empty list, never a hardcoded developer note`() {
        val view = NowViewStateMapper.active(
            details = emptyList(),
            gaps = emptyList(),
            sessionStartedAtUtc = 0L,
            sessionEndedAtUtc = null,
            nowMillis = 0L,
            firstHeardStationIds = emptySet(),
            asrAvailable = true,
            missingModel = missingModel(),
            listeningOnLabel = null,
        )
        assertTrue(view.worthKnowing.isEmpty())
    }

    @Test
    fun `D45 an inferred-only station reads inferred, never a claimed voice match, a confirmed one reads overs`() {
        // This task (constitution I, D45): the old "1 by voice match" label named a mechanism
        // (`:identity`) that is an empty stub — the only production path to INFERRED is a human
        // correction. "Inferred" stays accurate whether the state came from a correction (today)
        // or a real voice match (once `:identity` ships).
        val view = NowViewStateMapper.active(
            details = listOf(
                detail("TX1", Attribution.confirmed("W7NPC", 0.9)),
                detail("TX2", Attribution.inferred("KJ7ABC", 0.8)),
            ),
            gaps = emptyList(),
            sessionStartedAtUtc = 0L,
            sessionEndedAtUtc = null,
            nowMillis = 0L,
            firstHeardStationIds = emptySet(),
            asrAvailable = true,
            missingModel = missingModel(),
            listeningOnLabel = null,
        )
        val confirmedRow = view.stations.rows.single { it.stationId == "W7NPC" }
        val inferredRow = view.stations.rows.single { it.stationId == "KJ7ABC" }
        assertEquals("1 over", confirmedRow.countLabel)
        assertEquals("1 inferred", inferredRow.countLabel)
        assertFalse(inferredRow.countLabel.contains("voice", ignoreCase = true))
    }

    @Test
    fun `R_030 unidentified overs are counted honestly, never claimed as distinct voices`() {
        val view = NowViewStateMapper.active(
            details = listOf(
                detail("TX1", Attribution.unknown()),
                detail("TX2", Attribution.ambiguous()),
                detail("TX3", Attribution.confirmed("W7NPC", 0.9)),
            ),
            gaps = emptyList(),
            sessionStartedAtUtc = 0L,
            sessionEndedAtUtc = null,
            nowMillis = 0L,
            firstHeardStationIds = emptySet(),
            asrAvailable = true,
            missingModel = missingModel(),
            listeningOnLabel = null,
        )
        assertEquals("2 overs not attributed to a station", view.stations.unidentifiedLabel)
        assertFalse(view.stations.unidentifiedLabel!!.contains("voice", ignoreCase = true))
    }

    @Test
    @Requirement("R-176")
    fun `R_176 the unidentified count reads overs per the board, singular for exactly one`() {
        val plural = NowViewStateMapper.active(
            details = listOf(detail("TX1", Attribution.unknown()), detail("TX2", Attribution.ambiguous())),
            gaps = emptyList(),
            sessionStartedAtUtc = 0L,
            sessionEndedAtUtc = null,
            nowMillis = 0L,
            firstHeardStationIds = emptySet(),
            asrAvailable = true,
            missingModel = missingModel(),
            listeningOnLabel = null,
        )
        assertEquals("2 overs not attributed to a station", plural.stations.unidentifiedLabel)

        val singular = NowViewStateMapper.active(
            details = listOf(detail("TX1", Attribution.unknown())),
            gaps = emptyList(),
            sessionStartedAtUtc = 0L,
            sessionEndedAtUtc = null,
            nowMillis = 0L,
            firstHeardStationIds = emptySet(),
            asrAvailable = true,
            missingModel = missingModel(),
            listeningOnLabel = null,
        )
        assertEquals("1 over not attributed to a station", singular.stations.unidentifiedLabel)
    }

    /**
     * R-1147 (register): [NowViewStateMapper]'s own prior doc comment on this label defended
     * "voices" as "the board's own literal wording... not a claim this code verifies" — but a
     * label that is not a claim the code can verify is exactly the thing constitution I forbids.
     * This count is `details.size - attributed.size`: every over with no `stationId`, `UNKNOWN`
     * and `AMBIGUOUS` alike (an `AMBIGUOUS` over may carry a heard candidate callsign that simply
     * was not confirmed), so "no callsign heard" would itself overclaim for that half of the
     * count — the only fact common to both is that neither carries a resolved station.
     */
    @Test
    fun `R_1147 the unidentified label never says voices, for any count`() {
        val view = NowViewStateMapper.active(
            details = listOf(detail("TX1", Attribution.unknown()), detail("TX2", Attribution.ambiguous())),
            gaps = emptyList(),
            sessionStartedAtUtc = 0L,
            sessionEndedAtUtc = null,
            nowMillis = 0L,
            firstHeardStationIds = emptySet(),
            asrAvailable = true,
            missingModel = missingModel(),
            listeningOnLabel = null,
        )
        assertFalse(view.stations.unidentifiedLabel!!.contains("voice", ignoreCase = true))
    }

    @Test
    @Requirement("R-174")
    fun `R_174 overCount reflects the real over total and is zero for a fresh first session`() {
        val fresh = NowViewStateMapper.active(
            details = emptyList(),
            gaps = emptyList(),
            sessionStartedAtUtc = 0L,
            sessionEndedAtUtc = null,
            nowMillis = 0L,
            firstHeardStationIds = emptySet(),
            asrAvailable = true,
            missingModel = missingModel(),
            listeningOnLabel = "listening on 145.230 and 146.960",
        )
        assertEquals(0, fresh.overCount)
        assertEquals("0 overs · listening on 145.230 and 146.960", fresh.summaryLabel)

        val populated = NowViewStateMapper.active(
            details = listOf(detail("TX1", Attribution.confirmed("W7NPC", 0.9))),
            gaps = emptyList(),
            sessionStartedAtUtc = 0L,
            sessionEndedAtUtc = null,
            nowMillis = 0L,
            firstHeardStationIds = emptySet(),
            asrAvailable = true,
            missingModel = missingModel(),
            listeningOnLabel = null,
        )
        assertEquals(1, populated.overCount)
    }

    @Test
    fun `legacyFrom builds an honest idle state when not capturing`() {
        val state = NowViewStateMapper.legacyFrom(overCount = 0, stationCount = 0, isCapturing = false)
        assertTrue(state is NowViewState.Idle)
    }

    @Test
    fun `legacyFrom builds an active state with the real counts when capturing`() {
        val state = NowViewStateMapper.legacyFrom(overCount = 412, stationCount = 19, isCapturing = true)
        assertTrue(state is NowViewState.Active)
        assertEquals("412 overs · 19 stations", (state as NowViewState.Active).summaryLabel)
    }

    @Test
    @Requirement("R-414")
    fun `R_414 the axis labels carry the real start-end minute, not always the top of the hour`() {
        // 23:32:00 UTC start, 07:00:15 UTC end — a real, non-zero start minute and a near-zero (but
        // not exactly zero-second) end, so neither end can pass by accident if the fix only ever
        // rounds down to the hour.
        val startedAt = 23L * 3_600_000L + 32L * 60_000L
        val endedAt = 31L * 3_600_000L + 15_000L // next day 07:00:15 UTC, as an absolute offset
        val view = NowViewStateMapper.active(
            details = listOf(detail("TX1", Attribution.confirmed("W7NPC", 0.9))),
            gaps = emptyList(),
            sessionStartedAtUtc = startedAt,
            sessionEndedAtUtc = endedAt,
            nowMillis = endedAt,
            firstHeardStationIds = emptySet(),
            asrAvailable = true,
            missingModel = missingModel(),
            listeningOnLabel = null,
        )
        assertEquals("23:32", view.axisStartLabel)
        assertEquals("07:00", view.axisEndLabel)
    }

    @Test
    @Requirement("R-414")
    fun `R_414 a short session's start and end genuinely differ, never both reading the same rounded hour`() {
        // `first-session`'s own shape: three minutes old. Rounding both ends down to the hour (the
        // pre-fix behaviour) made them read identically ("01:00"/"01:00"), as if nothing had
        // elapsed — the register's own "elapsed duration as a clock" symptom.
        val startedAt = 23L * 3_600_000L + 57L * 60_000L
        val nowMillis = startedAt + 3L * 60_000L
        val view = NowViewStateMapper.active(
            details = emptyList(),
            gaps = emptyList(),
            sessionStartedAtUtc = startedAt,
            sessionEndedAtUtc = null,
            nowMillis = nowMillis,
            firstHeardStationIds = emptySet(),
            asrAvailable = true,
            missingModel = missingModel(),
            listeningOnLabel = null,
        )
        assertEquals("23:57", view.axisStartLabel)
        assertEquals("00:00", view.axisEndLabel)
    }

    @Test
    @Requirement("R-418")
    fun `R_418 the header count is genuinely singular at one over and one station`() {
        val view = NowViewStateMapper.active(
            details = listOf(detail("TX1", Attribution.confirmed("W7NPC", 0.9))),
            gaps = emptyList(),
            sessionStartedAtUtc = 0L,
            sessionEndedAtUtc = null,
            nowMillis = 0L,
            firstHeardStationIds = emptySet(),
            asrAvailable = true,
            missingModel = missingModel(),
            listeningOnLabel = null,
        )
        assertEquals("1 over · 1 station", view.summaryLabel)
    }

    // -------------------------------------------------------------------------------------------
    // R-1041 (N01, `LogFilterOrigin.Now`): the chart's own tap needs the session's real start.
    // -------------------------------------------------------------------------------------------

    @Test
    @Requirement("R-1041")
    fun `R_1041 active carries the session's own real start, not a fabricated one`() {
        val view = NowViewStateMapper.active(
            details = emptyList(),
            gaps = emptyList(),
            sessionStartedAtUtc = 12_345L,
            sessionEndedAtUtc = null,
            nowMillis = 12_345L,
            firstHeardStationIds = emptySet(),
            asrAvailable = true,
            missingModel = missingModel(),
            listeningOnLabel = null,
        )
        assertEquals(12_345L, view.sessionStartedAtUtc)
    }

    // -------------------------------------------------------------------------------------------
    // R-1074 (register, halt, constitution I): the chart must never show more not-listening time
    // than the gaps actually contain. Before this fix, `active` bucketed the session into whole
    // *clock* hours (`ActivityPatternMapper.buildSessionElapsedPattern`) and hatched a bucket the
    // instant any real gap merely touched it — the same false picture R-1069 already removed from
    // `Session.dc.html`'s own coverage bar, by reusing `SessionCoverageMapper.buildSegments` and
    // `ActivityPatternChart`'s `segmentWeights`. This proves R-1074 gives `Now` the identical fix
    // rather than a third implementation, and that the R-1041 tap filter now opens each segment's
    // own real window instead of a fabricated hour.
    // -------------------------------------------------------------------------------------------

    @Test
    @Requirement("R-1074")
    fun `R_1074 a 22-minute gap in a three-hour session never renders more than 22 minutes not-listening`() {
        // The register's own repro (shared with R-1069's SessionCoverageMapperTest): a 180-minute
        // session, one real 22-minute gap starting 40 minutes in. The pre-fix whole-hour-bucket
        // code hatched two of three hours (120 minutes) as not-listening for this exact shape.
        val minute = 60_000L
        val sessionStart = 0L
        val gapStart = 40 * minute
        val gapEnd = gapStart + 22 * minute
        val sessionEnd = 180 * minute
        val view = NowViewStateMapper.active(
            details = listOf(
                detail("TX1", Attribution.confirmed("W7NPC", 0.9), startedAtUtcMillis = 10 * minute),
                detail("TX2", Attribution.confirmed("W7NPC", 0.9), startedAtUtcMillis = 150 * minute),
            ),
            gaps = listOf(GapWindow(startedAt = gapStart, endedAt = gapEnd)),
            sessionStartedAtUtc = sessionStart,
            sessionEndedAtUtc = sessionEnd,
            nowMillis = sessionEnd,
            firstHeardStationIds = emptySet(),
            asrAvailable = true,
            missingModel = missingModel(),
            listeningOnLabel = null,
        )

        val notListeningMillis = view.activityPattern
            .filter { it.state == HourActivityState.NOT_LISTENING }
            .sumOf { bucket ->
                val segment = bucket as org.ort.app.ui.digest.SessionCoverageSegment
                ((segment.fractionEnd - segment.fractionStart) * (sessionEnd - sessionStart)).toLong()
            }

        assertTrue(
            notListeningMillis <= 22 * minute + FRACTION_ROUND_TRIP_TOLERANCE_MILLIS,
            "expected at most the real 22-minute gap's own not-listening time, got ${notListeningMillis}ms " +
                "(the pre-fix whole-hour-bucket code would have produced up to 120 minutes for this exact case)",
        )
        assertTrue(
            notListeningMillis >= 22 * minute - FRACTION_ROUND_TRIP_TOLERANCE_MILLIS,
            "expected the real 22-minute gap's own not-listening time, not a shrunk one, got ${notListeningMillis}ms",
        )
    }

    @Test
    @Requirement("R-1074")
    fun `R_1074 activityPattern reuses SessionCoverageSegment, never re-deriving a whole-hour shape`() {
        val minute = 60_000L
        val view = NowViewStateMapper.active(
            details = emptyList(),
            gaps = listOf(GapWindow(startedAt = 40 * minute, endedAt = 62 * minute)),
            sessionStartedAtUtc = 0L,
            sessionEndedAtUtc = 180 * minute,
            nowMillis = 180 * minute,
            firstHeardStationIds = emptySet(),
            asrAvailable = true,
            missingModel = missingModel(),
            listeningOnLabel = null,
        )

        // Listening, gap, listening — three real segments, never 3 whole-hour buckets that happen
        // to share the same count by coincidence (the shape asserted below rules that out).
        assertEquals(3, view.activityPattern.size)
        assertTrue(view.activityPattern.all { it is org.ort.app.ui.digest.SessionCoverageSegment })
        assertEquals(3, view.activitySegmentWeights.size)
        assertEquals(3, view.activitySegmentWindows.size)
    }

    // -------------------------------------------------------------------------------------------
    // R-1041 (N01, `LogFilterOrigin.Now`): the chart bar's own tap, now opening each segment's own
    // real window (R-1074) rather than a fabricated whole clock hour (`hourFilterWindow`, removed).
    // -------------------------------------------------------------------------------------------

    @Test
    @Requirement("R-1041")
    fun `R_1041 activitySegmentWindows carries each segment's own real span, not a fabricated hour`() {
        val minute = 60_000L
        val sessionStart = 1_000L
        val gapStart = sessionStart + 40 * minute
        val gapEnd = gapStart + 22 * minute
        val sessionEnd = sessionStart + 180 * minute
        val transmissionAt = 10 * minute + sessionStart
        val view = NowViewStateMapper.active(
            details = listOf(detail("TX1", Attribution.confirmed("W7NPC", 0.9), startedAtUtcMillis = transmissionAt)),
            gaps = listOf(GapWindow(startedAt = gapStart, endedAt = gapEnd)),
            sessionStartedAtUtc = sessionStart,
            sessionEndedAtUtc = sessionEnd,
            nowMillis = sessionEnd,
            firstHeardStationIds = emptySet(),
            asrAvailable = true,
            missingModel = missingModel(),
            listeningOnLabel = null,
        )

        assertEquals(3, view.activitySegmentWindows.size)
        val (from0, to0) = view.activitySegmentWindows[0]
        // The real listening span before the gap: [sessionStart, gapStart) — never a whole clock
        // hour, which for this session (starting at 1_000L) would not even align to gapStart at all.
        assertEquals(sessionStart, from0)
        assertTrue(
            Math.abs(to0 - (gapStart - 1)) <= FRACTION_ROUND_TRIP_TOLERANCE_MILLIS,
            "expected the segment's own real end (~${gapStart - 1}), got $to0",
        )
        // R-1041's own point (raised again when R-1074 changed the tap contract from an hour to a
        // segment window): a tap that does not land on its own overs is useless — the window a real
        // tap on this bar would open must actually contain the transmission that made it HEARD.
        assertTrue(
            transmissionAt in from0..to0,
            "expected the tapped-over's own timestamp ($transmissionAt) inside the opened window " +
                "[$from0, $to0]",
        )
    }

    @Test
    @Requirement("R-1041")
    fun `R_1041 an active state with no real span carries no segment windows at all`() {
        val view = NowViewStateMapper.active(
            details = emptyList(),
            gaps = emptyList(),
            sessionStartedAtUtc = 0L,
            sessionEndedAtUtc = null,
            nowMillis = 0L,
            firstHeardStationIds = emptySet(),
            asrAvailable = true,
            missingModel = missingModel(),
            listeningOnLabel = null,
        )
        assertTrue(view.activitySegmentWindows.isEmpty())
    }

    private companion object {
        /**
         * [org.ort.app.ui.digest.SessionCoverageSegment]'s own fractions are `Float`, so turning
         * one back into an absolute millisecond (R-1074's `activitySegmentWindows`) carries a
         * sub-millisecond-per-hour rounding error inherent to that representation, not a bug of
         * this reconstruction — this floor is many orders of magnitude tighter than the up-to-an-
         * hour error the pre-fix whole-hour-bucket code produced, which is the only thing this
         * suite needs to discriminate.
         */
        const val FRACTION_ROUND_TRIP_TOLERANCE_MILLIS = 50L
    }
}
