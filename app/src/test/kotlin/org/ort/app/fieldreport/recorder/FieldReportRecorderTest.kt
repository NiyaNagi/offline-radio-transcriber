package org.ort.app.fieldreport.recorder

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.testing.Requirement
import org.ort.testing.TestClock
import java.io.File
import java.nio.file.Files

/**
 * FR-OBS-6: the session recorder — bounded ring buffer, off the audio frame path (proven
 * structurally by [FieldReportRecorder.record]'s own non-suspending signature, not by a test here
 * — a test cannot observe "never blocks" directly). D55 supersedes FR-OBS-6's original "absent
 * from release builds" text: the recorder now runs in every build (`FieldReportRecorderTest`
 * carries no build-type gate any more — see `FieldReportAppWiring`'s own doc comment for why).
 */
class FieldReportRecorderTest {

    @AfterEach
    fun tearDown() {
        FieldReportRecorder.shutdown()
    }

    private fun tempFilesDir(): File = Files.createTempDirectory("field-report-recorder-test").toFile()

    @Test
    @Requirement("FR-OBS-6")
    fun `FR_OBS_6_recording fewer events than the bound keeps every one of them, oldest first`() {
        FieldReportRecorder.configure(tempFilesDir(), TestClock(), maxEvents = 5)

        FieldReportRecorder.record(RecorderEvent.DestinationChanged(RecorderDestination.NOW))
        FieldReportRecorder.record(RecorderEvent.DestinationChanged(RecorderDestination.LOG))
        runBlocking { FieldReportRecorder.flush() }

        assertEquals(
            listOf(
                RecorderEvent.DestinationChanged(RecorderDestination.NOW),
                RecorderEvent.DestinationChanged(RecorderDestination.LOG),
            ),
            FieldReportRecorder.events(),
        )
    }

    /**
     * AC-142's own discipline applied to the event ring buffer FR-OBS-6 separately requires
     * ("bounded ring buffer"): tested by *exceeding* the bound, not by asserting the constant. A
     * capacity of 3 fed 5 events must hold exactly the last 3, in order — never all 5, never the
     * first 3.
     */
    @Test
    @Requirement("FR-OBS-6")
    fun `FR_OBS_6_recording past the bound drops the oldest events, keeps the newest, never grows past it`() {
        FieldReportRecorder.configure(tempFilesDir(), TestClock(), maxEvents = 3)

        val destinations = listOf(
            RecorderDestination.NOW,
            RecorderDestination.LOG,
            RecorderDestination.SEARCH,
            RecorderDestination.THREADS,
            RecorderDestination.STATIONS,
        )
        destinations.forEach { FieldReportRecorder.record(RecorderEvent.DestinationChanged(it)) }
        runBlocking { FieldReportRecorder.flush() }

        val kept = FieldReportRecorder.events()
        assertEquals(3, kept.size, "ring buffer must never hold more than its configured bound")
        assertEquals(
            listOf(
                RecorderEvent.DestinationChanged(RecorderDestination.SEARCH),
                RecorderEvent.DestinationChanged(RecorderDestination.THREADS),
                RecorderEvent.DestinationChanged(RecorderDestination.STATIONS),
            ),
            kept,
            "must keep exactly the newest 3 (SEARCH, THREADS, STATIONS), oldest (NOW, LOG) dropped",
        )
    }

    @Test
    @Requirement("FR-OBS-6")
    fun `D55_configure always creates the field-report directory and records, with no build-type gate`() {
        val filesDir = tempFilesDir()

        FieldReportRecorder.configure(filesDir, TestClock())
        FieldReportRecorder.record(RecorderEvent.DestinationChanged(RecorderDestination.NOW))
        runBlocking { FieldReportRecorder.flush() }

        assertTrue(
            File(filesDir, "field-report").exists(),
            "D55: the recorder ships in every build now — the directory must exist",
        )
        assertEquals(listOf(RecorderEvent.DestinationChanged(RecorderDestination.NOW)), FieldReportRecorder.events())
    }

    @Test
    @Requirement("FR-OBS-6")
    fun `FR_OBS_6_recorded events are appended to the session recorder log file`() {
        val filesDir = tempFilesDir()
        FieldReportRecorder.configure(filesDir, TestClock(startWallMillis = 1_700_000_000_000L))

        FieldReportRecorder.record(RecorderEvent.DestinationChanged(RecorderDestination.CAPTURE))
        runBlocking { FieldReportRecorder.flush() }

        val logFile = File(File(filesDir, "field-report"), FieldReportRecorder.LOG_FILE_NAME)
        assertTrue(logFile.isFile)
        val line = logFile.readLines().single()
        assertTrue(line.contains("destination_changed"))
        assertTrue(line.contains("destination=CAPTURE"))
    }

    @Test
    @Requirement("FR-OBS-6")
    fun `FR_OBS_6_onDestinationChanged records the destination and requests a frame capture`() {
        val filesDir = tempFilesDir()
        val capturer = FakeScreenFrameCapturer(FakeScreenFrameCapturer.Mode.Success(byteArrayOf(9, 9, 9)))
        FieldReportRecorder.configure(filesDir, TestClock(), frameCapturer = capturer)

        FieldReportRecorder.onDestinationChanged(RecorderDestination.LOG)
        runBlocking {
            FieldReportRecorder.flush()
            FieldReportRecorder.awaitFrameCapture()
        }

        assertEquals(listOf(RecorderEvent.DestinationChanged(RecorderDestination.LOG)), FieldReportRecorder.events())
        assertEquals(1, capturer.callCount)
    }

    @Test
    @Requirement("FR-OBS-6")
    fun `FR_OBS_6_a failed frame capture still records the destination-changed event`() {
        val filesDir = tempFilesDir()
        val capturer = FakeScreenFrameCapturer(FakeScreenFrameCapturer.Mode.Failure)
        FieldReportRecorder.configure(filesDir, TestClock(), frameCapturer = capturer)

        FieldReportRecorder.onDestinationChanged(RecorderDestination.SETTINGS)
        runBlocking {
            FieldReportRecorder.flush()
            FieldReportRecorder.awaitFrameCapture()
        }

        assertEquals(
            listOf(RecorderEvent.DestinationChanged(RecorderDestination.SETTINGS)),
            FieldReportRecorder.events(),
        )
    }

    /**
     * D55/FR-OBS-7: redaction happens at capture, not at bundle time — see
     * `ScreenFrameCapturer.kt`'s own `mayRenderUserSuppliedContent` doc comment.
     * [RecorderDestination.STATION_DETAIL] is [org.ort.app.ui.screens.StationIdentityScreen], whose
     * "Given by you" fields can carry an operator-typed station name or note (FR-SPK-25,
     * FR-DIG-13). The capturer must never even be asked for a frame there — this is the
     * discriminating test for the fix in [FieldReportRecorder.onDestinationChanged]: before the
     * fix, [FakeScreenFrameCapturer.callCount] here would be 1, exactly like the LOG case above.
     */
    @Test
    @Requirement("FR-OBS-7")
    fun `D55_a station-detail destination is recorded but never requests a frame capture`() =
        assertDestinationNeverCaptures(RecorderDestination.STATION_DETAIL)

    /**
     * R-1128: the finding that proved the first version of this predicate under-inclusive.
     * `StationScreen.StationRow` draws `StationEntity.userName` as visible text and semantics on
     * every row of the `STATIONS` list — `mayRenderUserSuppliedContent`'s derivation table
     * (`ScreenFrameCapturer.kt`) now names it explicitly. Before that fix this test's
     * `capturer.callCount` would be 1, identical to [assertDestinationNeverCaptures]'s own
     * STATION_DETAIL case.
     */
    @Test
    @Requirement("FR-OBS-7")
    fun `R_1128_a stations-list destination is recorded but never requests a frame capture`() =
        assertDestinationNeverCaptures(RecorderDestination.STATIONS)

    /**
     * R-1128: `CorrectionSheet`'s station-search rows (`StationSearchRow.userName`) are composed
     * inside `TransmissionDetailContent`, not a separate nav route — see the derivation's own note
     * on why this destination is excluded on the derivation's terms, not on the timing of today's
     * one capture-per-arrival.
     */
    @Test
    @Requirement("FR-OBS-7")
    fun `R_1128_a transmission-detail destination is recorded but never requests a frame capture`() =
        assertDestinationNeverCaptures(RecorderDestination.TRANSMISSION_DETAIL)

    private fun assertDestinationNeverCaptures(destination: RecorderDestination) {
        val filesDir = tempFilesDir()
        val capturer = FakeScreenFrameCapturer(FakeScreenFrameCapturer.Mode.Success(byteArrayOf(4, 5, 6)))
        FieldReportRecorder.configure(filesDir, TestClock(), frameCapturer = capturer)

        FieldReportRecorder.onDestinationChanged(destination)
        runBlocking {
            FieldReportRecorder.flush()
            FieldReportRecorder.awaitFrameCapture()
        }

        assertEquals(
            listOf(RecorderEvent.DestinationChanged(destination)),
            FieldReportRecorder.events(),
            "the destination change itself is still safe to log — FR-OBS-6's vocabulary carries no name",
        )
        assertEquals(
            0,
            capturer.callCount,
            "a destination that can show an operator-typed name or station knowledge must never even " +
                "be asked for a frame",
        )
    }

    /**
     * R-1128: pins the full derivation, not just one destination — the fail-closed direction means
     * a destination this set does not name is unsafe by construction, so this test is the one place
     * that has to be updated (alongside the derivation table itself) when a screen genuinely earns
     * its way onto the allowlist.
     */
    @Test
    @Requirement("FR-OBS-7")
    fun `R_1128_mayRenderUserSuppliedContent blocks exactly STATIONS, STATION_DETAIL, TRANSMISSION_DETAIL`() {
        val blocked = RecorderDestination.entries.filter { it.mayRenderUserSuppliedContent() }.toSet()
        assertEquals(
            setOf(
                RecorderDestination.STATIONS,
                RecorderDestination.STATION_DETAIL,
                RecorderDestination.TRANSMISSION_DETAIL,
            ),
            blocked,
        )
        val cleared = RecorderDestination.entries.filterNot { it.mayRenderUserSuppliedContent() }.toSet()
        assertEquals(
            setOf(
                RecorderDestination.NOW,
                RecorderDestination.LOG,
                RecorderDestination.SEARCH,
                RecorderDestination.THREADS,
                RecorderDestination.THREAD_DETAIL,
                RecorderDestination.FREQUENCIES,
                RecorderDestination.FREQUENCY_DETAIL,
                RecorderDestination.EARLIER_NIGHTS,
                RecorderDestination.CAPTURE,
                RecorderDestination.IMPROVE_RECORDS,
                RecorderDestination.SETTINGS,
            ),
            cleared,
        )
    }
}
