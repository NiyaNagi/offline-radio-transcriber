package org.ort.app.diagnostics

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.AttributionState
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.StationEntity
import org.ort.data.entity.TransmissionEntity
import org.ort.data.entity.VoiceprintEntity
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * WP11e (register R-137, `Settings-Diagnostics.dc.html`, FR-OBS-1/3, constitution V). The
 * structural half of AC-109/AC-120: [DiagnosticsBundleBuilder] never queries a voiceprint's
 * embedding or a station's user-supplied name/notes to produce any of the seven board files (see
 * each producer's own source), so a voiceprint and a given station name planted in the same
 * database the bundle reads from are proven, not merely assumed, to never reach the zip.
 */
@RunWith(RobolectricTestRunner::class)
class DiagnosticsBundleBuilderTest {

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
        attributionState = AttributionState.CONFIRMED,
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
    )

    private fun zipEntries(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                out[entry.name] = zip.readBytes()
                entry = zip.nextEntry
            }
        }
        return out
    }

    private suspend fun writeBundle(includeAudio: Boolean = false): Pair<ByteArray, Map<String, ByteArray>> {
        val target = ByteArrayOutputStream()
        DiagnosticsBundleBuilder.write(context, target, includeAudio = includeAudio)
        val bytes = target.toByteArray()
        return bytes to zipEntries(bytes)
    }

    @Test
    fun `FR_OBS_3 the written bundle contains exactly the board's seven files`() = runTest {
        val (_, entries) = writeBundle()
        assertEquals(DiagnosticsBundleSpec.files.map { it.fileName }.toSet(), entries.keys)
    }

    @Test
    fun `AC_109 no voiceprint or embedding bytes appear in the bundle`() = runTest {
        val marker = "VOICEPRINT-EMBEDDING-MARKER-DO-NOT-LEAK"
        db.catalogDao().insert(
            VoiceprintEntity(
                id = "V1",
                embedding = marker.toByteArray(Charsets.UTF_8),
                memberCount = 1,
                centroidUpdatedAt = null,
                boundStationId = null,
                bindingConfidence = null,
                lastConfirmedAt = null,
                isEnrolled = true,
                enrolmentObservationCount = 1,
                enrolmentSessionIds = null,
                enrolledAt = null,
                lastMatchedAt = null,
                bindingSource = null,
                embeddingModelId = "test-embedder",
                embeddingModelVersion = "1",
            ),
        )

        val (bytes, entries) = writeBundle()
        val wholeZipText = bytes.toString(Charsets.ISO_8859_1)
        assertFalse(wholeZipText.contains(marker))
        entries.forEach { (_, content) -> assertFalse(content.toString(Charsets.ISO_8859_1).contains(marker)) }
    }

    @Test
    fun `AC_120 station knowledge — a user-given name and note — never appears in the bundle`() = runTest {
        val givenName = "Dave-The-Given-Name-Marker"
        val note = "NOTE-MARKER-lives-on-Whidbey"
        db.catalogDao().insert(
            StationEntity(
                id = "ST1",
                callsign = "K7ABC",
                firstHeardAt = null,
                lastHeardAt = null,
                transmissionCount = 0,
                isUserPinned = false,
                notes = note,
                userName = givenName,
                frequenciesHeard = null,
                activityByHourDow = null,
                potaRefs = null,
                spokenGrids = null,
                ituRegionFromPrefix = null,
                overCountsByAttributionState = null,
            ),
        )

        val (bytes, _) = writeBundle()
        val wholeZipText = bytes.toString(Charsets.ISO_8859_1)
        assertFalse(wholeZipText.contains(givenName))
        assertFalse(wholeZipText.contains(note))
        assertFalse(wholeZipText.contains("K7ABC"))
    }

    @Test
    fun `R_137 preview sizes match the written entry sizes exactly`() = runTest {
        db.sessionDao().insert(session("S1"))
        db.transmissionDao().insert(transmission("TX1", "S1"))

        val preview = DiagnosticsBundleBuilder.preview(context)
        val (_, entries) = writeBundle()

        assertEquals(DiagnosticsBundleSpec.files.size, preview.entries.size)
        for (previewEntry in preview.entries) {
            val written = entries.getValue(previewEntry.fileName)
            assertEquals(
                "size mismatch for ${previewEntry.fileName}",
                written.size.toLong(),
                previewEntry.sizeBytes,
            )
        }
        assertEquals(entries.values.sumOf { it.size.toLong() }, preview.totalBytes)
    }

    @Test
    fun `FR_OBS_3 includeAudio false excludes audio even when retained audio exists on disk`() = runTest {
        db.sessionDao().insert(session("S1"))
        val tx = transmission("TX1", "S1")
        db.transmissionDao().insert(tx)
        val audioFile = File(context.filesDir, tx.audioPath())
        audioFile.parentFile?.mkdirs()
        audioFile.writeBytes(byteArrayOf(1, 2, 3, 4))

        val (_, entries) = writeBundle(includeAudio = false)

        assertTrue(entries.keys.none { it.startsWith("audio/") })
    }

    @Test
    fun `FR_OBS_3 includeAudio true attaches real retained audio that exists on disk`() = runTest {
        db.sessionDao().insert(session("S1"))
        val tx = transmission("TX1", "S1")
        db.transmissionDao().insert(tx)
        val audioFile = File(context.filesDir, tx.audioPath())
        audioFile.parentFile?.mkdirs()
        val audioBytes = byteArrayOf(1, 2, 3, 4)
        audioFile.writeBytes(audioBytes)

        val (_, entries) = writeBundle(includeAudio = true)

        val audioEntry = entries.keys.singleOrNull { it.startsWith("audio/") }
        assertTrue("expected exactly one audio entry, got ${entries.keys}", audioEntry != null)
        assertTrue(entries.getValue(audioEntry!!).contentEquals(audioBytes))
    }

    @Test
    fun `FR_OBS_3 includeAudio true with no retained audio on disk still writes zero audio entries`() = runTest {
        db.sessionDao().insert(session("S1"))
        db.transmissionDao().insert(transmission("TX1", "S1"))

        val (_, entries) = writeBundle(includeAudio = true)

        assertTrue(entries.keys.none { it.startsWith("audio/") })
    }
}
