package org.ort.pipeline.passb

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.data.OrtDatabase
import org.ort.data.entity.StationEntity
import org.ort.data.entity.ThreadEntity
import org.ort.data.entity.ThreadKind
import org.ort.data.entity.ThreadKindSource
import org.ort.data.entity.WorkQueueState
import org.ort.core.PassId
import org.ort.data.entity.WorkQueueItemEntity
import org.ort.pipeline.PipelineTestFixtures
import org.robolectric.RobolectricTestRunner

/**
 * Register R-1124: [DataRankingContextSource] in isolation — the real `:data` reads behind
 * [PassBFactoryCalibrationTest]'s end-to-end proof, exercised directly so each input/edge case is
 * pinned without running a full Pass B decode for every case.
 */
@RunWith(RobolectricTestRunner::class)
public class DataRankingContextSourceTest {

    private lateinit var db: OrtDatabase

    @Before
    public fun openDatabase() {
        db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
    }

    private fun item(transmissionId: String) = WorkQueueItemEntity(
        transmissionId = transmissionId,
        pass = PassId.B_OFFLINE,
        state = WorkQueueState.LEASED,
        priority = 0,
        enqueuedAt = 0L,
    )

    private fun station(
        id: String,
        lastHeardAt: Long? = null,
    ) = StationEntity(
        id = id,
        callsign = id,
        firstHeardAt = lastHeardAt,
        lastHeardAt = lastHeardAt,
        transmissionCount = 1,
        isUserPinned = false,
        notes = null,
        userName = null,
        frequenciesHeard = null,
        activityByHourDow = null,
        potaRefs = null,
        spokenGrids = null,
        ituRegionFromPrefix = null,
        overCountsByAttributionState = null,
    )

    @Test
    fun `R_1124 an empty candidate set returns the cold default without touching the database`() = runTest {
        db.sessionDao().insert(PipelineTestFixtures.session())
        db.transmissionDao().insert(PipelineTestFixtures.transmission("TX1"))
        val source = DataRankingContextSource(db)

        val context = source.forCandidates(item("TX1"), emptySet())

        assertNull(context.databaseHits)
        assertNull(context.recency)
        assertNull(context.conversation)
    }

    @Test
    fun `R_1124 database presence is scoped to exactly the candidates asked about`() = runTest {
        db.sessionDao().insert(PipelineTestFixtures.session())
        db.transmissionDao().insert(PipelineTestFixtures.transmission("TX1"))
        db.catalogDao().insert(station("K7ABC"))
        val source = DataRankingContextSource(db)

        val context = source.forCandidates(item("TX1"), setOf("K7ABC", "W7XYZ"))

        assertEquals(setOf("K7ABC"), context.databaseHits)
    }

    @Test
    fun `R_1124 recency omits a candidate that exists but was never heard, without being cold`() = runTest {
        db.sessionDao().insert(PipelineTestFixtures.session())
        db.transmissionDao().insert(PipelineTestFixtures.transmission("TX1"))
        db.catalogDao().insert(station("K7ABC", lastHeardAt = null))
        val source = DataRankingContextSource(db)

        val context = source.forCandidates(item("TX1"), setOf("K7ABC"))

        assertTrue("recency itself must be non-null (a real, warm subsystem answered)", context.recency != null)
        assertFalse("a station that exists but was never heard must not appear in the map", "K7ABC" in context.recency!!)
    }

    @Test
    fun `R_1124 recency reports a positive secondsSinceLastHeard for a recently-heard candidate`() = runTest {
        db.sessionDao().insert(PipelineTestFixtures.session())
        db.transmissionDao().insert(PipelineTestFixtures.transmission("TX1"))
        val heardAt = 1_000_000L
        db.catalogDao().insert(station("K7ABC", lastHeardAt = heardAt))
        val source = DataRankingContextSource(db, clockMillis = { heardAt + 60_000L })

        val context = source.forCandidates(item("TX1"), setOf("K7ABC"))

        assertEquals(60L, context.recency!!.getValue("K7ABC").secondsSinceLastHeard)
    }

    @Test
    fun `R_1124 conversation context is real, not cold, even with no prior thread at all`() = runTest {
        db.sessionDao().insert(PipelineTestFixtures.session())
        db.transmissionDao().insert(PipelineTestFixtures.transmission("TX1"))
        val source = DataRankingContextSource(db)

        val context = source.forCandidates(item("TX1"), setOf("K7ABC"))

        assertTrue(context.conversation != null)
        assertFalse(context.conversation!!.otherStationIdentified)
    }

    @Test
    fun `R_1124 conversation context reports the already-identified participant of the continued thread`() = runTest {
        db.sessionDao().insert(PipelineTestFixtures.session())
        db.transmissionDao().insert(
            PipelineTestFixtures.transmission("TX-PRIOR").copy(
                frequencyHz = 146_520_000L,
                threadId = "THREAD-1",
                samplePosition = 0L,
            ),
        )
        db.catalogDao().insert(
            ThreadEntity(
                id = "THREAD-1",
                sessionId = "SESSION01",
                startedAt = 0L,
                endedAt = 1_000L,
                frequencyHz = 146_520_000L,
                transmissionCount = 1,
                participantStationIds = listOf("K7XYZ"),
                digestText = null,
                kind = ThreadKind.QSO,
                kindSource = ThreadKindSource.DETECTED,
                participantOrder = listOf("K7XYZ"),
            ),
        )
        db.transmissionDao().insert(
            PipelineTestFixtures.transmission("TX-CURRENT").copy(frequencyHz = 146_520_000L, samplePosition = 1_000L),
        )
        val source = DataRankingContextSource(db)

        val context = source.forCandidates(item("TX-CURRENT"), setOf("K7ABC"))

        assertTrue(context.conversation!!.otherStationIdentified)
        assertEquals("K7XYZ", context.conversation!!.otherStationCallsign)
    }

    @Test
    fun `R_1124 repeater, propagation, geographicDistanceKm and myStations stay honestly null`() = runTest {
        db.sessionDao().insert(PipelineTestFixtures.session())
        db.transmissionDao().insert(PipelineTestFixtures.transmission("TX1"))
        db.catalogDao().insert(station("K7ABC", lastHeardAt = System.currentTimeMillis()))
        val source = DataRankingContextSource(db)

        val context = source.forCandidates(item("TX1"), setOf("K7ABC"))

        assertNull("no Rig Module repeater roster exists on device -- must not be guessed", context.repeater)
        assertNull("no operator/candidate centroid distance can be computed -- must not be guessed", context.propagation)
        assertNull(
            "a non-null function that always returns null would look wired while contributing " +
                "nothing -- the exact defect this row fixes, not repeats",
            context.geographicDistanceKm,
        )
        assertNull("no my-stations list exists anywhere in the app yet -- must not be guessed", context.myStations)
    }
}
