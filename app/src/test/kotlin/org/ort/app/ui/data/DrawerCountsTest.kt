package org.ort.app.ui.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.data.OrtDatabase
import org.ort.data.entity.StationEntity
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * R-010 (ui-conformance-plan WP3): the drawer's Stations/Frequencies counts. The live-bar fallback
 * this file used to test ([DrawerCounts.liveBar]) is gone — `ui/data/LiveBarPolling.kt` (WP4's real
 * read path) landed on this branch and `OrtNavHost` now calls it directly; see `CHANGELOG.md`'s
 * WP3 addendum.
 */
@RunWith(RobolectricTestRunner::class)
class DrawerCountsTest {

    private lateinit var db: OrtDatabase
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun openDatabase() {
        db = OrtDatabase.create(context)
    }

    private fun station(id: String) = StationEntity(
        id = id,
        callsign = id,
        firstHeardAt = 0L,
        lastHeardAt = 0L,
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
    @Requirement("R-010")
    fun `current reads the real station count from the station table`(): Unit = runTest {
        db.catalogDao().insert(station("W7NPC"))
        db.catalogDao().insert(station("K7LWH"))

        val counts = DrawerCounts.current(context)

        assertEquals(2, counts.stationCount)
    }

    @Test
    @Requirement("R-010")
    fun `current reports zero, never a fabricated count, with nothing recorded yet`(): Unit = runTest {
        val counts = DrawerCounts.current(context)

        assertEquals(0, counts.stationCount)
        assertEquals(0, counts.frequencyCount)
    }
}
