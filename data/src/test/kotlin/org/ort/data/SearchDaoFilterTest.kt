package org.ort.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.AttributionState
import org.ort.core.TransmissionState
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
                // TestFixtures.transmission()'s default attributionState is derived from the
                // stationId *passed to that call*, not from a later .copy() — set explicitly so
                // it actually agrees with the stationId this row ends up with.
                attributionState = AttributionState.CONFIRMED,
            ),
        )
        db.transmissionDao().insert(
            TestFixtures.transmission("TX2", samplePosition = 2L).copy(
                stationId = "ST-K7ABC",
                frequencyHz = 146_520_000L,
                startedAtUtc = 5_000L,
                attributionState = AttributionState.CONFIRMED,
            ),
        )
        db.transmissionDao().insert(
            TestFixtures.transmission("TX3", samplePosition = 3L).copy(
                stationId = null,
                frequencyHz = 145_230_000L,
                startedAtUtc = 9_000L,
                processingState = TransmissionState.REJECTED,
            ),
        )
        // TX4: a different amateur band (70cm, not 2m like TX1-3) and a distinct attribution
        // state (AMBIGUOUS), so band/attribution-state filters have something to exclude.
        db.transmissionDao().insert(
            TestFixtures.transmission("TX4", samplePosition = 4L).copy(
                stationId = null,
                frequencyHz = 446_000_000L,
                startedAtUtc = 15_000L,
                attributionState = AttributionState.AMBIGUOUS,
            ),
        )
    }

    private suspend fun filterOnly(
        callsign: String? = null,
        frequencyHz: Long? = null,
        fromUtc: Long? = null,
        toUtc: Long? = null,
        bandMinHz: Long? = null,
        bandMaxHz: Long? = null,
        attributionState: AttributionState? = null,
        rejected: Boolean? = null,
    ) = db.searchDao().filterOnly(
        callsign,
        frequencyHz,
        fromUtc,
        toUtc,
        bandMinHz,
        bandMaxHz,
        attributionState,
        rejected,
    )

    @Test
    @Requirement("FR-UI-3")
    public fun `filtering by callsign returns only that station's transmissions`(): Unit = runTest {
        seed()
        val result = filterOnly(callsign = "W7NPC")
        assertEquals(listOf("TX1"), result.map { it.id })
    }

    @Test
    @Requirement("FR-UI-3")
    public fun `callsign filtering is case-insensitive`(): Unit = runTest {
        seed()
        val result = filterOnly(callsign = "w7npc")
        assertEquals(listOf("TX1"), result.map { it.id })
    }

    @Test
    @Requirement("FR-UI-3")
    public fun `filtering by frequency returns every transmission on that frequency`(): Unit = runTest {
        seed()
        val result = filterOnly(frequencyHz = 145_230_000L)
        assertEquals(setOf("TX1", "TX3"), result.map { it.id }.toSet())
    }

    @Test
    @Requirement("FR-UI-3")
    public fun `filtering by a date range returns only transmissions started within it`(): Unit = runTest {
        seed()
        val result = filterOnly(fromUtc = 2_000L, toUtc = 6_000L)
        assertEquals(listOf("TX2"), result.map { it.id })
    }

    @Test
    @Requirement("FR-UI-3")
    public fun `combined filters narrow to their intersection`(): Unit = runTest {
        seed()
        val result = filterOnly(frequencyHz = 145_230_000L, fromUtc = 5_000L)
        assertEquals(listOf("TX3"), result.map { it.id })
    }

    @Test
    @Requirement("FR-UI-3")
    public fun `no filters at all returns every transmission, newest first`(): Unit = runTest {
        seed()
        val result = filterOnly()
        assertEquals(listOf("TX4", "TX3", "TX2", "TX1"), result.map { it.id })
    }

    @Test
    @Requirement("FR-UI-3")
    public fun `FR_UI_3 filtering by band returns only transmissions whose frequency falls in it`(): Unit = runTest {
        seed()
        val result = filterOnly(bandMinHz = Band.VHF_2M.minHz, bandMaxHz = Band.VHF_2M.maxHz)
        assertEquals(setOf("TX1", "TX2", "TX3"), result.map { it.id }.toSet())
    }

    @Test
    @Requirement("FR-UI-3")
    public fun `FR_UI_3 filtering by a different band excludes transmissions outside it`(): Unit = runTest {
        seed()
        val result = filterOnly(bandMinHz = Band.UHF_70CM.minHz, bandMaxHz = Band.UHF_70CM.maxHz)
        assertEquals(listOf("TX4"), result.map { it.id })
    }

    @Test
    @Requirement("FR-UI-3")
    public fun `FR_UI_3 filtering by attribution state returns only that state`(): Unit = runTest {
        seed()
        val result = filterOnly(attributionState = AttributionState.CONFIRMED)
        assertEquals(setOf("TX1", "TX2"), result.map { it.id }.toSet())
    }

    @Test
    @Requirement("FR-UI-3")
    public fun `FR_UI_3 filtering by a different attribution state excludes the rest`(): Unit = runTest {
        seed()
        val result = filterOnly(attributionState = AttributionState.AMBIGUOUS)
        assertEquals(listOf("TX4"), result.map { it.id })
    }

    @Test
    @Requirement("FR-UI-3")
    public fun `FR_UI_3 rejected true returns only rejected transmissions`(): Unit = runTest {
        seed()
        val result = filterOnly(rejected = true)
        assertEquals(listOf("TX3"), result.map { it.id })
    }

    @Test
    @Requirement("FR-UI-3")
    public fun `FR_UI_3 rejected false returns only accepted transmissions`(): Unit = runTest {
        seed()
        val result = filterOnly(rejected = false)
        assertEquals(setOf("TX1", "TX2", "TX4"), result.map { it.id }.toSet())
    }

    @Test
    @Requirement("FR-UI-3")
    public fun `FR_UI_3 band, attribution state and rejected compose with each other and other filters`(): Unit =
        runTest {
            seed()
            // Everything on 2m, not rejected, and CONFIRMED: only TX1 and TX2 qualify; add a
            // frequency filter that further narrows to TX1 alone.
            val result = filterOnly(
                frequencyHz = 145_230_000L,
                bandMinHz = Band.VHF_2M.minHz,
                bandMaxHz = Band.VHF_2M.maxHz,
                attributionState = AttributionState.CONFIRMED,
                rejected = false,
            )
            assertEquals(listOf("TX1"), result.map { it.id })
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
