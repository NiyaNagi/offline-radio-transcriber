package org.ort.data.dao

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.data.OrtDatabase
import org.ort.data.TestFixtures
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * Build-plan P17's read-only aggregate queries (FR-UI-9, FR-UI-10) — a new DAO, not an edit to
 * [TransmissionDao] or [org.ort.data.dao.CatalogDao] (kept conflict-free with the concurrent P15
 * session). No schema change: every query reads columns the existing entities already declare.
 */
@RunWith(RobolectricTestRunner::class)
public class ActivityDaoTest {

    private lateinit var db: OrtDatabase

    @Before
    public fun openDatabase() {
        db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
    }

    @Test
    @Requirement("FR-UI-9")
    public fun transmissionsForStation_returns_every_transmission_across_sessions(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session("S1"))
        db.sessionDao().insert(TestFixtures.session("S2"))
        db.transmissionDao().insert(
            TestFixtures.transmission("TX1", sessionId = "S1", stationId = "W7NPC", startedAtUtc = 1L),
        )
        db.transmissionDao().insert(
            TestFixtures.transmission("TX2", sessionId = "S2", stationId = "W7NPC", startedAtUtc = 2L),
        )
        db.transmissionDao().insert(
            TestFixtures.transmission("TX3", sessionId = "S1", stationId = "K7LWH", startedAtUtc = 3L),
        )

        val heard = db.activityDao().transmissionsForStation("W7NPC")

        assertEquals(listOf("TX1", "TX2"), heard.map { it.id })
    }

    @Test
    @Requirement("FR-UI-10")
    public fun transmissionsForFrequency_returns_every_transmission_across_sessions(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session("S1"))
        db.transmissionDao().insert(
            TestFixtures.transmission("TX1", sessionId = "S1", frequencyHz = 146_960_000L, startedAtUtc = 1L),
        )
        db.transmissionDao().insert(
            TestFixtures.transmission("TX2", sessionId = "S1", frequencyHz = 146_520_000L, startedAtUtc = 2L),
        )

        val heard = db.activityDao().transmissionsForFrequency(146_960_000L)

        assertEquals(listOf("TX1"), heard.map { it.id })
    }

    @Test
    public fun listDistinctFrequencies_excludes_null_rather_than_fabricating_an_unknown_bucket(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session("S1"))
        db.transmissionDao().insert(TestFixtures.transmission("TX1", sessionId = "S1", frequencyHz = 146_960_000L))
        db.transmissionDao().insert(TestFixtures.transmission("TX2", sessionId = "S1", frequencyHz = null))

        val frequencies = db.activityDao().listDistinctFrequencies()

        assertEquals(listOf(146_960_000L), frequencies)
    }

    @Test
    public fun listStations_returns_every_station(): Unit = runTest {
        db.catalogDao().insert(TestFixtures.station("W7NPC"))

        val stations = db.activityDao().listStations()

        assertEquals(listOf("W7NPC"), stations.map { it.id })
    }
}
