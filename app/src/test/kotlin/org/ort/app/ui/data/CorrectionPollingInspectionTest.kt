package org.ort.app.ui.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.AttributionState
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.CallsignCandidateEntity
import org.ort.data.entity.LatticeSlotEntity
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.TransmissionEntity
import org.robolectric.RobolectricTestRunner

/**
 * Register R-320/R-182 (schema v5): [CorrectionPolling.inspectionWithSlots] and
 * [CorrectionPolling.winningCharSpan] — the real DAO round trip through [org.ort.data.dao.CatalogDao.slotDetailsFor]
 * / `.winningCandidateCharSpan`, kept in its own file for the same reason the pass-failure/
 * revisions surface already has one (`CorrectionPollingPassAndRevisionsTest.kt`) — this package's
 * own `LargeClass` history.
 */
@RunWith(RobolectricTestRunner::class)
class CorrectionPollingInspectionTest {

    private lateinit var db: OrtDatabase
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun openDatabase() {
        db = OrtDatabase.create(context)
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    private fun session() = SessionEntity(
        id = "S1",
        startedAt = 0L,
        endedAt = null,
        profileId = null,
        deviceTier = null,
        appVersion = "test",
        terminationReason = null,
        sourceId = null,
        schemaVersion = OrtDatabase.SCHEMA_VERSION,
    )

    private fun transmission(id: String) = TransmissionEntity(
        id = id,
        sessionId = "S1",
        threadId = null,
        startedAtUtc = 0L,
        endedAtUtc = 1_000L,
        durationMs = 1_000L,
        audioFormat = "flac/16k/mono",
        preRollMs = 200,
        postRollMs = 200,
        frequencyHz = 145_230_000L,
        frequencyProvenance = "measured",
        mode = null,
        signalStrength = null,
        channelName = null,
        voiceprintId = null,
        attributionState = AttributionState.CONFIRMED,
        stationId = "K7LWH",
        attributionConfidence = 0.95,
        attributionSourceTransmissionId = null,
        processingState = TransmissionState.COMPLETE,
        rejectionReason = null,
        samplePosition = 0L,
        monotonicStartNanos = 0L,
        utcOffsetMinutes = 0,
        calibrationId = null,
        executionProvider = null,
    )

    private fun candidate(id: String, transmissionId: String, selected: Boolean) = CallsignCandidateEntity(
        id = id,
        transmissionId = transmissionId,
        callsign = "K7LWH",
        rank = if (selected) 0 else 1,
        score = 8.6,
        grammarValid = true,
        ituPrefix = "K",
        ituCountry = "United States",
        priorBreakdown = null,
        databaseHit = true,
        selected = selected,
    )

    private fun slot(
        candidateId: String,
        transmissionId: String,
        index: Int,
        unit: String,
        charStart: Int? = null,
        charEnd: Int? = null,
    ) = LatticeSlotEntity(
        id = "$candidateId-slot$index",
        transmissionId = transmissionId,
        candidateId = candidateId,
        index = index,
        unit = unit,
        score = 0.9,
        keptAlternate = null,
        charStart = charStart,
        charEnd = charEnd,
    )

    @Test
    fun R_320_inspectionWithSlots_reads_the_winning_candidates_real_slots(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1"))
        db.catalogDao().insert(candidate("c1", "TX1", selected = true))
        db.catalogDao().insert(candidate("c2", "TX1", selected = false))
        db.catalogDao().insert(slot("c1", "TX1", 0, "K"))
        db.catalogDao().insert(slot("c1", "TX1", 1, "7"))
        db.catalogDao().insert(slot("c2", "TX1", 0, "K"))

        val inspection = CorrectionPolling.inspectionWithSlots(context, "TX1")

        val winner = inspection.candidates.single { it.selected }
        assertEquals(2, winner.slots.size)
    }

    @Test
    fun R_320_inspectionWithSlots_is_the_honest_empty_list_for_a_pre_schema_v5_record(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1"))
        db.catalogDao().insert(candidate("c1", "TX1", selected = true))

        val inspection = CorrectionPolling.inspectionWithSlots(context, "TX1")

        assertEquals(0, inspection.candidates.single().slots.size)
    }

    @Test
    fun R_182_winningCharSpan_reads_the_real_anchored_span(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1"))
        db.catalogDao().insert(candidate("c1", "TX1", selected = true))
        db.catalogDao().insert(slot("c1", "TX1", 0, "K", charStart = 8, charEnd = 9))
        db.catalogDao().insert(slot("c1", "TX1", 1, "7", charStart = 9, charEnd = 10))

        val span = CorrectionPolling.winningCharSpan(context, "TX1")

        assertEquals(8 until 10, span)
    }

    @Test
    fun R_182_winningCharSpan_is_null_for_an_unanchored_acoustic_lattice(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1"))
        db.catalogDao().insert(candidate("c1", "TX1", selected = true))
        db.catalogDao().insert(slot("c1", "TX1", 0, "K", charStart = null, charEnd = null))

        val span = CorrectionPolling.winningCharSpan(context, "TX1")

        assertNull(span)
    }

    @Test
    fun R_182_winningCharSpan_is_null_when_there_is_no_winning_candidate_at_all(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1"))

        val span = CorrectionPolling.winningCharSpan(context, "TX1")

        assertNull(span)
    }
}
