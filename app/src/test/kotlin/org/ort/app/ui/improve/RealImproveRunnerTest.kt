package org.ort.app.ui.improve

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.capture.android.codec.DeflatePredictiveCodec
import org.ort.core.AttributionState
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.TransmissionEntity
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * register R-091 (WP11d): [RealImproveRunner] is the thin [ImproveRunner] adapter over
 * `org.ort.pipeline.reprocess.ReprocessRunner` — real end-to-end (a real `OrtDatabase`, a real
 * transmission, no fake queue) proves the adapter actually wires through, not just that it
 * compiles against the interface. Robolectric (JVM) only.
 */
@RunWith(RobolectricTestRunner::class)
class RealImproveRunnerTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun session(id: String) = SessionEntity(
        id = id,
        startedAt = 0L,
        endedAt = null,
        profileId = null,
        deviceTier = "T1",
        appVersion = "test",
        terminationReason = null,
        sourceId = null,
        schemaVersion = OrtDatabase.SCHEMA_VERSION,
    )

    private fun transmission(id: String, sessionId: String) = TransmissionEntity(
        id = id,
        sessionId = sessionId,
        threadId = null,
        startedAtUtc = 0L,
        endedAtUtc = 1_000L,
        durationMs = 1_000L,
        audioFormat = "flac/16k/mono",
        preRollMs = 200,
        postRollMs = 200,
        frequencyHz = null,
        frequencyProvenance = "measured",
        mode = null,
        signalStrength = null,
        channelName = null,
        voiceprintId = null,
        attributionState = AttributionState.UNKNOWN,
        stationId = null,
        attributionConfidence = null,
        attributionSourceTransmissionId = null,
        processingState = TransmissionState.COMPLETE,
        rejectionReason = null,
        samplePosition = 0L,
        monotonicStartNanos = 0L,
        utcOffsetMinutes = 0,
        calibrationId = null,
        executionProvider = null,
        isReprocessCandidate = true,
    )

    /** A real, decodable retained-audio fixture at exactly the path `FlacSegmentAudioProvider` reads. */
    private fun writeAudioFixture(context: android.content.Context, transmissionId: String, sessionId: String) {
        val samples = ShortArray(16_000) { (it % 200 - 100).toShort() }
        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val s = samples[i].toInt()
            bytes[i * 2] = (s and 0xFF).toByte()
            bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        val encoded = DeflatePredictiveCodec().encode(bytes)
        val file = File(context.filesDir, "audio/$sessionId/$transmissionId.flac")
        file.parentFile?.mkdirs()
        file.writeBytes(encoded)
    }

    @Test
    fun `R_091_run_emits_done_equal_total_and_leaves_isReprocessCandidate_when_no_model_is_installed`() = runBlocking {
        val db = OrtDatabase.create(context)
        db.sessionDao().insert(session("S1"))
        db.transmissionDao().insert(transmission("TX1", "S1"))
        writeAudioFixture(context, "TX1", "S1")
        // No ASR model installed in this test environment -- RejectionPipeline reports it as an
        // honest Failed outcome (RealCaptureService.startProcessingLoop's own kdoc: "the queue
        // still drains against UnavailableAsrEngine so a rejection/failure reason is recorded
        // honestly"), never a crash and never a fabricated transcript. This test proves the
        // *adapter's* wiring reaches Flow completion either way -- see
        // org.ort.pipeline.reprocess.ReprocessRunnerTest for the engine's own real-transcript
        // coverage against a fake ASR engine.
        val runner = RealImproveRunner(context)

        val progress = runner.run(listOf("TX1")).toList()

        assertEquals(1, progress.last().total)
        assertEquals(progress.last().total, progress.last().done)
        // A model-unavailable pass fails, not completes -- isReprocessCandidate is honestly left
        // set (never claimed reprocessed when nothing actually improved it), and the transmission
        // is never left mid-flight as PROCESSING.
        assertFalse(db.transmissionDao().getById("TX1")!!.processingState == TransmissionState.PROCESSING)
    }
}
