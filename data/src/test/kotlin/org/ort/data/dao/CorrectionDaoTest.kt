package org.ort.data.dao

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.AttributionState
import org.ort.data.OrtDatabase
import org.ort.data.TestFixtures
import org.ort.data.entity.CorrectionEntity
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * Build-plan P16's write path for [CorrectionEntity] (FR-UI-6, FR-SPK-7). A new DAO file, not an
 * edit to [CatalogDao]'s existing basic CRUD, per the prompt's own instruction. The load-bearing
 * behaviour under test is the CORRECTED lock: once applied, a correction must never be silently
 * re-propagated over by a later machine-resolution write (constitution I, FR-SPK-7).
 */
@RunWith(RobolectricTestRunner::class)
public class CorrectionDaoTest {

    private lateinit var db: OrtDatabase

    @Before
    public fun openDatabase() {
        db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
    }

    @Test
    @Requirement("FR-UI-6")
    public fun recordCorrection_persists_the_correction_row_and_updates_the_attribution(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session("S1"))
        db.transmissionDao().insert(TestFixtures.transmission("TX1", sessionId = "S1", stationId = "K7ABC"))

        db.correctionDao().recordCorrection(
            CorrectionEntity(
                id = "CORR1",
                transmissionId = "TX1",
                field = CorrectionDao.FIELD_STATION,
                previousValue = "K7ABC",
                newValue = "W7NPC",
                correctedAt = 100L,
            ),
        )

        val row = db.transmissionDao().getById("TX1")!!
        assertEquals(AttributionState.INFERRED, row.attributionState)
        assertEquals("W7NPC", row.stationId)
        assertNull(row.attributionConfidence)
        assertNull(row.attributionSourceTransmissionId)
        assertTrue(row.corrected)

        val corrections = db.correctionDao().correctionsFor("TX1")
        assertEquals(1, corrections.size)
        assertEquals("W7NPC", corrections.first().newValue)
    }

    @Test
    @Requirement("FR-SPK-7")
    public fun a_correction_locks_the_attribution_against_later_machine_re_propagation(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session("S1"))
        db.transmissionDao().insert(TestFixtures.transmission("TX1", sessionId = "S1", stationId = "K7ABC"))
        db.correctionDao().recordCorrection(
            CorrectionEntity(
                id = "CORR1",
                transmissionId = "TX1",
                field = CorrectionDao.FIELD_STATION,
                previousValue = "K7ABC",
                newValue = "W7NPC",
                correctedAt = 100L,
            ),
        )

        // Simulate a later Pass B / propagation write attempting to overwrite the correction.
        db.transmissionDao().updateAttribution(
            id = "TX1",
            state = AttributionState.CONFIRMED,
            stationId = "K9ZZZ",
            confidence = 0.99,
            sourceTransmissionId = null,
        )

        val row = db.transmissionDao().getById("TX1")!!
        assertEquals("the correction must not be re-propagated over", AttributionState.INFERRED, row.attributionState)
        assertEquals("W7NPC", row.stationId)
        assertTrue(row.corrected)
    }

    @Test
    @Requirement("Q8")
    public fun free_text_corrections_are_marked_unverified_and_still_lock(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session("S1"))
        db.transmissionDao().insert(TestFixtures.transmission("TX1", sessionId = "S1", stationId = null))

        db.correctionDao().recordCorrection(
            CorrectionEntity(
                id = "CORR1",
                transmissionId = "TX1",
                field = CorrectionDao.FIELD_STATION_UNVERIFIED,
                previousValue = null,
                newValue = "N0CALL",
                correctedAt = 100L,
            ),
        )

        val row = db.transmissionDao().getById("TX1")!!
        assertEquals("N0CALL", row.stationId)
        assertTrue(row.corrected)
        assertEquals(CorrectionDao.FIELD_STATION_UNVERIFIED, db.correctionDao().correctionsFor("TX1").first().field)
    }

    @Test
    public fun isCorrected_reflects_the_lock(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session("S1"))
        db.transmissionDao().insert(TestFixtures.transmission("TX1", sessionId = "S1"))

        assertEquals(false, db.correctionDao().isCorrected("TX1"))

        db.correctionDao().recordCorrection(
            CorrectionEntity(
                id = "CORR1",
                transmissionId = "TX1",
                field = CorrectionDao.FIELD_STATION,
                previousValue = null,
                newValue = "W7NPC",
                correctedAt = 1L,
            ),
        )

        assertEquals(true, db.correctionDao().isCorrected("TX1"))
    }
}
