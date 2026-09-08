package org.ort.app.ui.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.AttributionState
import org.ort.core.PassId
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.StationEntity
import org.ort.data.entity.TranscriptEntity
import org.ort.data.entity.TranscriptPass
import org.ort.data.entity.TransmissionEntity
import org.ort.data.entity.VoiceprintBindingSource
import org.ort.data.entity.VoiceprintEntity
import org.ort.data.entity.WorkQueueItemEntity
import org.ort.data.entity.WorkQueueState
import org.robolectric.RobolectricTestRunner

/**
 * ui-conformance WP6 (R-052, R-058), `Detail-Correct-A/B/C.dc.html`, `Detail-Propagated.dc.html`,
 * `Flow-Correct.dc.html`: propagation (every over sharing the corrected over's `voiceprintId`, or
 * `stationId` when there is none, gets a [org.ort.data.entity.CorrectionEntity]) and `Confirm`
 * (an audit-only agreement, never a re-attribution). Its own reads/writes through [OrtDatabase]
 * directly — never through [ReaderPolling], per this package's file-ownership boundary.
 */
@RunWith(RobolectricTestRunner::class)
class CorrectionPollingTest {

    private lateinit var db: OrtDatabase
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun openDatabase() {
        db = OrtDatabase.create(context)
    }

    /**
     * Register R-110's `ScenariosTest` flake (`SQLiteBusyException: [database is locked]`) turned
     * out to be, in part, every file-backed-`OrtDatabase` test class in this module opening a fresh
     * `RoomDatabase` (its own connection pool, its own `InvalidationTracker`) against the *same*
     * on-disk `ort.db` and never closing the previous one — a leaked-writer effect that compounds
     * across an entire Gradle test-worker JVM, not just within one test class (Gradle can run many
     * test classes in one forked worker; `OrtDatabase.create(context)`'s default name is the same
     * every time). This class had exactly that gap — no `@After` at all — closed here the same way
     * `ScenariosTest` (`app/src/test/kotlin/org/ort/app/debug/ScenariosTest.kt`) now closes its own,
     * so this class stops being one of the never-closed instances a *different* test class's run
     * could contend against.
     */
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

    private fun transmission(
        id: String,
        stationId: String? = "K7LWH",
        voiceprintId: String? = "V1",
        samplePosition: Long = 0L,
    ) = TransmissionEntity(
        id = id,
        sessionId = "S1",
        threadId = null,
        startedAtUtc = samplePosition,
        endedAtUtc = samplePosition + 1_000L,
        durationMs = 1_000L,
        audioFormat = "flac/16k/mono",
        preRollMs = 200,
        postRollMs = 200,
        frequencyHz = 145_230_000L,
        frequencyProvenance = "measured",
        mode = null,
        signalStrength = null,
        channelName = null,
        voiceprintId = voiceprintId,
        attributionState = if (stationId != null) AttributionState.INFERRED else AttributionState.UNKNOWN,
        stationId = stationId,
        attributionConfidence = if (stationId != null) 0.7 else null,
        attributionSourceTransmissionId = null,
        processingState = TransmissionState.COMPLETE,
        rejectionReason = null,
        samplePosition = samplePosition,
        monotonicStartNanos = 0L,
        utcOffsetMinutes = 0,
        calibrationId = null,
        executionProvider = null,
    )

    private fun request(
        transmissionId: String,
        previous: String?,
        new: String,
        tier: CorrectionTier = CorrectionTier.PICK_CANDIDATE,
    ) = CorrectionRequest(
        transmissionId = transmissionId,
        previousStationId = previous,
        newStationId = new,
        tier = tier,
        correctedAtMillis = 500L,
    )

    private fun station(id: String) = StationEntity(
        id = id,
        callsign = id,
        firstHeardAt = null,
        lastHeardAt = null,
        transmissionCount = 0,
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

    private fun voiceprint(
        id: String,
        boundStationId: String? = null,
        bindingConfidence: Double? = null,
        bindingSource: VoiceprintBindingSource? = null,
    ) = VoiceprintEntity(
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
    fun `R_052 THIS_OVER_ONLY corrects exactly the one transmission`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1"))
        db.transmissionDao().insert(transmission("TX2"))

        val outcome = CorrectionPolling.applyCorrection(
            context,
            request("TX1", "K7LWH", "KA7LWH"),
            CorrectionScope.THIS_OVER_ONLY,
        )

        assertEquals(1, outcome.overCount)
        assertEquals("KA7LWH", db.transmissionDao().getById("TX1")!!.stationId)
        assertEquals("K7LWH", db.transmissionDao().getById("TX2")!!.stationId)
    }

    @Test
    fun `R_052 EVERY_OVER_SAME_VOICE propagates to every transmission sharing the voiceprint`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1", voiceprintId = "V1"))
        db.transmissionDao().insert(transmission("TX2", voiceprintId = "V1"))
        db.transmissionDao().insert(transmission("TX3", voiceprintId = "V2"))

        val outcome = CorrectionPolling.applyCorrection(
            context,
            request("TX1", "K7LWH", "KA7LWH"),
            CorrectionScope.EVERY_OVER_SAME_VOICE,
        )

        assertEquals(2, outcome.overCount)
        assertEquals("KA7LWH", db.transmissionDao().getById("TX1")!!.stationId)
        assertEquals("KA7LWH", db.transmissionDao().getById("TX2")!!.stationId)
        assertEquals("K7LWH", db.transmissionDao().getById("TX3")!!.stationId)
        assertEquals(setOf("TX1", "TX2"), outcome.affected.map { it.transmissionId }.toSet())
    }

    @Test
    fun `R_052 with no voiceprint, propagation falls back to the shared stationId`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1", stationId = "K7LWH", voiceprintId = null))
        db.transmissionDao().insert(transmission("TX2", stationId = "K7LWH", voiceprintId = null))

        val outcome = CorrectionPolling.applyCorrection(
            context,
            request("TX1", "K7LWH", "KA7LWH"),
            CorrectionScope.EVERY_OVER_SAME_VOICE,
        )

        assertEquals(2, outcome.overCount)
    }

    @Test
    fun `R_052 nothing is ever deleted by a correction`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1"))

        val outcome = CorrectionPolling.applyCorrection(
            context,
            request("TX1", "K7LWH", "KA7LWH"),
            CorrectionScope.THIS_OVER_ONLY,
        )

        assertEquals(0, outcome.deletedCount)
        assertEquals(1, db.correctionDao().correctionsFor("TX1").size)
    }

    // ---- R-052 complete: voiceprint rebinding and prior versioning through StationIdentityDao ----

    @Test
    fun R_052_propagation_rebinds_the_voiceprint_and_keeps_the_old_binding_reachable(): Unit = runTest {
        db.sessionDao().insert(session())
        db.catalogDao().insert(station("K7LWH"))
        db.catalogDao().insert(station("KA7LWH"))
        db.catalogDao().insert(
            voiceprint(
                "V1",
                boundStationId = "K7LWH",
                bindingConfidence = 0.6,
                bindingSource = VoiceprintBindingSource.AUTO,
            ),
        )
        db.transmissionDao().insert(transmission("TX1", stationId = "K7LWH", voiceprintId = "V1"))
        db.transmissionDao().insert(transmission("TX2", stationId = "K7LWH", voiceprintId = "V1"))

        val outcome = CorrectionPolling.applyCorrection(
            context,
            request("TX1", "K7LWH", "KA7LWH", tier = CorrectionTier.PICK_CANDIDATE),
            CorrectionScope.EVERY_OVER_SAME_VOICE,
        )

        // The voiceprint now belongs to the corrected station...
        assertTrue(outcome.voiceprintReassigned)
        val voiceprints = db.catalogDao().voiceprintsForStation("KA7LWH")
        assertEquals(1, voiceprints.size)
        assertEquals("V1", voiceprints.single().id)
        assertTrue(db.catalogDao().voiceprintsForStation("K7LWH").isEmpty())

        // ...but the earlier binding to K7LWH stays reachable, not overwritten out of existence.
        val history = db.stationIdentityDao().voiceprintBindingHistoryFor("V1")
        assertEquals(1, history.size)
        assertEquals("K7LWH", history.single().previousStationId)
        assertEquals("KA7LWH", history.single().newStationId)
        assertEquals(0.6, history.single().previousBindingConfidence)

        // A PICK_CANDIDATE correction is verified — it also reinforces the two named priors.
        assertEquals(2, outcome.priorsUpdatedCount)
        val onThisRepeater = db.stationIdentityDao().currentPriorWeight("KA7LWH", "on_this_repeater")!!
        assertEquals(0.15, onThisRepeater.weight, 0.0001)
        assertTrue(onThisRepeater.isCurrent)
    }

    @Test
    fun R_052_a_free_text_correction_never_rebinds_the_voiceprint_or_feeds_the_priors(): Unit = runTest {
        db.sessionDao().insert(session())
        db.catalogDao().insert(voiceprint("V1", boundStationId = "K7LWH"))
        db.transmissionDao().insert(transmission("TX1", stationId = "K7LWH", voiceprintId = "V1"))

        val outcome = CorrectionPolling.applyCorrection(
            context,
            request("TX1", "K7LWH", "N0CALL", tier = CorrectionTier.FREE_TEXT),
            CorrectionScope.EVERY_OVER_SAME_VOICE,
        )

        assertFalse(outcome.voiceprintReassigned)
        assertEquals(0, outcome.priorsUpdatedCount)
        assertTrue(db.stationIdentityDao().voiceprintBindingHistoryFor("V1").isEmpty())
    }

    @Test
    fun R_052_this_over_only_never_rebinds_a_voiceprint_shared_with_overs_left_alone(): Unit = runTest {
        db.sessionDao().insert(session())
        db.catalogDao().insert(voiceprint("V1", boundStationId = "K7LWH"))
        db.transmissionDao().insert(transmission("TX1", stationId = "K7LWH", voiceprintId = "V1"))
        db.transmissionDao().insert(transmission("TX2", stationId = "K7LWH", voiceprintId = "V1"))

        val outcome = CorrectionPolling.applyCorrection(
            context,
            request("TX1", "K7LWH", "KA7LWH"),
            CorrectionScope.THIS_OVER_ONLY,
        )

        assertFalse(outcome.voiceprintReassigned)
        assertEquals(0, outcome.priorsUpdatedCount)
    }

    @Test
    fun R_052_undo_all_restores_the_previous_binding_without_deleting(): Unit = runTest {
        db.sessionDao().insert(session())
        db.catalogDao().insert(station("K7LWH"))
        db.catalogDao().insert(station("KA7LWH"))
        db.catalogDao().insert(
            voiceprint(
                "V1",
                boundStationId = "K7LWH",
                bindingConfidence = 0.6,
                bindingSource = VoiceprintBindingSource.AUTO,
            ),
        )
        db.transmissionDao().insert(transmission("TX1", stationId = "K7LWH", voiceprintId = "V1"))
        val outcome = CorrectionPolling.applyCorrection(
            context,
            request("TX1", "K7LWH", "KA7LWH"),
            CorrectionScope.EVERY_OVER_SAME_VOICE,
        )

        CorrectionPolling.undoAll(context, outcome, atMillis = 700L)

        // The binding is back on the original station...
        assertEquals(1, db.catalogDao().voiceprintsForStation("K7LWH").size)
        assertTrue(db.catalogDao().voiceprintsForStation("KA7LWH").isEmpty())
        // ...but undo is itself a further, kept binding — both changes stay reachable.
        val bindingHistory = db.stationIdentityDao().voiceprintBindingHistoryFor("V1")
        assertEquals(2, bindingHistory.size)
        assertEquals("KA7LWH", bindingHistory[1].previousStationId)
        assertEquals("K7LWH", bindingHistory[1].newStationId)

        // The prior weight is back at its pre-correction value, and both rows are kept.
        val current = db.stationIdentityDao().currentPriorWeight("KA7LWH", "on_this_repeater")!!
        assertEquals(0.0, current.weight, 0.0001)
        val priorHistory = db.stationIdentityDao().priorWeightHistoryFor("KA7LWH", "on_this_repeater")
        assertEquals(2, priorHistory.size)
        assertEquals(listOf(false, true), priorHistory.map { it.isCurrent })
    }

    @Test
    fun R_052_propagated_counts_are_the_real_row_counts(): Unit = runTest {
        db.sessionDao().insert(session())
        db.catalogDao().insert(voiceprint("V1", boundStationId = "K7LWH"))
        db.transmissionDao().insert(transmission("TX1", stationId = "K7LWH", voiceprintId = "V1"))
        db.transmissionDao().insert(transmission("TX2", stationId = "K7LWH", voiceprintId = "V1"))
        db.transmissionDao().insert(transmission("TX3", stationId = "K7LWH", voiceprintId = "V1"))

        val outcome = CorrectionPolling.applyCorrection(
            context,
            request("TX1", "K7LWH", "KA7LWH"),
            CorrectionScope.EVERY_OVER_SAME_VOICE,
        )

        // Real, not placeholder: exactly the propagation's own blast radius, exactly two named
        // priors, and zero deleted — literal and true (constitution III: nothing deleted quietly).
        assertEquals(3, outcome.overCount)
        assertTrue(outcome.voiceprintReassigned)
        assertEquals(2, outcome.priorsUpdatedCount)
        assertEquals(0, outcome.deletedCount)
        assertEquals(
            3,
            db.correctionDao().correctionsFor("TX1").size + db.correctionDao().correctionsFor("TX2").size +
                db.correctionDao().correctionsFor("TX3").size,
        )
    }

    @Test
    fun `R_052 undo reverts every affected transmission with a new, kept correction row`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1", voiceprintId = "V1"))
        db.transmissionDao().insert(transmission("TX2", voiceprintId = "V1"))
        val outcome = CorrectionPolling.applyCorrection(
            context,
            request("TX1", "K7LWH", "KA7LWH"),
            CorrectionScope.EVERY_OVER_SAME_VOICE,
        )

        CorrectionPolling.undoAll(context, outcome, atMillis = 600L)

        assertEquals("K7LWH", db.transmissionDao().getById("TX1")!!.stationId)
        assertEquals("K7LWH", db.transmissionDao().getById("TX2")!!.stationId)
        // Undo is itself a correction — the reverted-from row is kept, not deleted (constitution III).
        assertEquals(2, db.correctionDao().correctionsFor("TX1").size)
    }

    // ---- R-058: Confirm is an audit row, never a re-attribution ----

    @Test
    fun `R_058 confirming a CONFIRMED transmission leaves its attribution state untouched`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(
            transmission("TX1", stationId = "K7LWH").copy(attributionState = AttributionState.CONFIRMED),
        )

        CorrectionPolling.confirm(context, transmissionId = "TX1", stationId = "K7LWH", atMillis = 100L)

        val entity = db.transmissionDao().getById("TX1")!!
        assertEquals(AttributionState.CONFIRMED, entity.attributionState)
        assertFalse(entity.corrected)
    }

    @Test
    fun `R_058 confirming records station_confirmed with an unchanged value`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1", stationId = "K7LWH"))

        CorrectionPolling.confirm(context, transmissionId = "TX1", stationId = "K7LWH", atMillis = 100L)

        val corrections = db.correctionDao().correctionsFor("TX1")
        assertEquals(1, corrections.size)
        assertEquals(FIELD_STATION_CONFIRMED, corrections.single().field)
        assertEquals("K7LWH", corrections.single().previousValue)
        assertEquals("K7LWH", corrections.single().newValue)
    }

    @Test
    fun `affectedOverCount reports the propagation blast radius before applying`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1", voiceprintId = "V1"))
        db.transmissionDao().insert(transmission("TX2", voiceprintId = "V1"))
        db.transmissionDao().insert(transmission("TX3", voiceprintId = "V2"))

        val count = CorrectionPolling.affectedOverCount(context, "TX1", CorrectionScope.EVERY_OVER_SAME_VOICE)

        assertEquals(2, count)
    }

    @Test
    fun `affectedOverCount for THIS_OVER_ONLY is always exactly one`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1", voiceprintId = "V1"))
        db.transmissionDao().insert(transmission("TX2", voiceprintId = "V1"))

        val count = CorrectionPolling.affectedOverCount(context, "TX1", CorrectionScope.THIS_OVER_ONLY)

        assertEquals(1, count)
    }

    // ---- R-153, F18 Fail-Pass, FR-RUN-9: passFailure + retryFailedPass ----

    private fun workQueueItem(
        transmissionId: String,
        pass: PassId = PassId.B_OFFLINE,
        state: WorkQueueState = WorkQueueState.FAILED,
        attemptCount: Int = 3,
        lastError: String? = "out of memory in the decoder",
    ) = WorkQueueItemEntity(
        transmissionId = transmissionId,
        pass = pass,
        state = state,
        priority = 0,
        attemptCount = attemptCount,
        lastError = lastError,
        enqueuedAt = 0L,
    )

    @Test
    fun R_153_passFailure_reads_the_real_failed_items_pass_attempts_and_error(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1").copy(processingState = TransmissionState.FAILED))
        db.workQueueDao().insert(workQueueItem("TX1"))

        val failure = CorrectionPolling.passFailure(context, "TX1")

        assertEquals(PassId.B_OFFLINE, failure?.passId)
        assertEquals("Pass B", failure?.passLabel)
        assertEquals(3, failure?.attempts)
        assertEquals("out of memory in the decoder", failure?.lastError)
    }

    @Test
    fun R_153_passFailure_is_null_when_no_work_queue_item_is_terminally_failed(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1"))
        db.workQueueDao().insert(workQueueItem("TX1", state = WorkQueueState.READY))

        assertEquals(null, CorrectionPolling.passFailure(context, "TX1"))
    }

    @Test
    fun FR_RUN_9_retryFailedPass_requeues_the_item_and_returns_the_transmission_to_processing(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1").copy(processingState = TransmissionState.FAILED))
        db.workQueueDao().insert(workQueueItem("TX1"))

        val retried = CorrectionPolling.retryFailedPass(context, "TX1", PassId.B_OFFLINE)

        assertTrue(retried)
        assertEquals(TransmissionState.PROCESSING, db.transmissionDao().getById("TX1")!!.processingState)
        val item = db.workQueueDao().findByTransmissionAndPass("TX1", PassId.B_OFFLINE.name).single()
        assertEquals(WorkQueueState.READY, item.state)
        assertEquals(0, item.attemptCount)
        // FR-RUN-9: the prior error is left reachable, not erased — constitution III.
        assertEquals("out of memory in the decoder", item.lastError)
    }

    @Test
    fun FR_RUN_9_retryFailedPass_is_scoped_to_this_transmissions_item_only(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1").copy(processingState = TransmissionState.FAILED))
        db.transmissionDao().insert(transmission("TX2").copy(processingState = TransmissionState.FAILED))
        db.workQueueDao().insert(workQueueItem("TX1"))
        db.workQueueDao().insert(workQueueItem("TX2"))

        CorrectionPolling.retryFailedPass(context, "TX1", PassId.B_OFFLINE)

        assertEquals(
            WorkQueueState.READY,
            db.workQueueDao().findByTransmissionAndPass("TX1", PassId.B_OFFLINE.name).single().state,
        )
        assertEquals(
            WorkQueueState.FAILED,
            db.workQueueDao().findByTransmissionAndPass("TX2", PassId.B_OFFLINE.name).single().state,
        )
        assertEquals(TransmissionState.PROCESSING, db.transmissionDao().getById("TX1")!!.processingState)
        assertEquals(TransmissionState.FAILED, db.transmissionDao().getById("TX2")!!.processingState)
    }

    @Test
    fun FR_RUN_9_retryFailedPass_returns_false_and_never_throws_when_nothing_matches(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1"))

        val retried = CorrectionPolling.retryFailedPass(context, "TX1", PassId.B_OFFLINE)

        assertFalse(retried)
    }

    // ---- R-055: revisions + Restore ----

    private fun transcript(
        id: String,
        transmissionId: String,
        pass: TranscriptPass,
        text: String,
        isCurrent: Boolean,
        createdAt: Long,
    ) = TranscriptEntity(
        id = id,
        transmissionId = transmissionId,
        pass = pass,
        text = text,
        modelId = "whisper-small",
        modelVersion = "1",
        quantization = null,
        decodeParams = null,
        noSpeechProb = null,
        confidence = 0.9,
        isCurrent = isCurrent,
        createdAt = createdAt,
    )

    @Test
    fun `R_055 revisions lists every version, current first, nothing hidden`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1"))
        db.transcriptDao().insert(
            transcript("V1", "TX1", TranscriptPass.A, "live partial", isCurrent = false, createdAt = 10L),
        )
        db.transcriptDao().insert(
            transcript("V2", "TX1", TranscriptPass.B, "final text", isCurrent = true, createdAt = 20L),
        )

        val versions = CorrectionPolling.revisions(context, "TX1")

        assertEquals(2, versions.size)
        assertEquals("V2", versions.first().id)
        assertEquals(true, versions.first().isCurrent)
        assertEquals(false, versions[1].isCurrent)
    }

    @Test
    fun `R_055 restore installs a new current transcript row and keeps the old one as superseded`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1"))
        db.transcriptDao().insert(
            transcript("V1", "TX1", TranscriptPass.A, "earlier text", isCurrent = false, createdAt = 10L),
        )
        db.transcriptDao().insert(
            transcript("V2", "TX1", TranscriptPass.B, "later text", isCurrent = true, createdAt = 20L),
        )

        CorrectionPolling.restore(context, "TX1", versionId = "V1", atMillis = 30L)

        val current = db.transcriptDao().getCurrent("TX1")!!
        assertEquals("earlier text", current.text)
        val all = db.transcriptDao().getAllVersions("TX1")
        assertEquals("restoring adds a row rather than deleting anything", 3, all.size)
        assertEquals(1, all.count { it.isCurrent })
    }
}
