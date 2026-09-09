package org.ort.app.ui.data

import org.junit.jupiter.api.Assertions.assertEquals
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
    fun `R_030 a station heard by voice only reads by voice match, a confirmed one reads overs`() {
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
        assertEquals("1 by voice match", inferredRow.countLabel)
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
        assertEquals("2 unidentified voices", view.stations.unidentifiedLabel)
    }

    @Test
    @Requirement("R-176")
    fun `R_176 the unidentified count reads voices per the board, singular for exactly one`() {
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
        assertEquals("2 unidentified voices", plural.stations.unidentifiedLabel)

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
        assertEquals("1 unidentified voice", singular.stations.unidentifiedLabel)
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
}
