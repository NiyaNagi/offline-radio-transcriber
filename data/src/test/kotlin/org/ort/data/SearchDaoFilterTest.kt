package org.ort.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.data.entity.StationEntity
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * FR-UI-3's filter half — callsign, frequency and date-range filters over the real `transmission`
 * (and, for callsign, `station`) tables, joined and exercised against a real Room database. This
 * path never touches `transcript_fts` at all, so — unlike full-text matching (see
 * [SearchDaoFullTextTest], which the fts5-availability probe in that file's own comment explains
 * is genuinely untestable in this Robolectric environment) — it is fully provable here.
 */
@RunWith(RobolectricTestRunner::class)
public class SearchDaoFilterTest {

    private lateinit var db: OrtDatabase

    @Before
    public fun openDatabase() {
        db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
    }

    private fun station(id: String, callsign: String?) = StationEntity(
        id = id,
        callsign = callsign,
        firstHeardAt = null,
        lastHeardAt = null,
        notes = null,
        userName = null,
        frequenciesHeard = null,
        activityByHourDow = null,
        potaRefs = null,
        spokenGrids = null,
        ituRegionFromPrefix = null,
        overCountsByAttributionState = null,
    )

    private suspend fun seed() {
        db.sessionDao().insert(TestFixtures.session())
        db.catalogDao().insert(station("ST-W7NPC", "W7NPC"))
        db.catalogDao().insert(station("ST-K7ABC", "K7ABC"))
        db.transmissionDao().insert(
            TestFixtures.transmission("TX1", samplePosition = 1L).copy(
                stationId = "ST-W7NPC",
                frequencyHz = 145_230_000L,
                startedAtUtc = 1_000L,
            ),
        )
        db.transmissionDao().insert(
            TestFixtures.transmission("TX2", samplePosition = 2L).copy(
                stationId = "ST-K7ABC",
                frequencyHz = 146_520_000L,
                startedAtUtc = 5_000L,
            ),
        )
        db.transmissionDao().insert(
            TestFixtures.transmission("TX3", samplePosition = 3L).copy(
                stationId = null,
                frequencyHz = 145_230_000L,
                startedAtUtc = 9_000L,
            ),
        )
    }

    @Test
    @Requirement("FR-UI-3")
    public fun `filtering by callsign returns only that station's transmissions`(): Unit = runTest {
        seed()
        val result = db.searchDao().filterOnly(callsign = "W7NPC", frequencyHz = null, fromUtc = null, toUtc = null)
        assertEquals(listOf("TX1"), result.map { it.id })
    }

    @Test
    @Requirement("FR-UI-3")
    public fun `callsign filtering is case-insensitive`(): Unit = runTest {
        seed()
        val result = db.searchDao().filterOnly(callsign = "w7npc", frequencyHz = null, fromUtc = null, toUtc = null)
        assertEquals(listOf("TX1"), result.map { it.id })
    }

    @Test
    @Requirement("FR-UI-3")
    public fun `filtering by frequency returns every transmission on that frequency`(): Unit = runTest {
        seed()
        val result =
            db.searchDao().filterOnly(callsign = null, frequencyHz = 145_230_000L, fromUtc = null, toUtc = null)
        assertEquals(setOf("TX1", "TX3"), result.map { it.id }.toSet())
    }

    @Test
    @Requirement("FR-UI-3")
    public fun `filtering by a date range returns only transmissions started within it`(): Unit = runTest {
        seed()
        val result = db.searchDao().filterOnly(callsign = null, frequencyHz = null, fromUtc = 2_000L, toUtc = 6_000L)
        assertEquals(listOf("TX2"), result.map { it.id })
    }

    @Test
    @Requirement("FR-UI-3")
    public fun `combined filters narrow to their intersection`(): Unit = runTest {
        seed()
        val result = db.searchDao().filterOnly(
            callsign = null,
            frequencyHz = 145_230_000L,
            fromUtc = 5_000L,
            toUtc = null,
        )
        assertEquals(listOf("TX3"), result.map { it.id })
    }

    @Test
    @Requirement("FR-UI-3")
    public fun `no filters at all returns every transmission, newest first`(): Unit = runTest {
        seed()
        val result = db.searchDao().filterOnly(callsign = null, frequencyHz = null, fromUtc = null, toUtc = null)
        assertEquals(listOf("TX3", "TX2", "TX1"), result.map { it.id })
    }

    @Test
    @Requirement("FR-UI-3")
    public fun `search with blank text delegates to the filter-only path without touching the fts index`(): Unit =
        runTest {
            seed()
            val result = db.searchDao().search(text = "  ", callsign = "K7ABC")
            assertEquals(listOf("TX2"), result.map { it.id })
        }
}
