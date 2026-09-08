package org.ort.app.ui.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.AttributionState
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.StationEntity
import org.ort.data.entity.TransmissionEntity
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
}
