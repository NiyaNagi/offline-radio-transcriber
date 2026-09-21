package org.ort.app.fieldreport.bundle

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.fieldreport.recorder.FakeScreenFrameCapturer
import org.ort.app.fieldreport.recorder.FieldReportRecorder
import org.ort.app.fieldreport.recorder.RecorderDestination
import org.ort.core.AttributionState
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.TransmissionEntity
import org.ort.testing.TestClock
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * FR-OBS-8/FR-OBS-9: [FieldReportBundleBuilder] never drifts between what it *shows* and what it
 * *sends* ([preview] vs. [write], the same file-set, the same byte counts), the ungated set is
 * exactly `FieldReportBundleSpec.ungatedFiles` regardless of which gated categories are chosen
 * (AC-143), and a gated category's own files reach the bundle only when that category is included
 * (AC-148 — proven here for `SCREEN_FRAMES`; `FieldReportRecorderTest`/`FrameStoreTest` own the
 * recorder's/store's side of that guarantee, this test owns the *bundle's* side: a frame that
 * exists on disk must never leak into an upload whose categories do not name it).
 */
@RunWith(RobolectricTestRunner::class)
class FieldReportBundleBuilderTest {

    private lateinit var db: OrtDatabase
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun openDatabase() {
        db = OrtDatabase.create(context)
    }

    @After
    fun shutdownRecorder() {
        FieldReportRecorder.shutdown()
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

    private suspend fun writeBundle(
        categories: Set<FieldReportGatedCategory> = emptySet(),
    ): Pair<ByteArray, Map<String, ByteArray>> {
        val target = ByteArrayOutputStream()
        FieldReportBundleBuilder.write(context, target, categories)
        val bytes = target.toByteArray()
        return bytes to zipEntries(bytes)
    }

    @Test
    fun `AC_143 with no category on, the bundle contains exactly the ungated set`() = runTest {
        val (_, entries) = writeBundle(categories = emptySet())
        assertEquals(FieldReportBundleSpec.ungatedFiles.map { it.fileName }.toSet(), entries.keys)
    }

    @Test
    fun `AC_143 turning one category on adds only that category's own files, the ungated set unchanged`() = runTest {
        val (_, before) = writeBundle(categories = emptySet())

        db.sessionDao().insert(session("S1"))
        val tx = transmission("TX1", "S1")
        db.transmissionDao().insert(tx)
        val audioFile = File(context.filesDir, tx.audioPath())
        audioFile.parentFile?.mkdirs()
        audioFile.writeBytes(byteArrayOf(9, 9, 9))

        val (_, after) = writeBundle(categories = setOf(FieldReportGatedCategory.RETAINED_AUDIO))

        assertEquals(before.keys, after.keys.filterNot { it.startsWith("audio/") }.toSet())
        assertTrue(after.keys.any { it.startsWith("audio/") })
    }

    @Test
    fun `AC_148 a screen frame on disk never reaches the bundle unless SCREEN_FRAMES is included`() = runTest {
        val framesDir = File(File(context.filesDir, FIELD_REPORT_DIR_NAME), "frames")
        framesDir.mkdirs()
        File(framesDir, "0000000001.png").writeBytes(byteArrayOf(1, 2, 3))

        val (_, withoutCategory) = writeBundle(categories = emptySet())
        assertTrue(
            "a stored frame must never join the ungated set",
            withoutCategory.keys.none { it.startsWith("frames/") },
        )

        val (_, withCategory) = writeBundle(categories = setOf(FieldReportGatedCategory.SCREEN_FRAMES))
        assertTrue(withCategory.keys.contains("frames/0000000001.png"))
        assertTrue(withCategory.getValue("frames/0000000001.png").contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `AC_148 with no frame on disk, SCREEN_FRAMES included still writes zero frame entries`() = runTest {
        val (_, entries) = writeBundle(categories = setOf(FieldReportGatedCategory.SCREEN_FRAMES))
        assertTrue(entries.keys.none { it.startsWith("frames/") })
    }

    @Test
    fun `preview totals and per-file sizes match the written entry sizes exactly, across every category`() = runTest {
        db.sessionDao().insert(session("S1"))
        val tx = transmission("TX1", "S1")
        db.transmissionDao().insert(tx)
        val audioFile = File(context.filesDir, tx.audioPath())
        audioFile.parentFile?.mkdirs()
        audioFile.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        val framesDir = File(File(context.filesDir, FIELD_REPORT_DIR_NAME), "frames")
        framesDir.mkdirs()
        File(framesDir, "0000000001.png").writeBytes(byteArrayOf(7, 7))

        val categories = setOf(
            FieldReportGatedCategory.RETAINED_AUDIO,
            FieldReportGatedCategory.VOICEPRINT_EMBEDDINGS,
            FieldReportGatedCategory.SCREEN_FRAMES,
        )
        val preview = FieldReportBundleBuilder.preview(context, categories)
        val (_, entries) = writeBundle(categories)

        assertEquals(entries.keys, preview.entries.map { it.fileName }.toSet())
        for (previewEntry in preview.entries) {
            assertEquals(
                "size mismatch for ${previewEntry.fileName}",
                entries.getValue(previewEntry.fileName).size.toLong(),
                previewEntry.sizeBytes,
            )
        }
        assertEquals(entries.values.sumOf { it.size.toLong() }, preview.totalBytes)
    }

    @Test
    fun `preview with no category chosen carries no category label on any entry`() = runTest {
        val preview = FieldReportBundleBuilder.preview(context, emptySet())
        assertTrue(preview.entries.all { it.category == null })
    }

    @Test
    fun `preview labels each gated entry with the category that produced it`() = runTest {
        val framesDir = File(File(context.filesDir, FIELD_REPORT_DIR_NAME), "frames")
        framesDir.mkdirs()
        File(framesDir, "0000000001.png").writeBytes(byteArrayOf(1))

        val preview = FieldReportBundleBuilder.preview(context, setOf(FieldReportGatedCategory.SCREEN_FRAMES))
        val frameEntry = preview.entries.single { it.fileName == "frames/0000000001.png" }
        assertEquals(FieldReportGatedCategory.SCREEN_FRAMES, frameEntry.category)
        assertFalse(preview.entries.any { it.fileName != "frames/0000000001.png" && it.category != null })
    }

    /**
     * D55/FR-OBS-7/FR-OBS-8: the literal "Tests first" case — a bundle built from a screen showing
     * an operator-typed station name must contain that name in **no** frame. Redaction happens at
     * capture ([org.ort.app.fieldreport.recorder.ScreenFrameCapturer]'s own
     * `mayRenderUserSuppliedContent`), not at bundle time: this drives the real
     * [FieldReportRecorder] through a [RecorderDestination.STATION_DETAIL] destination change with
     * a capturer that would (if ever called) hand back bytes standing in for a captured pixel of
     * the name, then asserts the bundle's own SCREEN_FRAMES entries never carry them — proving the
     * redaction holds end to end, from the recorder down through the real bundle builder, not only
     * at the unit that decides whether to call the capturer.
     */
    @Test
    fun `D55 a bundle built from STATION_DETAIL never carries a frame from that destination`() = runTest {
        val stationNameMarker = "OPERATOR-TYPED-STATION-NAME".toByteArray()
        val capturer = FakeScreenFrameCapturer(FakeScreenFrameCapturer.Mode.Success(stationNameMarker))
        FieldReportRecorder.configure(context.filesDir, TestClock(), frameCapturer = capturer)

        FieldReportRecorder.onDestinationChanged(RecorderDestination.STATION_DETAIL)
        FieldReportRecorder.flush()
        FieldReportRecorder.awaitFrameCapture()

        val (_, entries) = writeBundle(categories = setOf(FieldReportGatedCategory.SCREEN_FRAMES))

        assertTrue(
            "a STATION_DETAIL destination must never produce a stored frame at all",
            entries.keys.none { it.startsWith("frames/") },
        )
        assertTrue(
            "the operator-typed name marker must appear in no bundle entry",
            entries.values.none { it.toString(Charsets.UTF_8).contains("OPERATOR-TYPED-STATION-NAME") },
        )
        assertEquals(
            "the capturer must never even have been asked for a frame on this destination",
            0,
            capturer.callCount,
        )
    }

    /** Control for the test above: a non-sensitive destination's frame reaches the bundle exactly
     * as `AC_148` already proves for a frame placed on disk directly — this drives it through the
     * real recorder instead, so the control and the redacted case above exercise the identical
     * path. */
    @Test
    fun `control D55 a NOW destination's captured frame does reach the bundle`() = runTest {
        val capturer = FakeScreenFrameCapturer(FakeScreenFrameCapturer.Mode.Success(byteArrayOf(1, 2, 3)))
        FieldReportRecorder.configure(context.filesDir, TestClock(), frameCapturer = capturer)

        FieldReportRecorder.onDestinationChanged(RecorderDestination.NOW)
        FieldReportRecorder.flush()
        FieldReportRecorder.awaitFrameCapture()

        val (_, entries) = writeBundle(categories = setOf(FieldReportGatedCategory.SCREEN_FRAMES))

        assertTrue(
            "a non-sensitive destination's frame must reach the bundle",
            entries.keys.any { it.startsWith("frames/") },
        )
        assertEquals(1, capturer.callCount)
    }
}
