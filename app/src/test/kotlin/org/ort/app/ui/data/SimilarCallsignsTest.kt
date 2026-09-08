package org.ort.app.ui.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.data.OrtDatabase
import org.ort.data.entity.StationEntity
import org.robolectric.RobolectricTestRunner

/** `Search-Empty.dc.html`'s "Similar callsigns heard" — one edit away (R-063). */
@RunWith(RobolectricTestRunner::class)
class SimilarCallsignsTest {

    private lateinit var db: OrtDatabase
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun openDatabase() {
        context.deleteDatabase(OrtDatabase.DATABASE_NAME)
        db = OrtDatabase.create(context)
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

    @Test
    fun `a callsign one substitution away is found`(): Unit = runTest {
        db.catalogDao().insert(station("ST1", "VE7ABD"))
        db.catalogDao().insert(station("ST2", "W7ZZZZ"))

        val near = SimilarCallsigns.near(context, "VE7ABC")

        assertEquals(listOf("VE7ABD"), near)
    }

    @Test
    fun `a callsign one insertion or deletion away is found`(): Unit = runTest {
        db.catalogDao().insert(station("ST1", "VA7ABC"))

        val near = SimilarCallsigns.near(context, "VA7ABCD")

        assertEquals(listOf("VA7ABC"), near)
    }

    @Test
    fun `the searched callsign itself is never returned as its own suggestion`(): Unit = runTest {
        db.catalogDao().insert(station("ST1", "W7NPC"))

        assertTrue(SimilarCallsigns.near(context, "W7NPC").isEmpty())
    }

    @Test
    fun `a callsign two edits away is not suggested`(): Unit = runTest {
        db.catalogDao().insert(station("ST1", "K7LMN"))

        assertTrue(SimilarCallsigns.near(context, "K7ABC").isEmpty())
    }

    @Test
    fun `a blank callsign returns nothing rather than every station`(): Unit = runTest {
        db.catalogDao().insert(station("ST1", "W7NPC"))

        assertTrue(SimilarCallsigns.near(context, "  ").isEmpty())
    }
}
