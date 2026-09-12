package org.ort.app.diagnostics.localsave

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.diagnostics.DiagnosticsBundleSpec
import org.ort.app.fieldreport.bundle.FIELD_REPORT_DIR_NAME
import org.ort.app.fieldreport.bundle.VoiceprintEmbeddingsProducer
import org.ort.app.fieldreport.recorder.FieldReportRecorder
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
 * WPDUMP (operator: "just allow a single save dump with checkboxes for EVERYTHING that can be
 * saved"). [LocalSaveBundleBuilder] is the one producer behind that checklist — every category's
 * real bytes, real availability, and the exact same files [FieldReportBundleBuilder]/
 * [org.ort.app.diagnostics.DiagnosticsBundleBuilder]/[org.ort.app.export.DebugDumpBuilder] already
 * define, reused rather than re-derived (this builder's own top doc comment).
 */
@RunWith(RobolectricTestRunner::class)
class LocalSaveBundleBuilderTest {

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

    private fun voiceprint(id: String, boundStationId: String?) = VoiceprintEntity(
        id = id,
        embedding = byteArrayOf(1, 2, 3),
        memberCount = 1,
        centroidUpdatedAt = null,
        boundStationId = boundStationId,
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
    )

    private fun station(id: String, callsign: String) = StationEntity(
        id = id,
        callsign = callsign,
        firstHeardAt = null,
        lastHeardAt = 0L,
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

    private suspend fun writeBundle(
        selected: Set<LocalSaveCategoryId>,
        debugBuild: Boolean = true,
    ): Pair<ByteArray, Map<String, ByteArray>> {
        val target = ByteArrayOutputStream()
        LocalSaveBundleBuilder.write(context, target, selected, debugBuild)
        val bytes = target.toByteArray()
        return bytes to zipEntries(bytes)
    }

    @Test
    fun `closed set is exactly twelve categories, nine of them default on`() {
        assertEquals(12, LocalSaveCategoryId.entries.size)
        assertEquals(9, LocalSaveBundleSpec.defaultSelected.size)
        assertEquals(
            setOf(
                LocalSaveCategoryId.RETAINED_AUDIO,
                LocalSaveCategoryId.VOICEPRINT_EMBEDDINGS,
                LocalSaveCategoryId.SCREEN_FRAMES,
            ),
            LocalSaveBundleSpec.thirdPartyContent,
        )
        assertFalse(
            "third-party content must never default on",
            LocalSaveBundleSpec.defaultSelected.any { it in LocalSaveBundleSpec.thirdPartyContent },
        )
    }

    @Test
    fun `write with the default selection produces exactly the nine default-on files, debug build`() = runTest {
        val (_, entries) = writeBundle(LocalSaveBundleSpec.defaultSelected, debugBuild = true)
        val expected = DiagnosticsBundleSpec.files.map { it.fileName }.toSet() +
            setOf(FieldReportRecorder.LOG_FILE_NAME, "debug-dump.ndjson")
        assertEquals(expected, entries.keys)
    }

    @Test
    fun `the session-recorder log is unavailable on a release build and excluded from the write`() = runTest {
        val (_, entries) = writeBundle(LocalSaveBundleSpec.defaultSelected, debugBuild = false)
        assertFalse(entries.keys.contains(FieldReportRecorder.LOG_FILE_NAME))

        val preview = LocalSaveBundleBuilder.preview(context, debugBuild = false)
        val recorderEntry = preview.entries.single { it.id == LocalSaveCategoryId.SESSION_RECORDER_LOG }
        assertFalse(recorderEntry.available)
        assertTrue(
            "expected the release-build reason in the caption, got '${recorderEntry.caption}'",
            recorderEntry.caption.contains("only runs in debug builds"),
        )
    }

    @Test
    fun `retained audio unavailable with no audio on disk, available once real bytes exist`() = runTest {
        db.sessionDao().insert(session("S1"))
        val tx = transmission("TX1", "S1")
        db.transmissionDao().insert(tx)

        val emptyPreview = LocalSaveBundleBuilder.preview(context)
        val emptyEntry = emptyPreview.entries.single { it.id == LocalSaveCategoryId.RETAINED_AUDIO }
        assertFalse(emptyEntry.available)
        assertTrue(emptyEntry.caption.contains("no retained audio exists on this device yet"))

        val audioFile = File(context.filesDir, tx.audioPath())
        audioFile.parentFile?.mkdirs()
        val audioBytes = byteArrayOf(9, 9, 9, 9)
        audioFile.writeBytes(audioBytes)

        val filledPreview = LocalSaveBundleBuilder.preview(context)
        val filledEntry = filledPreview.entries.single { it.id == LocalSaveCategoryId.RETAINED_AUDIO }
        assertTrue(filledEntry.available)
        assertEquals(audioBytes.size.toLong(), filledEntry.sizeBytes)

        val (_, entries) = writeBundle(setOf(LocalSaveCategoryId.RETAINED_AUDIO))
        val audioEntry = entries.keys.singleOrNull { it.startsWith("audio/") }
        assertTrue("expected exactly one audio entry", audioEntry != null)
        assertTrue(entries.getValue(audioEntry!!).contentEquals(audioBytes))
    }

    @Test
    fun `voiceprint embeddings is unavailable with none resolved, available once one is bound`() = runTest {
        val emptyPreview = LocalSaveBundleBuilder.preview(context)
        val emptyEntry = emptyPreview.entries.single { it.id == LocalSaveCategoryId.VOICEPRINT_EMBEDDINGS }
        assertFalse(emptyEntry.available)
        assertTrue(emptyEntry.caption.contains("no voiceprints have been resolved yet"))

        db.catalogDao().insert(station("ST1", "K7ABC"))
        db.catalogDao().insert(voiceprint("V1", boundStationId = "ST1"))

        val filledPreview = LocalSaveBundleBuilder.preview(context)
        val filledEntry = filledPreview.entries.single { it.id == LocalSaveCategoryId.VOICEPRINT_EMBEDDINGS }
        assertTrue(filledEntry.available)
        assertTrue(filledEntry.sizeBytes > 0)

        val (_, entries) = writeBundle(setOf(LocalSaveCategoryId.VOICEPRINT_EMBEDDINGS))
        assertTrue(entries.containsKey(VoiceprintEmbeddingsProducer.FILE_NAME))
    }

    @Test
    fun `screen frames is unavailable with none captured, available once one is stored`() = runTest {
        val emptyPreview = LocalSaveBundleBuilder.preview(context)
        val emptyEntry = emptyPreview.entries.single { it.id == LocalSaveCategoryId.SCREEN_FRAMES }
        assertFalse(emptyEntry.available)
        assertTrue(emptyEntry.caption.contains("no screen frames have been captured yet"))

        val framesDir = File(File(context.filesDir, FIELD_REPORT_DIR_NAME), "frames")
        framesDir.mkdirs()
        File(framesDir, "0000000001.png").writeBytes(byteArrayOf(1, 2, 3))

        val (_, entries) = writeBundle(setOf(LocalSaveCategoryId.SCREEN_FRAMES))
        assertTrue(entries.containsKey("frames/0000000001.png"))
        assertTrue(entries.getValue("frames/0000000001.png").contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `an unavailable category passed as selected is still excluded from the write — defensive filter`() = runTest {
        // Nothing on disk for any of the three third-party categories — select all three anyway.
        val (_, entries) = writeBundle(
            setOf(
                LocalSaveCategoryId.RETAINED_AUDIO,
                LocalSaveCategoryId.VOICEPRINT_EMBEDDINGS,
                LocalSaveCategoryId.SCREEN_FRAMES,
            ),
        )
        assertTrue("an unavailable category must never contribute an entry", entries.isEmpty())
    }

    @Test
    fun `preview totalBytes for a selection matches the sum of that selection's real written sizes`() = runTest {
        db.sessionDao().insert(session("S1"))
        db.transmissionDao().insert(transmission("TX1", "S1"))

        val selection = setOf(LocalSaveCategoryId.LIFECYCLE_LOG, LocalSaveCategoryId.DEBUG_DUMP_NDJSON)
        val preview = LocalSaveBundleBuilder.preview(context)
        val (_, entries) = writeBundle(selection)

        assertEquals(entries.values.sumOf { it.size.toLong() }, preview.totalBytes(selection))
    }

    @Test
    fun `the eight reused ungated entries are byte-identical to the field-report bundle's own ungated files`() =
        runTest {
            val fieldReportBundle = ByteArrayOutputStream().also {
                org.ort.app.fieldreport.bundle.FieldReportBundleBuilder.write(context, it, emptySet())
            }.toByteArray()
            val fieldReportEntries = zipEntries(fieldReportBundle)

            val (_, localSaveEntries) = writeBundle(LocalSaveBundleSpec.defaultSelected)

            for (fileName in fieldReportEntries.keys) {
                assertTrue(
                    "expected '$fileName' in the local save too",
                    localSaveEntries.containsKey(fileName),
                )
                assertTrue(
                    "expected '$fileName' byte-identical between the field-report bundle and the local save",
                    fieldReportEntries.getValue(fileName).contentEquals(localSaveEntries.getValue(fileName)),
                )
            }
        }
}
