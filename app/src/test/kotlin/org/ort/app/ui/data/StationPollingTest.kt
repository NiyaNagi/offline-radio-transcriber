package org.ort.app.ui.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.debug.Scenarios
import org.ort.core.AttributionState
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.dao.StationIdentityDao
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.StationEntity
import org.ort.data.entity.TransmissionEntity
import org.ort.data.entity.VoiceprintEntity
import org.robolectric.RobolectricTestRunner

/**
 * R-070 through R-075 (ui-conformance-plan WP8): [StationPolling]/[FrequencyPolling] against a
 * real (file-backed) `:data` database, the same pattern `ReaderPollingTest` (WP4's file) uses —
 * proves the real DAOs feed the new aggregations correctly, not a fake standing in for `:data`.
 */
@RunWith(RobolectricTestRunner::class)
class StationPollingTest {

    private lateinit var db: OrtDatabase
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun openDatabase() {
        db = OrtDatabase.create(context)
    }

    private fun session(id: String, startedAt: Long, endedAt: Long? = null) = SessionEntity(
        id = id,
        startedAt = startedAt,
        endedAt = endedAt,
        profileId = null,
        deviceTier = null,
        appVersion = "test",
        terminationReason = null,
        sourceId = null,
        schemaVersion = OrtDatabase.SCHEMA_VERSION,
    )

    private fun station(id: String, userName: String? = null, firstHeardAt: Long? = 0L) = StationEntity(
        id = id,
        callsign = id,
        firstHeardAt = firstHeardAt,
        lastHeardAt = firstHeardAt,
        transmissionCount = 1,
        isUserPinned = false,
        notes = null,
        userName = userName,
        frequenciesHeard = null,
        activityByHourDow = null,
        potaRefs = null,
        spokenGrids = null,
        ituRegionFromPrefix = null,
        overCountsByAttributionState = null,
    )

    /** Bundles the attribution columns tests care about, keeping [tx]'s own parameter count down. */
    private data class FixtureAttribution(
        val stationId: String? = null,
        val state: AttributionState = AttributionState.UNKNOWN,
        val confidence: Double? = null,
        val corrected: Boolean = false,
        val voiceprintId: String? = null,
    )

    private fun tx(
        id: String,
        sessionId: String,
        startedAtUtc: Long,
        frequencyHz: Long? = 146_960_000L,
        mode: String? = "FM",
        attribution: FixtureAttribution = FixtureAttribution(),
    ) = TransmissionEntity(
        id = id,
        sessionId = sessionId,
        threadId = null,
        startedAtUtc = startedAtUtc,
        endedAtUtc = startedAtUtc + 1_000L,
        durationMs = 4_200L,
        audioFormat = "flac/16k/mono",
        preRollMs = 200,
        postRollMs = 200,
        frequencyHz = frequencyHz,
        frequencyProvenance = "measured",
        mode = mode,
        signalStrength = 7.0,
        channelName = null,
        voiceprintId = attribution.voiceprintId,
        attributionState = attribution.state,
        stationId = attribution.stationId,
        attributionConfidence = attribution.confidence,
        attributionSourceTransmissionId = null,
        corrected = attribution.corrected,
        processingState = TransmissionState.CAPTURED,
        rejectionReason = null,
        samplePosition = startedAtUtc,
        monotonicStartNanos = 0L,
        utcOffsetMinutes = 0,
        calibrationId = null,
        executionProvider = null,
    )

    @Test
    fun `R_070 listStations reports the dominant tonight attribution and an honest count context`(): Unit = runTest {
        db.sessionDao().insert(session("S1", startedAt = 0L))
        db.catalogDao().insert(station("W7NPC"))
        db.transmissionDao().insert(
            tx(
                "TX1",
                "S1",
                0L,
                attribution = FixtureAttribution("W7NPC", AttributionState.CONFIRMED, 0.9),
            ),
        )
        db.transmissionDao().insert(
            tx(
                "TX2",
                "S1",
                1_000L,
                attribution = FixtureAttribution("W7NPC", AttributionState.INFERRED, 0.7),
            ),
        )

        val rows = StationPolling.listStations(context)

        val row = rows.single { it.stationId == "W7NPC" }
        assertEquals(AttributionState.CONFIRMED, row.attribution.state)
        assertEquals("1 by voice match · 1 heard", row.countContext)
        assertTrue(row.heardTonight)
    }

    @Test
    fun `R_070 a station first heard tonight gets the NEW badge`(): Unit = runTest {
        db.sessionDao().insert(session("S1", startedAt = 10_000L))
        db.catalogDao().insert(station("WA7HJR", firstHeardAt = 10_500L))
        db.transmissionDao().insert(
            tx(
                "TX1",
                "S1",
                10_500L,
                attribution = FixtureAttribution("WA7HJR", AttributionState.CONFIRMED, 0.9),
            ),
        )

        val rows = StationPolling.listStations(context)

        assertEquals(StationListBadge.NEW, rows.single { it.stationId == "WA7HJR" }.badge)
    }

    @Test
    fun `R_070 unidentifiedSummary counts distinct voiceprints where clustering wrote one`(): Unit = runTest {
        db.sessionDao().insert(session("S1", startedAt = 0L))
        val unknown = { voiceprintId: String? ->
            FixtureAttribution(state = AttributionState.UNKNOWN, voiceprintId = voiceprintId)
        }
        db.transmissionDao().insert(tx("TX1", "S1", 0L, attribution = unknown("V1")))
        db.transmissionDao().insert(tx("TX2", "S1", 1_000L, attribution = unknown("V1")))
        db.transmissionDao().insert(tx("TX3", "S1", 2_000L, attribution = unknown("V2")))

        val summary = StationPolling.unidentifiedSummary(context, tonightOnly = true)

        assertEquals(2, summary?.voiceCount)
        assertEquals(3, summary?.overCount)
    }

    @Test
    fun `R_070 unidentifiedSummary never fabricates a voice count with no clustering data`(): Unit = runTest {
        db.sessionDao().insert(session("S1", startedAt = 0L))
        db.transmissionDao().insert(
            tx("TX1", "S1", 0L, attribution = FixtureAttribution(state = AttributionState.UNKNOWN)),
        )

        val summary = StationPolling.unidentifiedSummary(context, tonightOnly = true)

        assertNull(summary?.voiceCount)
        assertEquals(1, summary?.overCount)
    }

    @Test
    fun `R_071 stationDetail reports the real confirmed-inferred-corrected split`(): Unit = runTest {
        db.sessionDao().insert(session("S1", startedAt = 0L))
        db.catalogDao().insert(station("W7NPC"))
        db.transmissionDao().insert(
            tx(
                "TX1",
                "S1",
                0L,
                attribution = FixtureAttribution("W7NPC", AttributionState.CONFIRMED, 0.9),
            ),
        )
        db.transmissionDao().insert(
            tx(
                "TX2",
                "S1",
                1_000L,
                attribution = FixtureAttribution("W7NPC", AttributionState.INFERRED, 0.7, corrected = true),
            ),
        )

        val detail = StationPolling.stationDetail(context, "W7NPC", nowMillis = 2_000L)

        assertEquals(1, detail.confirmedCount)
        assertEquals(1, detail.inferredCount)
        assertEquals(1, detail.correctedCount)
    }

    @Test
    fun `R_074 whatItIs and busierThanUsual reflect real per-frequency data`(): Unit = runTest {
        val dayMillis = 86_400_000L
        // Noon (not midnight) UTC on "day 20" — a wide margin either side of the local calendar
        // day boundary, since FrequencyPolling always buckets nights in ZoneId.systemDefault()
        // (R-075) and this test does not control what zone the test JVM runs under.
        val now = 20 * dayMillis + 12 * 3_600_000L
        db.sessionDao().insert(session("S0", startedAt = now - dayMillis, endedAt = now - dayMillis + 3_600_000L))
        db.sessionDao().insert(session("S1", startedAt = now, endedAt = now + 3_600_000L))
        db.transmissionDao().insert(tx("TXusual", "S0", now - dayMillis, mode = "FM"))
        repeat(20) { i ->
            db.transmissionDao().insert(tx("TXtonight$i", "S1", now + i * 1_000L, mode = "FM"))
        }

        val frequencies = FrequencyPolling.listFrequencies(context, nowMillis = now)

        val row = frequencies.single { it.frequencyHz == 146_960_000L }
        assertEquals("2 m · FM", row.whatItIs)
        assertTrue(row.busierThanUsual)
    }

    private fun voiceprint(id: String, boundStationId: String?, memberCount: Int) = VoiceprintEntity(
        id = id,
        embedding = ByteArray(0),
        memberCount = memberCount,
        centroidUpdatedAt = null,
        boundStationId = boundStationId,
        bindingConfidence = null,
        lastConfirmedAt = null,
        isEnrolled = false,
        enrolmentObservationCount = 0,
        enrolmentSessionIds = null,
        enrolledAt = null,
        lastMatchedAt = null,
        bindingSource = null,
        embeddingModelId = null,
        embeddingModelVersion = null,
    )

    @Test
    fun `R_206_a_station_heard_only_on_a_prior_night_still_shows_its_real_dominant_state`(): Unit = runTest {
        // The real bug, reproduced exactly: WA7HJR has 10/10 CONFIRMED overs, all from a session
        // that is NOT the most recent one — the "All time" list (the default view) must still show
        // its real state, not fall back to Unknown just because it was not heard tonight.
        db.sessionDao().insert(session("S_OLD", startedAt = 0L))
        db.sessionDao().insert(session("S_TONIGHT", startedAt = 100_000L))
        db.catalogDao().insert(station("WA7HJR"))
        repeat(10) { i ->
            db.transmissionDao().insert(
                tx(
                    "TX$i",
                    "S_OLD",
                    i * 1_000L,
                    attribution = FixtureAttribution("WA7HJR", AttributionState.CONFIRMED, 0.9),
                ),
            )
        }

        val rows = StationPolling.listStations(context)

        val row = rows.single { it.stationId == "WA7HJR" }
        assertEquals(AttributionState.CONFIRMED, row.attribution.state)
        assertTrue("must not fall back to Unknown", row.attribution.state != AttributionState.UNKNOWN)
        assertTrue("this station was not heard in the latest session", !row.heardTonight)
    }

    @Test
    fun `R_213_the_voiceprint_total_equals_the_confirmed_plus_inferred_breakdown`(): Unit = runTest {
        // The real WA7HJR-shaped bug: real CONFIRMED/INFERRED overs exist, but the bound
        // voiceprint's own `memberCount` is stale (0) — the clustering pipeline lagging or never
        // having run is a real, separate fact from the attribution pipeline's own resolved count.
        db.sessionDao().insert(session("S1", startedAt = 0L))
        db.catalogDao().insert(station("WA7HJR"))
        db.catalogDao().insert(voiceprint("V1", boundStationId = "WA7HJR", memberCount = 0))
        db.transmissionDao().insert(
            tx(
                "TX1",
                "S1",
                0L,
                attribution = FixtureAttribution("WA7HJR", AttributionState.CONFIRMED, 0.9, voiceprintId = "V1"),
            ),
        )
        db.transmissionDao().insert(
            tx(
                "TX2",
                "S1",
                1_000L,
                attribution = FixtureAttribution("WA7HJR", AttributionState.INFERRED, 0.7, voiceprintId = "V1"),
            ),
        )

        val identity = StationPolling.stationIdentity(context, "WA7HJR")

        assertEquals(1, identity.voice.confirmedCount)
        assertEquals(1, identity.voice.inferredCount)
        assertEquals(2, identity.voice.clusterOverCount)
    }

    @Test
    fun `R_073_rename_persists_and_the_previous_name_stays_reachable`(): Unit = runTest {
        db.catalogDao().insert(station("N7XYZ"))

        StationPolling.renameStation(context, "N7XYZ", "Dave")
        StationPolling.renameStation(context, "N7XYZ", "David")

        // The identity screen (and the Stations list, via the same read path) only ever shows
        // the current name...
        val identity = StationPolling.stationIdentity(context, "N7XYZ")
        assertEquals("David", identity.givenByYou.name)
        val row = StationPolling.listStations(context).single { it.stationId == "N7XYZ" }
        assertEquals("David", row.givenName)

        // ...but the name it replaced (constitution III) stays reachable in the DAO's own history,
        // oldest first, exactly the shape StationIdentityDaoTest proves at the `:data` layer.
        val history = db.stationIdentityDao().stationIdentityHistoryFor("N7XYZ")
            .filter { it.field == StationIdentityDao.FIELD_NAME }
        assertEquals(listOf(null, "Dave"), history.map { it.previousValue })
        assertEquals(listOf("Dave", "David"), history.map { it.newValue })
    }

    @Test
    fun `R_073_split_moves_the_chosen_overs_into_a_new_voiceprint`(): Unit = runTest {
        db.sessionDao().insert(session("S1", startedAt = 0L))
        db.catalogDao().insert(station("N7DAVE"))
        db.catalogDao().insert(voiceprint("V1", boundStationId = "N7DAVE", memberCount = 2))
        db.transmissionDao().insert(
            tx(
                "TX1",
                "S1",
                0L,
                attribution = FixtureAttribution("N7DAVE", AttributionState.CONFIRMED, 0.9, voiceprintId = "V1"),
            ),
        )
        db.transmissionDao().insert(
            tx(
                "TX2",
                "S1",
                1_000L,
                attribution = FixtureAttribution("N7DAVE", AttributionState.INFERRED, 0.7, voiceprintId = "V1"),
            ),
        )

        val candidates = StationPolling.voiceSplitCandidates(context, "N7DAVE")!!
        assertEquals(setOf("TX1", "TX2"), candidates.overs.map { it.transmissionId }.toSet())
        assertTrue(candidates.overs.single { it.transmissionId == "TX1" }.isAnchor)
        assertTrue(!candidates.overs.single { it.transmissionId == "TX2" }.isAnchor)

        val refreshed = StationPolling.splitVoiceprint(context, "N7DAVE", candidates.fromVoiceprintId, listOf("TX2"))

        // The identity screen reflects the smaller remaining cluster (constitution III's "nothing
        // deleted" cuts the other way here too: TX2 did not vanish, it moved).
        assertEquals(1, refreshed.voice.clusterOverCount)

        val tx2 = db.transmissionDao().getById("TX2")!!
        assertEquals(AttributionState.UNKNOWN, tx2.attributionState)
        assertNull(tx2.stationId)
        assertTrue(tx2.corrected)
        assertTrue(tx2.voiceprintId != "V1")

        val tx1 = db.transmissionDao().getById("TX1")!!
        assertEquals("N7DAVE", tx1.stationId)
        assertEquals("V1", tx1.voiceprintId)

        // A CORRECTED badge belongs where the DAO actually recorded the correction: on the moved
        // over itself, not manufactured for the originating station's own screens (TX2 no longer
        // belongs to N7DAVE at all once split, so there is nowhere honest on *this* station's
        // screens to show it — see this package's report).
        val corrections = db.correctionDao().correctionsFor("TX2")
        assertEquals(1, corrections.size)
        assertEquals("N7DAVE", corrections.single().previousValue)
        assertEquals(StationIdentityDao.FIELD_VOICEPRINT_SPLIT, corrections.single().field)
    }

    @Test
    fun `R_216_R_074 the frequency-change scenario makes FQ03 reachable with a real busier-than-usual cause`(): Unit =
        runTest {
            Scenarios.load(context, "frequency-change")

            val frequencies = FrequencyPolling.listFrequencies(context)
            val row = frequencies.single { it.frequencyHz == 145_230_000L }
            assertTrue("frequency-change must seed a real busier-than-usual night", row.busierThanUsual)

            val change = FrequencyPolling.frequencyChange(context, 145_230_000L)
            assertTrue("expected at least one real cause", change.causes.isNotEmpty())
            assertTrue(
                "expected a first-time-heard cause",
                change.causes.any { !it.isUnidentified && it.label.contains("first time heard") },
            )
            assertTrue(
                "expected an unidentified-voices cause",
                change.causes.any { it.isUnidentified },
            )
        }
}
