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
import org.ort.data.entity.PriorAdjustmentEntity
import org.ort.data.entity.StationIdentityHistoryEntity
import org.ort.data.entity.VoiceprintBindingHistoryEntity
import org.ort.data.entity.VoiceprintBindingSource
import org.ort.data.entity.VoiceprintEntity
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * The `:data` write paths R-073 (`Station-Identity.dc.html`, FR-SPK-10) and R-052
 * (`Detail-Propagated.dc.html`, FR-UI-6) found missing: renaming a station and adding a note,
 * rebinding a voiceprint to a station, and updating a named prior's weight. The load-bearing
 * behaviour under test in every case is constitution III — "nothing is deleted quietly" — so each
 * test checks not just that the new value lands, but that the value it replaced stays reachable.
 */
@RunWith(RobolectricTestRunner::class)
public class StationIdentityDaoTest {

    private lateinit var db: OrtDatabase

    @Before
    public fun openDatabase() {
        db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
    }

    private fun voiceprint(
        id: String,
        boundStationId: String? = null,
        bindingConfidence: Double? = null,
        bindingSource: VoiceprintBindingSource? = null,
    ): VoiceprintEntity = VoiceprintEntity(
        id = id,
        embedding = ByteArray(0),
        memberCount = 1,
        centroidUpdatedAt = null,
        boundStationId = boundStationId,
        bindingConfidence = bindingConfidence,
        lastConfirmedAt = null,
        isEnrolled = false,
        enrolmentObservationCount = 0,
        enrolmentSessionIds = null,
        enrolledAt = null,
        lastMatchedAt = null,
        bindingSource = bindingSource,
        embeddingModelId = null,
        embeddingModelVersion = null,
    )

    @Test
    @Requirement("R-073", "FR-SPK-10")
    public fun R_073_station_given_name_and_note_are_updatable(): Unit = runTest {
        db.catalogDao().insert(TestFixtures.station("N7XYZ"))
        val dao = db.stationIdentityDao()

        dao.renameStation(
            StationIdentityHistoryEntity(
                id = "H1",
                stationId = "N7XYZ",
                field = StationIdentityDao.FIELD_NAME,
                previousValue = null,
                newValue = "Dave",
                changedAt = 100L,
            ),
        )
        dao.updateStationNote(
            StationIdentityHistoryEntity(
                id = "H2",
                stationId = "N7XYZ",
                field = StationIdentityDao.FIELD_NOTE,
                previousValue = null,
                newValue = "net control most Tuesdays",
                changedAt = 101L,
            ),
        )

        val station = db.catalogDao().getStation("N7XYZ")!!
        assertEquals("Dave", station.userName)
        assertEquals("net control most Tuesdays", station.notes)
    }

    @Test
    @Requirement("R-073", "FR-SPK-10")
    public fun R_073_renaming_keeps_the_previous_name_reachable(): Unit = runTest {
        db.catalogDao().insert(TestFixtures.station("N7XYZ"))
        val dao = db.stationIdentityDao()

        dao.renameStation(
            StationIdentityHistoryEntity(
                id = "H1",
                stationId = "N7XYZ",
                field = StationIdentityDao.FIELD_NAME,
                previousValue = null,
                newValue = "Dave",
                changedAt = 100L,
            ),
        )
        dao.renameStation(
            StationIdentityHistoryEntity(
                id = "H2",
                stationId = "N7XYZ",
                field = StationIdentityDao.FIELD_NAME,
                previousValue = "Dave",
                newValue = "David",
                changedAt = 200L,
            ),
        )

        // The station row only ever holds the current name...
        assertEquals("David", db.catalogDao().getStation("N7XYZ")!!.userName)
        // ...but both earlier states remain reachable, oldest first.
        val history = dao.stationIdentityHistoryFor("N7XYZ")
        assertEquals(listOf("H1", "H2"), history.map { it.id })
        assertEquals(listOf(null, "Dave"), history.map { it.previousValue })
        assertEquals(listOf("Dave", "David"), history.map { it.newValue })
    }

    @Test
    @Requirement("R-052", "FR-UI-6")
    public fun R_052_voiceprint_rebinding_records_the_previous_binding(): Unit = runTest {
        db.catalogDao().insert(TestFixtures.station("K7LWH"))
        db.catalogDao().insert(TestFixtures.station("KA7LWH"))
        db.catalogDao().insert(
            voiceprint(
                "V1",
                boundStationId = "K7LWH",
                bindingConfidence = 0.6,
                bindingSource = VoiceprintBindingSource.AUTO,
            ),
        )
        val dao = db.stationIdentityDao()

        dao.bindVoiceprintToStation(
            VoiceprintBindingHistoryEntity(
                id = "BH1",
                voiceprintId = "V1",
                previousStationId = "K7LWH",
                previousBindingConfidence = 0.6,
                previousBindingSource = VoiceprintBindingSource.AUTO,
                newStationId = "KA7LWH",
                newBindingConfidence = 1.0,
                newBindingSource = VoiceprintBindingSource.MANUAL,
                changedAt = 300L,
            ),
        )

        // The voiceprint now belongs to the corrected station...
        val voiceprints = db.catalogDao().voiceprintsForStation("KA7LWH")
        assertEquals(1, voiceprints.size)
        assertEquals("V1", voiceprints.single().id)
        assertEquals(VoiceprintBindingSource.MANUAL, voiceprints.single().bindingSource)
        assertTrue(db.catalogDao().voiceprintsForStation("K7LWH").isEmpty())

        // ...but the earlier binding to K7LWH is not overwritten out of existence.
        val history = dao.voiceprintBindingHistoryFor("V1")
        assertEquals(1, history.size)
        assertEquals("K7LWH", history.single().previousStationId)
        assertEquals("KA7LWH", history.single().newStationId)
    }

    @Test
    @Requirement("R-052", "FR-LEX-9")
    public fun R_052_prior_weight_update_is_versioned(): Unit = runTest {
        db.catalogDao().insert(TestFixtures.station("KA7LWH"))
        val dao = db.stationIdentityDao()

        dao.updatePriorWeight(
            PriorAdjustmentEntity(
                id = "P1",
                stationId = "KA7LWH",
                name = "on_this_repeater",
                weight = 0.2,
                reason = null,
                isCurrent = true,
                updatedAt = 100L,
            ),
        )
        dao.updatePriorWeight(
            PriorAdjustmentEntity(
                id = "P2",
                stationId = "KA7LWH",
                name = "on_this_repeater",
                weight = 0.35,
                reason = "corrected to KA7LWH on this repeater",
                isCurrent = true,
                updatedAt = 200L,
            ),
        )

        // Exactly one current weight for this (station, prior) pair.
        val current = dao.currentPriorWeight("KA7LWH", "on_this_repeater")!!
        assertEquals("P2", current.id)
        assertEquals(0.35, current.weight, 0.0)

        // The weight it replaced is superseded, not deleted.
        val history = dao.priorWeightHistoryFor("KA7LWH", "on_this_repeater")
        assertEquals(listOf("P1", "P2"), history.map { it.id })
        assertEquals(listOf(false, true), history.map { it.isCurrent })
    }

    @Test
    @Requirement("R-073")
    public fun R_073_splitting_a_voiceprint_moves_membership_and_keeps_the_old_attribution_reachable(): Unit = runTest {
        db.sessionDao().insert(TestFixtures.session("S1"))
        db.catalogDao().insert(TestFixtures.station("N7DAVE"))
        db.catalogDao().insert(voiceprint("V1"))
        db.catalogDao().insert(voiceprint("V2"))
        db.transmissionDao().insert(
            TestFixtures.transmission("TX1", sessionId = "S1", stationId = "N7DAVE").copy(voiceprintId = "V1"),
        )
        db.transmissionDao().insert(
            TestFixtures.transmission("TX2", sessionId = "S1", stationId = "N7DAVE").copy(voiceprintId = "V1"),
        )
        val dao = db.stationIdentityDao()

        dao.splitVoiceprint(
            fromVoiceprintId = "V1",
            intoVoiceprintId = "V2",
            members = listOf(VoiceprintSplitMember(transmissionId = "TX2", correctionId = "C1")),
            splitAt = 400L,
        )

        // TX2 moved to the new, unidentified cluster and is marked corrected.
        val tx2 = db.transmissionDao().getById("TX2")!!
        assertEquals("V2", tx2.voiceprintId)
        assertNull(tx2.stationId)
        assertEquals(AttributionState.UNKNOWN, tx2.attributionState)
        assertTrue(tx2.corrected)
        // TX1 is untouched.
        val tx1 = db.transmissionDao().getById("TX1")!!
        assertEquals("V1", tx1.voiceprintId)
        assertEquals("N7DAVE", tx1.stationId)

        // The old attribution is kept, reachable as a correction row, not deleted.
        val corrections = db.correctionDao().correctionsFor("TX2")
        assertEquals(1, corrections.size)
        assertEquals("N7DAVE", corrections.single().previousValue)
        assertEquals(StationIdentityDao.FIELD_VOICEPRINT_SPLIT, corrections.single().field)
    }
}
