package org.ort.app.export

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.AttributionState
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.OperatorLocationEntity
import org.ort.data.entity.OperatorLocationSource
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.StationEntity
import org.ort.data.entity.TranscriptEntity
import org.ort.data.entity.TranscriptPass
import org.ort.data.entity.TransmissionEntity
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * FR-EXP-7, AC-171. [ShareCoordinator] builds the content a share-sheet tap actually sends —
 * these tests seed a station carrying every forbidden field (a user-supplied name, notes, spoken
 * grids) plus a real [OperatorLocationEntity], and assert none of it ever reaches the shared
 * bytes, the same "proven with seeded rows" standard
 * [org.ort.app.diagnostics.DiagnosticsBundleBuilderTest] already holds one package over.
 */
@RunWith(RobolectricTestRunner::class)
class ShareCoordinatorTest {

    private lateinit var db: OrtDatabase
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun openDatabase() {
        db = OrtDatabase.create(context)
    }

    private fun session(id: String, startedAt: Long, endedAt: Long? = null) = SessionEntity(
        id = id,
        startedAt = startedAt,
        endedAt = endedAt,
        profileId = null,
        deviceTier = null,
        appVersion = "test",
        terminationReason = null,
        sourceId = null,
        schemaVersion = OrtDatabase.SCHEMA_VERSION,
    )

    private fun transmission(
        id: String,
        sessionId: String,
        threadId: String? = null,
        stationId: String? = null,
        startedAtUtc: Long = 1_000L,
    ) = TransmissionEntity(
        id = id,
        sessionId = sessionId,
        threadId = threadId,
        startedAtUtc = startedAtUtc,
        endedAtUtc = startedAtUtc + 5_000L,
        durationMs = 5_000L,
        audioFormat = "flac/16k/mono",
        preRollMs = 200,
        postRollMs = 200,
        frequencyHz = 146_520_000L,
        frequencyProvenance = "measured",
        mode = "FM",
        signalStrength = null,
        channelName = null,
        voiceprintId = null,
        attributionState = if (stationId != null) AttributionState.CONFIRMED else AttributionState.UNKNOWN,
        stationId = stationId,
        attributionConfidence = if (stationId != null) 0.9 else null,
        attributionSourceTransmissionId = null,
        processingState = TransmissionState.COMPLETE,
        rejectionReason = null,
        samplePosition = 0L,
        monotonicStartNanos = 0L,
        utcOffsetMinutes = 0,
        calibrationId = null,
        executionProvider = null,
    )

    @Test
    fun `AC_171 buildDigestShareFile returns null when there is no session yet`() = runTest {
        assertNull(ShareCoordinator.buildDigestShareFile(context))
    }

    @Test
    fun `AC_171 buildThreadTranscriptShareFile returns null when nothing has a thread yet`() = runTest {
        db.sessionDao().insert(session("S1", 0L))
        db.transmissionDao().insert(transmission("T1", "S1", threadId = null))
        assertNull(ShareCoordinator.buildThreadTranscriptShareFile(context))
    }

    @Test
    fun `AC_171 resolveOverAudioShareFile returns null when nothing is retained on disk`() = runTest {
        db.sessionDao().insert(session("S1", 0L))
        db.transmissionDao().insert(transmission("T1", "S1"))
        assertNull(ShareCoordinator.resolveOverAudioShareFile(context))
    }

    @Test
    fun `AC_171 a shared digest carries the real transcript and callsign but never station knowledge or location`() =
        runTest {
            db.sessionDao().insert(session("S1", 0L, endedAt = 10_000L))
            db.catalogDao().insert(
                StationEntity(
                    id = "ST1",
                    callsign = "KI7ABC",
                    firstHeardAt = null,
                    lastHeardAt = null,
                    transmissionCount = 0,
                    notes = "SECRET-NOTE-lives on Elm Street",
                    userName = "SECRET-NAME-Bob",
                    frequenciesHeard = listOf(146_520_000L),
                    activityByHourDow = "SECRET-PATTERN",
                    potaRefs = null,
                    spokenGrids = listOf("SECRET-GRID-CN87"),
                    ituRegionFromPrefix = null,
                    overCountsByAttributionState = null,
                ),
            )
            db.catalogDao().insert(
                OperatorLocationEntity(
                    profileId = "default",
                    gridSquare = "SECRET-OPERATOR-GRID",
                    source = OperatorLocationSource.MANUAL,
                    updatedAt = 0L,
                ),
            )
            db.transmissionDao().insert(transmission("T1", "S1", stationId = "ST1"))
            db.transcriptDao().insert(
                TranscriptEntity(
                    id = "TR1", transmissionId = "T1", pass = TranscriptPass.B, text = "this is KI7ABC, QRT",
                    modelId = "whisper-small", modelVersion = "1.2.0", quantization = null, decodeParams = null,
                    noSpeechProb = null, confidence = null, isCurrent = true, createdAt = 0L,
                ),
            )

            val shared = ShareCoordinator.buildDigestShareFile(context)
            assertTrue(shared != null && shared.file.isFile)
            val text = shared!!.file.readText(Charsets.UTF_8)

            assertTrue("expected the real callsign", text.contains("KI7ABC"))
            assertTrue("expected the real transcript text", text.contains("this is KI7ABC, QRT"))
            assertTrue("expected no user-supplied name", !text.contains("SECRET-NAME"))
            assertTrue("expected no station note", !text.contains("SECRET-NOTE"))
            assertTrue("expected no station-knowledge pattern", !text.contains("SECRET-PATTERN"))
            assertTrue("expected no spoken grid", !text.contains("SECRET-GRID"))
            assertTrue("expected no operator location", !text.contains("SECRET-OPERATOR-GRID"))
        }

    @Test
    fun `AC_171 a shared thread transcript covers every over in that thread, ordered, and only that thread`() =
        runTest {
            db.sessionDao().insert(session("S1", 0L))
            // TH1 (T1/T2) is the *most recent* thread — both later than TH2's own single over —
            // so ShareCoordinator's own "most recent thread" pick resolves to TH1, not TH2.
            db.transmissionDao().insert(
                transmission("T1", "S1", threadId = "TH1", stationId = "KI7ABC", startedAtUtc = 3_000L),
            )
            db.transmissionDao().insert(
                transmission("T2", "S1", threadId = "TH1", stationId = "W7NPC", startedAtUtc = 2_000L),
            )
            db.transmissionDao().insert(
                transmission("T3", "S1", threadId = "TH2", stationId = "N7ZZZ", startedAtUtc = 1_000L),
            )
            db.transcriptDao().insert(
                TranscriptEntity(
                    id = "TR1", transmissionId = "T1", pass = TranscriptPass.B, text = "second over",
                    modelId = "m", modelVersion = "1", quantization = null, decodeParams = null,
                    noSpeechProb = null, confidence = null, isCurrent = true, createdAt = 0L,
                ),
            )
            db.transcriptDao().insert(
                TranscriptEntity(
                    id = "TR2", transmissionId = "T2", pass = TranscriptPass.B, text = "first over",
                    modelId = "m", modelVersion = "1", quantization = null, decodeParams = null,
                    noSpeechProb = null, confidence = null, isCurrent = true, createdAt = 0L,
                ),
            )
            db.transcriptDao().insert(
                TranscriptEntity(
                    id = "TR3", transmissionId = "T3", pass = TranscriptPass.B, text = "a different thread entirely",
                    modelId = "m", modelVersion = "1", quantization = null, decodeParams = null,
                    noSpeechProb = null, confidence = null, isCurrent = true, createdAt = 0L,
                ),
            )

            val shared = ShareCoordinator.buildThreadTranscriptShareFile(context)
            assertTrue(shared != null)
            val text = shared!!.file.readText(Charsets.UTF_8)

            assertTrue(text.contains("first over"))
            assertTrue(text.contains("second over"))
            assertTrue("expected only TH1's own thread, not TH2's", !text.contains("a different thread entirely"))
            assertTrue(
                "expected the first over before the second",
                text.indexOf("first over") < text.indexOf("second over"),
            )
        }

    @Test
    fun `AC_171 resolveOverAudioShareFile finds the most recent retained audio file, real bytes`() = runTest {
        db.sessionDao().insert(session("S1", 0L))
        val older = transmission("T1", "S1", startedAtUtc = 1_000L)
        val newer = transmission("T2", "S1", startedAtUtc = 2_000L)
        db.transmissionDao().insert(older)
        db.transmissionDao().insert(newer)
        File(context.filesDir, older.audioPath()).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        File(context.filesDir, newer.audioPath()).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(2))
        }

        val shared = ShareCoordinator.resolveOverAudioShareFile(context)
        assertTrue(shared != null)
        assertEquals("audio/flac", shared!!.mimeType)
        assertTrue(shared.file.readBytes().contentEquals(byteArrayOf(2)))
    }
}
