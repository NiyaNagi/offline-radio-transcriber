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
    @Requirement("R-321")
    public fun R_321_correction_then_restore_round_trips_a_confirmed_attribution_exactly(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session("S1"))
        db.transmissionDao().insert(
            TestFixtures.transmission("TX1", sessionId = "S1", stationId = "K7ABC").copy(
                attributionConfidence = 0.95,
            ),
        )

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
        assertEquals(AttributionState.INFERRED, db.transmissionDao().getById("TX1")!!.attributionState)

        val correction = db.correctionDao().correctionsFor("TX1").single()
        assertEquals(AttributionState.CONFIRMED, correction.previousAttributionState)
        assertEquals(0.95, correction.previousAttributionConfidence)
        assertNull(correction.previousAttributionSourceTransmissionId)
        assertEquals(false, correction.previousCorrected)

        db.correctionDao().restoreAttribution(
            transmissionId = "TX1",
            state = correction.previousAttributionState!!,
            stationId = correction.previousValue,
            confidence = correction.previousAttributionConfidence,
            sourceTransmissionId = correction.previousAttributionSourceTransmissionId,
            corrected = correction.previousCorrected!!,
        )

        val restored = db.transmissionDao().getById("TX1")!!
        assertEquals(AttributionState.CONFIRMED, restored.attributionState)
        assertEquals("K7ABC", restored.stationId)
        assertEquals(0.95, restored.attributionConfidence)
        assertNull(restored.attributionSourceTransmissionId)
        assertEquals(
            "Undo all must also clear the CORRECTED lock a restore-to-uncorrected implies",
            false,
            restored.corrected,
        )
    }

    @Test
    @Requirement("R-321")
    public fun R_321_correction_then_restore_round_trips_an_ambiguous_attribution_exactly(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session("S1"))
        db.transmissionDao().insert(
            TestFixtures.transmission("TX1", sessionId = "S1", stationId = null).copy(
                attributionState = AttributionState.AMBIGUOUS,
            ),
        )

        db.correctionDao().recordCorrection(
            CorrectionEntity(
                id = "CORR1",
                transmissionId = "TX1",
                field = CorrectionDao.FIELD_STATION,
                previousValue = null,
                newValue = "W7NPC",
                correctedAt = 100L,
            ),
        )

        val correction = db.correctionDao().correctionsFor("TX1").single()
        assertEquals(AttributionState.AMBIGUOUS, correction.previousAttributionState)
        assertNull(correction.previousAttributionConfidence)
        assertNull(correction.previousAttributionSourceTransmissionId)
        assertEquals(false, correction.previousCorrected)

        db.correctionDao().restoreAttribution(
            transmissionId = "TX1",
            state = correction.previousAttributionState!!,
            stationId = correction.previousValue,
            confidence = correction.previousAttributionConfidence,
            sourceTransmissionId = correction.previousAttributionSourceTransmissionId,
            corrected = correction.previousCorrected!!,
        )

        val restored = db.transmissionDao().getById("TX1")!!
        assertEquals(AttributionState.AMBIGUOUS, restored.attributionState)
        assertNull(restored.stationId)
        assertNull(restored.attributionConfidence)
        assertNull(restored.attributionSourceTransmissionId)
        assertEquals(false, restored.corrected)
    }

    @Test
    @Requirement("R-321")
    public fun R_321_a_second_correction_captures_the_first_corrections_resulting_state(): Unit = runTest {
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
        db.correctionDao().recordCorrection(
            CorrectionEntity(
                id = "CORR2",
                transmissionId = "TX1",
                field = CorrectionDao.FIELD_STATION,
                previousValue = "W7NPC",
                newValue = "K9ZZZ",
                correctedAt = 200L,
            ),
        )

        // The second correction's "previous" is the FIRST correction's resulting state
        // (INFERRED, locked) -- not the transmission's original CONFIRMED state, because that is
        // what was really overwritten this time (a chain of corrections restores one hop at a
        // time, the same way a chain of transcript versions does -- constitution III).
        val second = db.correctionDao().correctionsFor("TX1").single { it.id == "CORR2" }
        assertEquals(AttributionState.INFERRED, second.previousAttributionState)
        assertNull(second.previousAttributionConfidence)
        assertNull(second.previousAttributionSourceTransmissionId)
        assertTrue(second.previousCorrected!!)
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
