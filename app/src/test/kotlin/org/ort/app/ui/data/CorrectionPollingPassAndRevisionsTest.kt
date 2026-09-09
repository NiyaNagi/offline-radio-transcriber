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
import org.ort.data.entity.TranscriptEntity
import org.ort.data.entity.TranscriptPass
import org.ort.data.entity.TransmissionEntity
import org.ort.data.entity.WorkAttemptEntity
import org.ort.data.entity.WorkAttemptOutcome
import org.ort.data.entity.WorkQueueItemEntity
import org.ort.data.entity.WorkQueueState
import org.robolectric.RobolectricTestRunner

/**
 * `CorrectionPollingTest.kt` split — detekt's `LargeClass` finding, the same fix `RowsTest.kt`'s
 * own `NavRowTest.kt` split and `ScenariosTest.kt`'s own `FailureOverrideScenariosTest.kt` split
 * used (see either file's own doc comment): adding this round's R-188/R-194 tests grew
 * `CorrectionPollingTest.kt` past the threshold. This file owns [CorrectionPolling]'s pass-failure
 * (R-153, F18 `Fail-Pass.dc.html`, FR-RUN-9), revisions/restore (R-055, R-194,
 * `Detail-Revisions.dc.html`) and transcript-confidence (R-188, `Detail.dc.html`) surfaces; the
 * propagation/correction/search/attribution surfaces (R-052, R-058, R-183, R-185, R-189) stay in
 * `CorrectionPollingTest.kt`. Its own reads/writes through [OrtDatabase] directly, same as that file.
 */
@RunWith(RobolectricTestRunner::class)
class CorrectionPollingPassAndRevisionsTest {

    private lateinit var db: OrtDatabase
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun openDatabase() {
        db = OrtDatabase.create(context)
    }

    /** Same fix `CorrectionPollingTest.closeDatabase`'s own doc comment describes in full. */
    @After
    fun closeDatabase() {
        db.close()
    }

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
        // No `work_attempt` rows seeded for this item — round 11's honest single-line fallback.
        assertTrue(failure?.attemptLog.isNullOrEmpty())
    }

    @Test
    fun R_426_attempts_reads_the_real_per_attempt_rows_oldest_first_and_words_a_timeout_honestly(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1").copy(processingState = TransmissionState.FAILED))
        val itemId = db.workQueueDao().insert(workQueueItem("TX1"))
        // Seeded out of chronological order — `attemptsFor`'s own `ORDER BY attemptNo` is what
        // guarantees oldest-first, not insertion order.
        db.workQueueDao().insert(
            WorkAttemptEntity(
                itemId = itemId,
                attemptNo = 3,
                startedAtMillis = 5_015_000L,
                finishedAtMillis = 5_015_000L,
                outcome = WorkAttemptOutcome.TIMEOUT,
                reason = "timeout",
            ),
        )
        db.workQueueDao().insert(
            WorkAttemptEntity(
                itemId = itemId,
                attemptNo = 1,
                startedAtMillis = 5_000_000L,
                finishedAtMillis = 5_000_000L,
                outcome = WorkAttemptOutcome.FAILED,
                reason = "out of memory in the decoder",
            ),
        )
        db.workQueueDao().insert(
            WorkAttemptEntity(
                itemId = itemId,
                attemptNo = 2,
                startedAtMillis = 5_005_000L,
                finishedAtMillis = 5_005_000L,
                outcome = WorkAttemptOutcome.FAILED,
                reason = "out of memory in the decoder",
            ),
        )

        val failure = CorrectionPolling.passFailure(context, "TX1")

        assertEquals(3, failure?.attemptLog?.size)
        assertEquals("Out of memory in the decoder", failure?.attemptLog?.get(0)?.reasonLabel)
        assertEquals("Out of memory in the decoder", failure?.attemptLog?.get(1)?.reasonLabel)
        // The real, only-ever-stored `"timeout"` reason humanized — never the raw token.
        assertEquals("Timed out", failure?.attemptLog?.get(2)?.reasonLabel)
        assertEquals(
            ReaderTransmissionViewStateMapper.timeLabel(5_000_000L),
            failure?.attemptLog?.get(0)?.timeLabel,
        )
        assertEquals(
            ReaderTransmissionViewStateMapper.timeLabel(5_015_000L),
            failure?.attemptLog?.get(2)?.timeLabel,
        )
    }

    @Test
    fun R_470_passFailure_counts_the_real_FAILED_transmissions_in_this_overs_own_session(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(
            transmission("TX1", samplePosition = 0L).copy(processingState = TransmissionState.FAILED),
        )
        db.transmissionDao().insert(
            transmission("TX2", samplePosition = 1_000L).copy(processingState = TransmissionState.FAILED),
        )
        // Not FAILED -- must not be counted.
        db.transmissionDao().insert(
            transmission("TX3", samplePosition = 2_000L).copy(processingState = TransmissionState.COMPLETE),
        )
        db.workQueueDao().insert(workQueueItem("TX1"))

        val failure = CorrectionPolling.passFailure(context, "TX1")

        assertEquals(2, failure?.sessionFailedCount)
    }

    @Test
    fun R_470_passFailure_session_failed_count_is_scoped_to_this_session_alone(): Unit = runTest {
        db.sessionDao().insert(session())
        db.sessionDao().insert(session().copy(id = "S2"))
        db.transmissionDao().insert(
            transmission("TX1", samplePosition = 0L).copy(processingState = TransmissionState.FAILED),
        )
        db.transmissionDao().insert(
            transmission("TX-OTHER-SESSION", samplePosition = 0L)
                .copy(sessionId = "S2", processingState = TransmissionState.FAILED),
        )
        db.workQueueDao().insert(workQueueItem("TX1"))

        val failure = CorrectionPolling.passFailure(context, "TX1")

        assertEquals(1, failure?.sessionFailedCount)
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

    // ---- R-194, `Detail-Revisions.dc.html`: every version card carries the real current attribution ----

    @Test
    fun `R_194_revisions_carries_the_real_current_stationId_and_corrected_flag_on_every_version`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(
            transmission("TX1", stationId = "W7NPC").copy(corrected = true),
        )
        db.transcriptDao().insert(
            transcript("V1", "TX1", TranscriptPass.A, "earlier text", isCurrent = false, createdAt = 10L),
        )
        db.transcriptDao().insert(
            transcript("V2", "TX1", TranscriptPass.B, "later text", isCurrent = true, createdAt = 20L),
        )

        val versions = CorrectionPolling.revisions(context, "TX1")

        assertEquals(2, versions.size)
        versions.forEach { version ->
            assertEquals("W7NPC", version.stationId)
            assertTrue(version.corrected)
        }
    }

    @Test
    fun `R_194_revisions_carries_no_stationId_for_an_unattributed_transmission`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1", stationId = null))
        db.transcriptDao().insert(
            transcript("V1", "TX1", TranscriptPass.A, "text", isCurrent = true, createdAt = 10L),
        )

        val versions = CorrectionPolling.revisions(context, "TX1")

        assertEquals(null, versions.single().stationId)
        assertFalse(versions.single().corrected)
    }

    // ---- R-188, `Detail.dc.html`: the current transcript's real recorded confidence ----

    @Test
    fun `R_188_currentTranscriptConfidence_reads_the_real_current_rows_confidence`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1"))
        db.transcriptDao().insert(
            transcript("V1", "TX1", TranscriptPass.A, "earlier", isCurrent = false, createdAt = 10L),
        )
        db.transcriptDao().insert(
            transcript("V2", "TX1", TranscriptPass.B, "later", isCurrent = true, createdAt = 20L).copy(
                confidence = 0.61,
            ),
        )

        val confidence = CorrectionPolling.currentTranscriptConfidence(context, "TX1")

        assertEquals(0.61, confidence)
    }

    @Test
    fun `R_188_currentTranscriptConfidence_is_null_when_no_current_transcript_row_exists`(): Unit = runTest {
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1"))

        assertEquals(null, CorrectionPolling.currentTranscriptConfidence(context, "TX1"))
    }
}
