package org.ort.pipeline.reprocess

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.asrapi.fake.FakeAsrEngine
import org.ort.capture.android.codec.DeflatePredictiveCodec
import org.ort.core.AssetRef
import org.ort.core.AttributionState
import org.ort.core.Tier
import org.ort.core.TransmissionState
import org.ort.core.Ulid
import org.ort.data.OrtDatabase
import org.ort.data.PassRunOutcome
import org.ort.data.entity.TranscriptEntity
import org.ort.data.entity.TranscriptPass
import org.ort.data.entity.WorkQueueItemEntity
import org.ort.pipeline.Pass
import org.ort.pipeline.PipelineTestFixtures
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.ShedStatus
import org.ort.pipeline.passb.PassBFactory
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * register R-091 (FR-REP-1, FR-REP-6, FR-REP-9, FR-REP-11; FR-RUN-8; register R-143): before
 * [ReprocessRunner] existed, `:pipeline` had no way to re-run a pass over a historical transmission
 * at all (`org.ort.app.ui.improve.ImproveRunner`'s own kdoc). Robolectric only (JVM) — nothing here
 * is device-verified.
 *
 * **`kotlinx.coroutines.runBlocking`, deliberately, never `kotlinx.coroutines.test.runTest`.**
 * `runTest`'s virtual-time auto-advance (it fast-forwards to the next scheduled event whenever
 * its own dispatcher looks idle) races [org.ort.data.WorkQueue.runLeased]'s real
 * `withTimeoutOrNull`: Room dispatches suspend DAO calls onto its own real executor thread, so a
 * coroutine awaiting one *looks* idle to `runTest`'s scheduler, which then fires the (nominally
 * 20 s) timeout immediately — found the hard way, reproducibly, as a `Pass` that genuinely writes
 * through `TranscriptDao`/`TransmissionDao` being reported `Errored("timeout")` on every attempt
 * until `WorkQueue`'s bounded retry exhausted it to `FAILED`, even though no real 20 s ever
 * elapsed. `RealCaptureServiceTest`'s own kdoc already names the same real-dispatcher reasoning
 * for using real time instead of a virtual one; this class inherits it for the same reason.
 */
@RunWith(RobolectricTestRunner::class)
class ReprocessRunnerTest {

    private lateinit var db: OrtDatabase
    private lateinit var filesDir: File

    @Before
    fun setUp() = runBlocking {
        db = OrtDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
        filesDir = ApplicationProvider.getApplicationContext<android.content.Context>().filesDir
        ReprocessStatus.reset()
        CaptureState.idle(clearSession = true)
        ShedStatus.reset()
        db.sessionDao().insert(PipelineTestFixtures.session("S1"))
    }

    // ---------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------

    private suspend fun seedTransmission(
        id: String,
        text: String = "old text",
        corrected: Boolean = false,
        attributionState: AttributionState = AttributionState.UNKNOWN,
        stationId: String? = null,
    ) {
        val tx = PipelineTestFixtures.transmission(id, sessionId = "S1").copy(
            processingState = TransmissionState.COMPLETE,
            corrected = corrected,
            attributionState = attributionState,
            stationId = stationId,
        )
        db.transmissionDao().insert(tx)
        db.transcriptDao().supersede(
            TranscriptEntity(
                id = "$id-seed",
                transmissionId = id,
                pass = TranscriptPass.B,
                text = text,
                modelId = "seed",
                modelVersion = "0",
                quantization = null,
                decodeParams = null,
                noSpeechProb = null,
                confidence = 0.5,
                isCurrent = true,
                createdAt = 0L,
            ),
        )
    }

    /** A real, decodable retained-audio fixture at exactly the path
     * [org.ort.pipeline.passb.FlacSegmentAudioProvider] reads. */
    private fun writeAudioFixture(transmissionId: String, sessionId: String = "S1") {
        val samples = ShortArray(16_000) { (it % 200 - 100).toShort() } // 1s @16kHz, a real tone, not silence
        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val s = samples[i].toInt()
            bytes[i * 2] = (s and 0xFF).toByte()
            bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        val encoded = DeflatePredictiveCodec().encode(bytes)
        val file = File(filesDir, "audio/$sessionId/$transmissionId.flac")
        file.parentFile?.mkdirs()
        file.writeBytes(encoded)
    }

    private class InstantCompletingPass : Pass {
        override suspend fun run(item: WorkQueueItemEntity): PassRunOutcome =
            PassRunOutcome.Finished(TransmissionState.COMPLETE)
    }

    private sealed interface ScriptedOutcome {
        data class Complete(val text: String, val state: AttributionState, val stationId: String?) : ScriptedOutcome
        data object Rejected : ScriptedOutcome
    }

    /** Writes through the exact real DAO paths `DataPassBResultSink` uses -- including the
     * `corrected = 0` guard on attribution -- without needing a real ASR engine or audio file. */
    private class ScriptedPass(private val db: OrtDatabase, private val scriptFor: (String) -> ScriptedOutcome) : Pass {
        override suspend fun run(item: WorkQueueItemEntity): PassRunOutcome =
            when (val outcome = scriptFor(item.transmissionId)) {
                is ScriptedOutcome.Complete -> {
                    db.transcriptDao().supersede(
                        TranscriptEntity(
                            id = Ulid.generate().value,
                            transmissionId = item.transmissionId,
                            pass = TranscriptPass.B,
                            text = outcome.text,
                            modelId = "test",
                            modelVersion = "1",
                            quantization = null,
                            decodeParams = null,
                            noSpeechProb = null,
                            confidence = 0.9,
                            isCurrent = true,
                            createdAt = 0L,
                        ),
                    )
                    db.transmissionDao().updateAttribution(
                        id = item.transmissionId,
                        state = outcome.state,
                        stationId = outcome.stationId,
                        confidence = 0.9,
                        sourceTransmissionId = null,
                    )
                    PassRunOutcome.Finished(TransmissionState.COMPLETE)
                }
                ScriptedOutcome.Rejected -> PassRunOutcome.Finished(TransmissionState.REJECTED)
            }
    }

    // ---------------------------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------------------------

    @Test
    @Requirement("FR-REP-1", "FR-REP-9", "R-091")
    fun `FR_REP_1_reprocess_re_runs_pass_b_at_the_current_tier_and_supersedes_the_transcript`() = runBlocking {
        val txId = "TX-REP1"
        seedTransmission(txId, text = "old text")
        writeAudioFixture(txId)

        val engine =
            FakeAsrEngine(FakeAsrEngine.Behaviour.Returns(FakeAsrEngine.defaultResult(text = "new transcript text")))
        val tierCalls = mutableListOf<Tier>()
        val runner = ReprocessRunner(
            db = db,
            filesDir = filesDir,
            currentTier = {
                tierCalls.add(Tier.T2)
                Tier.T2
            },
            passFor = { tier ->
                PassBFactory.create(filesDir, db, engine, AssetRef("fake-asr-model", "1"), "cpu", tier = tier)
            },
        )

        val progress = runner.run(listOf(txId)).toList()

        assertEquals("current tier must actually be threaded through, not hardcoded", listOf(Tier.T2), tierCalls)
        assertEquals(ReprocessProgress(1, 1, txId), progress.last())

        val versions = db.transcriptDao().getAllVersions(txId)
        assertTrue("the old transcript must stay reachable, not deleted (constitution III)", versions.size >= 2)
        assertEquals("new transcript text", db.transcriptDao().getCurrent(txId)?.text)
        assertTrue(versions.any { it.text == "old text" && !it.isCurrent })

        val summary = (ReprocessStatus.state as ReprocessStatus.State.Done).summary
        assertEquals(1, summary.transcriptsChanged)
    }

    @Test
    @Requirement("FR-REP-6", "R-091")
    fun `FR_REP_6_reprocess_pauses_while_capture_is_busy`() = runBlocking {
        val txId = "TX-REP6"
        seedTransmission(txId)
        var busyChecks = 0
        val runner = ReprocessRunner(
            db = db,
            filesDir = filesDir,
            currentTier = { Tier.T0 },
            passFor = { InstantCompletingPass() },
            isCaptureBusy = {
                busyChecks++
                busyChecks <= 3
            },
            tuning = ReprocessTuning(yieldPollIntervalMillis = 10L),
        )

        val progress = runner.run(listOf(txId)).toList()

        assertTrue("must have actually observed the busy state, not skipped it", busyChecks > 3)
        assertEquals(1, progress.last().done)
        assertTrue(
            "must finish Done, not stay stuck Paused",
            ReprocessStatus.state is ReprocessStatus.State.Done,
        )
    }

    @Test
    @Requirement("FR-RUN-8", "R-091")
    fun `FR_RUN_8_reprocess_is_idempotent`() = runBlocking {
        val txId = "TX-RUN8"
        seedTransmission(txId)
        val pass = InstantCompletingPass()

        val first = ReprocessRunner(db, filesDir, currentTier = {
            Tier.T0
        }, passFor = { pass }).run(listOf(txId)).toList()
        val second = ReprocessRunner(db, filesDir, currentTier = {
            Tier.T0
        }, passFor = { pass }).run(listOf(txId)).toList()

        assertEquals(1, first.last().done)
        assertEquals(1, second.last().done)
        assertEquals(TransmissionState.COMPLETE, db.transmissionDao().getById(txId)?.processingState)
    }

    @Test
    @Requirement("R-143", "FR-REP-11")
    fun `R_143_done_summary_counts_real_changes`() = runBlocking {
        seedTransmission("TX-CHANGE", text = "old text", attributionState = AttributionState.UNKNOWN)
        seedTransmission("TX-SAME", text = "old text", attributionState = AttributionState.UNKNOWN)
        seedTransmission("TX-REJECT", text = "old text")
        seedTransmission(
            "TX-CORRECTED",
            text = "locked text",
            corrected = true,
            attributionState = AttributionState.CONFIRMED,
            stationId = "LOCKED1",
        )

        val pass = ScriptedPass(db) { id ->
            when (id) {
                "TX-CHANGE" -> ScriptedOutcome.Complete("new text", AttributionState.CONFIRMED, "NEW1")
                "TX-SAME" -> ScriptedOutcome.Complete("old text", AttributionState.UNKNOWN, null)
                "TX-REJECT" -> ScriptedOutcome.Rejected
                "TX-CORRECTED" -> ScriptedOutcome.Complete(
                    "locked text (better)",
                    AttributionState.CONFIRMED,
                    "SHOULD-NOT-STICK",
                )
                else -> error("unexpected id $id")
            }
        }
        val runner = ReprocessRunner(db, filesDir, currentTier = { Tier.T0 }, passFor = { pass })

        runner.run(listOf("TX-CHANGE", "TX-SAME", "TX-REJECT", "TX-CORRECTED")).toList()

        val summary = (ReprocessStatus.state as ReprocessStatus.State.Done).summary
        assertEquals(4, summary.total)
        assertEquals("TX-CHANGE and TX-CORRECTED both got new transcript text", 2, summary.transcriptsChanged)
        assertEquals("only TX-CHANGE's attribution was actually free to change", 1, summary.attributionsChanged)
        assertEquals(1, summary.rejected)
        assertEquals(1, summary.correctedCount)

        // The structural guard this class relies on, not re-implements: a corrected transmission's
        // attribution never moves, even though its transcript legitimately can.
        val corrected = db.transmissionDao().getById("TX-CORRECTED")
        assertEquals(AttributionState.CONFIRMED, corrected?.attributionState)
        assertEquals("LOCKED1", corrected?.stationId)
        assertEquals("locked text (better)", db.transcriptDao().getCurrent("TX-CORRECTED")?.text)
    }

    @Test
    @Requirement("R-091")
    fun `an empty transmission list completes immediately with an honest empty summary`() = runBlocking {
        val progress = ReprocessRunner(db, filesDir, passFor = { InstantCompletingPass() }).run(emptyList()).toList()

        assertEquals(listOf(ReprocessProgress(0, 0, null)), progress)
        val summary = (ReprocessStatus.state as ReprocessStatus.State.Done).summary
        assertEquals(0, summary.total)
    }

    @Test
    @Requirement("R-091")
    fun `requesting an unsupported pass throws rather than silently no-opping`() = runBlocking {
        var threw = false
        try {
            ReprocessRunner(db, filesDir, passFor = { InstantCompletingPass() })
                .run(listOf("TX-ANY"), passes = setOf(org.ort.core.PassId.C_SPOT))
                .toList()
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("Pass C has no runner in :pipeline yet -- must not be silently accepted", threw)
    }
}
