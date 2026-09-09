package org.ort.app.ui.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
import org.ort.data.entity.VoiceprintEntity
import org.robolectric.RobolectricTestRunner

/**
 * Register R-320/R-182/R-425 (schema v5): [CorrectionPolling.inspectionWithSlots],
 * [CorrectionPolling.winningCharSpan] and [CorrectionPolling.ambiguousCandidateEvidence] — the
 * real DAO round trip through [org.ort.data.dao.CatalogDao.slotDetailsFor]/
 * `.winningCandidateCharSpan`/`.voiceprintsForStation`, kept in its own file for the same reason
 * the pass-failure/revisions surface already has one (`CorrectionPollingPassAndRevisionsTest.kt`)
 * — this package's own `LargeClass` history.
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

    // ---- R-471 (D03, `Detail-Ambiguous.dc.html`): the top-ranked candidate's span, when nothing is selected ----

    @Test
    fun R_471_winningCharSpan_falls_back_to_the_top_ranked_candidates_span_when_ambiguous(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(
            transmission("TX1").copy(attributionState = AttributionState.AMBIGUOUS, stationId = null),
        )
        // AMBIGUOUS: neither candidate is `selected` -- the real over R-471 reports.
        db.catalogDao().insert(
            CallsignCandidateEntity(
                id = "c-top",
                transmissionId = "TX1",
                callsign = "K7LWH",
                rank = 0,
                score = 8.6,
                grammarValid = true,
                ituPrefix = "K",
                ituCountry = "United States",
                priorBreakdown = null,
                databaseHit = true,
                selected = false,
            ),
        )
        db.catalogDao().insert(candidate("c-second", "TX1", selected = false))
        db.catalogDao().insert(slot("c-top", "TX1", 0, "K", charStart = 5, charEnd = 6))
        db.catalogDao().insert(slot("c-top", "TX1", 1, "7", charStart = 6, charEnd = 7))
        db.catalogDao().insert(slot("c-second", "TX1", 0, "K", charStart = 0, charEnd = 1))

        val span = CorrectionPolling.winningCharSpan(context, "TX1")

        assertEquals(5 until 7, span)
    }

    @Test
    fun R_471_winningCharSpan_still_prefers_the_selected_candidates_span_when_one_exists(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1"))
        db.catalogDao().insert(candidate("c1", "TX1", selected = true))
        db.catalogDao().insert(slot("c1", "TX1", 0, "K", charStart = 8, charEnd = 9))

        val span = CorrectionPolling.winningCharSpan(context, "TX1")

        // Unchanged from R-182's own behaviour -- the fallback only ever fires when the
        // selected-candidate query is genuinely empty.
        assertEquals(8 until 9, span)
    }

    // ---- R-425: real, per-candidate heard-count/last-heard/voice-match evidence ----

    @Test
    fun R_425_ambiguousCandidateEvidence_reads_the_real_heard_count_and_last_heard_time(): Unit = runTest {
        db.sessionDao().insert(session())
        // The transmission currently being disambiguated.
        db.transmissionDao().insert(transmission("TX1").copy(stationId = null, voiceprintId = null))
        // KE7QRS heard twice before, most recently at 02:00:00 UTC (7_200_000 ms).
        db.transmissionDao().insert(
            transmission("PAST1").copy(stationId = "KE7QRS", startedAtUtc = 3_600_000L),
        )
        db.transmissionDao().insert(
            transmission("PAST2").copy(stationId = "KE7QRS", startedAtUtc = 7_200_000L),
        )
        // KE7QRF never heard before this over — no rows for it at all.

        val evidence = CorrectionPolling.ambiguousCandidateEvidence(
            context,
            "TX1",
            listOf("KE7QRS", "KE7QRF"),
        )

        val qrs = evidence.getValue("KE7QRS")
        assertEquals(2, qrs.heardCount)
        assertEquals(ReaderTransmissionViewStateMapper.timeLabel(7_200_000L), qrs.lastHeardLabel)
        val qrf = evidence.getValue("KE7QRF")
        assertEquals(0, qrf.heardCount)
        assertNull(qrf.lastHeardLabel)
    }

    @Test
    fun R_425_ambiguousCandidateEvidence_voiceOnFile_matches_only_this_overs_own_voiceprint(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1").copy(stationId = null, voiceprintId = "V1"))
        db.catalogDao().insert(
            VoiceprintEntity(
                id = "V1",
                embedding = ByteArray(0),
                memberCount = 1,
                centroidUpdatedAt = null,
                boundStationId = "KE7QRS",
                bindingConfidence = 0.9,
                lastConfirmedAt = null,
                isEnrolled = false,
                enrolmentObservationCount = 0,
                enrolmentSessionIds = null,
                enrolledAt = null,
                lastMatchedAt = null,
                bindingSource = null,
                embeddingModelId = null,
                embeddingModelVersion = null,
            ),
        )

        val evidence = CorrectionPolling.ambiguousCandidateEvidence(
            context,
            "TX1",
            listOf("KE7QRS", "KE7QRF"),
        )

        assertTrue(
            "this over's own bound voiceprint must read as a real match",
            evidence.getValue("KE7QRS").voiceOnFile,
        )
        assertFalse(evidence.getValue("KE7QRF").voiceOnFile)
    }
}
