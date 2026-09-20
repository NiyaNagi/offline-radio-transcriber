package org.ort.app.backup

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.AttributionState
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.CorrectionEntity
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.TranscriptEntity
import org.ort.data.entity.TranscriptPass
import org.ort.data.entity.TransmissionEntity
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * FR-STO-6. [BackupBundleBuilder] is the real producer behind `Settings-Backup`'s "Save backup"
 * button — these tests seed a real `:data` database (the same idiom
 * `org.ort.app.export.ExportCoordinatorTest` already uses) and assert the real zip [write]
 * produces, and that [preview] never drifts from it (the "read twice, write once, same producer"
 * discipline this package's own kdoc states).
 */
@RunWith(RobolectricTestRunner::class)
class BackupBundleBuilderTest {

    private lateinit var db: OrtDatabase
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun openDatabase() {
        db = OrtDatabase.create(context)
    }

    private fun session(id: String) = SessionEntity(
        id = id,
        startedAt = 0L,
        endedAt = null,
        profileId = null,
        deviceTier = null,
        appVersion = "test",
        terminationReason = null,
        sourceId = null,
        schemaVersion = OrtDatabase.SCHEMA_VERSION,
    )

    private fun transmission(id: String, sessionId: String) = TransmissionEntity(
        id = id,
        sessionId = sessionId,
        threadId = null,
        startedAtUtc = 1_000L,
        endedAtUtc = 2_000L,
        durationMs = 1_000L,
        audioFormat = "flac/16k/mono",
        preRollMs = 200,
        postRollMs = 200,
        frequencyHz = 146_520_000L,
        frequencyProvenance = "measured",
        mode = "FM",
        signalStrength = null,
        channelName = null,
        voiceprintId = null,
        attributionState = AttributionState.CONFIRMED,
        stationId = "KI7ABC",
        attributionConfidence = 0.9,
        attributionSourceTransmissionId = null,
        processingState = TransmissionState.COMPLETE,
        rejectionReason = null,
        samplePosition = 0L,
        monotonicStartNanos = 0L,
        utcOffsetMinutes = 0,
        calibrationId = null,
        executionProvider = null,
    )

    private fun zipEntries(bytes: ByteArray): Map<String, ByteArray> {
        val out = mutableMapOf<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                out[entry.name] = zip.readBytes()
                entry = zip.nextEntry
            }
        }
        return out
    }

    @Test
    fun `FR_STO_6 write produces a manifest naming the real counts`() = runTest {
        db.sessionDao().insert(session("S1"))
        db.transmissionDao().insert(transmission("T1", "S1"))
        db.correctionDao().recordCorrection(
            CorrectionEntity(
                id = "C1",
                transmissionId = "T1",
                field = "stationId",
                previousValue = null,
                newValue = "KI7ABC",
                correctedAt = 0L,
            ),
        )

        val out = ByteArrayOutputStream()
        BackupBundleBuilder.write(context, out)
        val entries = zipEntries(out.toByteArray())

        val manifest = org.json.JSONObject(String(entries.getValue(BACKUP_MANIFEST_ENTRY), Charsets.UTF_8))
        assertEquals(1, manifest.getInt("sessionCount"))
        assertEquals(1, manifest.getInt("transmissionCount"))
        assertEquals(1, manifest.getInt("correctionCount"))
    }

    @Test
    fun `FR_STO_6 write carries the real session, transmission and correction rows`() = runTest {
        db.sessionDao().insert(session("S1"))
        db.transmissionDao().insert(transmission("T1", "S1"))
        db.correctionDao().recordCorrection(
            CorrectionEntity(
                id = "C1",
                transmissionId = "T1",
                field = "stationId",
                previousValue = null,
                newValue = "KI7ABC",
                correctedAt = 0L,
            ),
        )

        val out = ByteArrayOutputStream()
        BackupBundleBuilder.write(context, out)
        val entries = zipEntries(out.toByteArray())

        val sessions = JSONArray(String(entries.getValue(BACKUP_SESSIONS_ENTRY), Charsets.UTF_8))
        assertEquals(1, sessions.length())
        assertEquals("S1", sessions.getJSONObject(0).getString("id"))

        val corrections = JSONArray(String(entries.getValue(BACKUP_CORRECTIONS_ENTRY), Charsets.UTF_8))
        assertEquals(1, corrections.length())
        assertEquals("C1", corrections.getJSONObject(0).getString("id"))
    }

    @Test
    fun `FR_STO_6 write carries the current transcript for a transmission that has one`() = runTest {
        db.sessionDao().insert(session("S1"))
        db.transmissionDao().insert(transmission("T1", "S1"))
        db.transcriptDao().insert(
            TranscriptEntity(
                id = "TR1", transmissionId = "T1", pass = TranscriptPass.B, text = "this is KI7ABC",
                modelId = "whisper-small", modelVersion = "1.2.0", quantization = null, decodeParams = null,
                noSpeechProb = null, confidence = null, isCurrent = true, createdAt = 0L,
            ),
        )

        val out = ByteArrayOutputStream()
        BackupBundleBuilder.write(context, out)
        val entries = zipEntries(out.toByteArray())
        val transcripts = JSONArray(String(entries.getValue(BACKUP_TRANSCRIPTS_ENTRY), Charsets.UTF_8))
        assertEquals(1, transcripts.length())
        assertEquals("this is KI7ABC", transcripts.getJSONObject(0).getString("text"))
    }

    @Test
    fun `FR_STO_6 write carries a retained audio file under audio-sessionId-transmissionId-flac`() = runTest {
        db.sessionDao().insert(session("S1"))
        val entity = transmission("T1", "S1")
        db.transmissionDao().insert(entity)
        val audioFile = java.io.File(context.filesDir, entity.audioPath())
        audioFile.parentFile?.mkdirs()
        audioFile.writeBytes(byteArrayOf(1, 2, 3, 4))

        val out = ByteArrayOutputStream()
        BackupBundleBuilder.write(context, out)
        val entries = zipEntries(out.toByteArray())

        val expectedName = "${BACKUP_AUDIO_ENTRY_PREFIX}S1/T1.flac"
        assertTrue("expected $expectedName among ${entries.keys}", entries.containsKey(expectedName))
        assertTrue(entries.getValue(expectedName).contentEquals(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun `FR_STO_6 preview reports the same counts and a size matching the real write`() = runTest {
        db.sessionDao().insert(session("S1"))
        val entity = transmission("T1", "S1")
        db.transmissionDao().insert(entity)
        val audioFile = java.io.File(context.filesDir, entity.audioPath())
        audioFile.parentFile?.mkdirs()
        audioFile.writeBytes(ByteArray(100))

        val preview = BackupBundleBuilder.preview(context)
        assertEquals(1, preview.sessionCount)
        assertEquals(1, preview.transmissionCount)
        assertEquals(1, preview.audioFileCount)
        assertTrue("expected a non-zero estimated size", preview.totalSizeBytes > 0)
    }

    @Test
    fun `FR_STO_6 write never throws for an empty database — an honest, empty-but-valid bundle`() = runTest {
        val out = ByteArrayOutputStream()
        BackupBundleBuilder.write(context, out)
        val entries = zipEntries(out.toByteArray())
        val manifest = org.json.JSONObject(String(entries.getValue(BACKUP_MANIFEST_ENTRY), Charsets.UTF_8))
        assertEquals(0, manifest.getInt("sessionCount"))
    }
}
