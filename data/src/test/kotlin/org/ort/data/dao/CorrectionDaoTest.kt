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
    @Requirement("FR-ANL-2")
    public fun `FR_ANL_2_listAllFields returns every correction's field name, and only that`(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session("S1"))
        db.transmissionDao().insert(TestFixtures.transmission("TX1", sessionId = "S1", stationId = "K7ABC"))
        db.transmissionDao().insert(TestFixtures.transmission("TX2", sessionId = "S1", stationId = null))
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
                transmissionId = "TX2",
                field = CorrectionDao.FIELD_STATION_UNVERIFIED,
                previousValue = null,
                newValue = "N0CALL",
                correctedAt = 200L,
            ),
        )

        val fields = db.correctionDao().listAllFields()

        assertEquals(listOf(CorrectionDao.FIELD_STATION, CorrectionDao.FIELD_STATION_UNVERIFIED), fields.sorted())
    }

    @Test
    @Requirement("FR-ANL-2")
    public fun `FR_ANL_2_listAllFields is empty when no correction has ever been recorded`(): Unit = runTest {
        assertEquals(emptyList<String>(), db.correctionDao().listAllFields())
    }

    /**
     * Register R-1132, D56 (FR-DIG-7, constitution I, III): "an operator correction always
     * creates or rebinds the record" — the other half of D56, alongside Pass B closure
     * ([CatalogDao.recordStationObservation]). A correction always resolves to `INFERRED`
     * ([CorrectionDao.applyCorrectedAttribution]), which already clears D56's "AMBIGUOUS or
     * better" bar, so this never needs its own separate threshold.
     */
    @Test
    @Requirement("R-1132", "FR-DIG-7")
    public fun R_1132_recordCorrection_creates_a_station_for_a_callsign_never_seen_before(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session("S1"))
        db.transmissionDao().insert(TestFixtures.transmission("TX1", sessionId = "S1", stationId = null))

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

        val station = db.catalogDao().getStation("W7NPC")!!
        assertEquals("W7NPC", station.callsign)
        assertEquals(100L, station.firstHeardAt)
        assertEquals("CONFIRMED=0,INFERRED=1,AMBIGUOUS=0,UNKNOWN=0", station.overCountsByAttributionState)
    }

    @Test
    @Requirement("R-1132", "FR-DIG-7")
    public fun R_1132_recordCorrection_rebinds_rather_than_duplicates_an_existing_station(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session("S1"))
        // W7NPC already has one genuine, real AMBIGUOUS observation from an earlier over (TX0)
        // before TX1's correction rebinds it -- backed by a real transmission + top-ranked
        // candidate row, the same shape a real Pass B closure would leave (register R-1134: the
        // count is derived, not a bare recordStationObservation(...) call with nothing behind it).
        db.transmissionDao().insert(
            TestFixtures.transmission("TX0", sessionId = "S1", stationId = null)
                .copy(attributionState = AttributionState.AMBIGUOUS),
        )
        db.catalogDao().insert(
            org.ort.data.entity.CallsignCandidateEntity(
                id = "C-TX0",
                transmissionId = "TX0",
                callsign = "W7NPC",
                rank = 0,
                score = 0.9,
                grammarValid = true,
                ituPrefix = "W",
                ituCountry = "United States",
                priorBreakdown = null,
                databaseHit = true,
                selected = false,
            ),
        )
        db.catalogDao().recordStationObservation("W7NPC", AttributionState.AMBIGUOUS, observedAt = 50L)
        db.transmissionDao().insert(TestFixtures.transmission("TX1", sessionId = "S1", stationId = null))

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

        val station = db.catalogDao().getStation("W7NPC")!!
        assertEquals(50L, station.firstHeardAt) // birth timestamp untouched by the rebind
        assertEquals(100L, station.lastHeardAt)
        // TX0 (AMBIGUOUS, found via its top-ranked candidate) and TX1 (INFERRED, corrected --
        // stationId now names W7NPC directly) both derive as real, distinct observations.
        assertEquals("CONFIRMED=0,INFERRED=1,AMBIGUOUS=1,UNKNOWN=0", station.overCountsByAttributionState)
    }

    /**
     * Register R-1134 (FR-DIG-7, FR-DIG-8, constitution I, III): the central regression this row
     * exists for. Before this fix, `overCountsByAttributionState` was incremented in place on
     * every `recordStationObservation` call and nothing ever called an inverse on undo -- so
     * correcting a callsign and then undoing it left the old station's count permanently
     * inflated by one observation it no longer has any evidence for. Deriving the count from the
     * transmission rows themselves (see [CatalogDao.getStation]'s own doc comment) fixes this for
     * free: `restoreAttribution` already writes the transmission's own `attributionState`/
     * `stationId` back to their pre-correction values, and the very next [CatalogDao.getStation]
     * read simply re-derives from that real state -- no separate "undo the increment" step to
     * remember or forget.
     */
    @Test
    @Requirement("R-1134", "FR-DIG-7", "FR-DIG-8", "R-321")
    public fun R_1134_an_undone_correction_no_longer_inflates_the_old_stations_over_count(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session("S1"))
        // TX1's real Pass B outcome was an uncorrected AMBIGUOUS resolution to K7ABC -- no
        // stationId (register R-1110), found only via its top-ranked candidate, the same shape
        // DataPassBResultSink.recordStationObservation itself produces on a real device.
        db.transmissionDao().insert(
            TestFixtures.transmission("TX1", sessionId = "S1", stationId = null)
                .copy(attributionState = AttributionState.AMBIGUOUS),
        )
        db.catalogDao().insert(
            org.ort.data.entity.CallsignCandidateEntity(
                id = "C1",
                transmissionId = "TX1",
                callsign = "K7ABC",
                rank = 0,
                score = 0.9,
                grammarValid = true,
                ituPrefix = "K",
                ituCountry = "United States",
                priorBreakdown = null,
                databaseHit = true,
                selected = false,
            ),
        )
        db.catalogDao().recordStationObservation("K7ABC", AttributionState.AMBIGUOUS, observedAt = 500L)
        assertEquals(
            "CONFIRMED=0,INFERRED=0,AMBIGUOUS=1,UNKNOWN=0",
            db.catalogDao().getStation("K7ABC")!!.overCountsByAttributionState,
        )

        // The operator corrects TX1 away from K7ABC to W7NPC.
        db.correctionDao().recordCorrection(
            CorrectionEntity(
                id = "CORR1",
                transmissionId = "TX1",
                field = CorrectionDao.FIELD_STATION,
                previousValue = null,
                newValue = "W7NPC",
                correctedAt = 1_000L,
            ),
        )
        // K7ABC's count drops -- TX1's stationId now names W7NPC, not null, so the AMBIGUOUS arm
        // of the derivation no longer finds it there.
        assertEquals(
            "CONFIRMED=0,INFERRED=0,AMBIGUOUS=0,UNKNOWN=0",
            db.catalogDao().getStation("K7ABC")!!.overCountsByAttributionState,
        )
        assertEquals(
            "CONFIRMED=0,INFERRED=1,AMBIGUOUS=0,UNKNOWN=0",
            db.catalogDao().getStation("W7NPC")!!.overCountsByAttributionState,
        )

        // The operator undoes the correction.
        val correction = db.correctionDao().correctionsFor("TX1").single()
        db.correctionDao().restoreAttribution(
            transmissionId = "TX1",
            state = correction.previousAttributionState!!,
            stationId = correction.previousValue,
            confidence = correction.previousAttributionConfidence,
            sourceTransmissionId = correction.previousAttributionSourceTransmissionId,
            corrected = correction.previousCorrected!!,
        )

        // K7ABC's count is restored automatically -- no inverse-decrement call was ever made;
        // the derivation just reads TX1's real, restored attributionState/stationId again.
        assertEquals(
            "CONFIRMED=0,INFERRED=0,AMBIGUOUS=1,UNKNOWN=0",
            db.catalogDao().getStation("K7ABC")!!.overCountsByAttributionState,
        )
        // And W7NPC no longer claims an observation it has no evidence for.
        assertEquals(
            "CONFIRMED=0,INFERRED=0,AMBIGUOUS=0,UNKNOWN=0",
            db.catalogDao().getStation("W7NPC")!!.overCountsByAttributionState,
        )
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
