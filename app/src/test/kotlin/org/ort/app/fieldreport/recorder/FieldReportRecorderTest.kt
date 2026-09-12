package org.ort.app.fieldreport.recorder

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.app.BuildConfig
import org.ort.testing.Requirement
import org.ort.testing.TestClock
import java.io.File
import java.nio.file.Files

/**
 * FR-OBS-6: the debug-build session recorder — bounded ring buffer, off the audio frame path
 * (proven structurally by [FieldReportRecorder.record]'s own non-suspending signature, not by a
 * test here — a test cannot observe "never blocks" directly), absent from release builds.
 */
class FieldReportRecorderTest {

    @AfterEach
    fun tearDown() {
        FieldReportRecorder.shutdown()
        FieldReportRecorder.isDebugBuild = { BuildConfig.DEBUG }
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
    fun `FR_OBS_6_a release build never configures, never records, never accumulates anything`() {
        FieldReportRecorder.isDebugBuild = { false }
        val filesDir = tempFilesDir()

        FieldReportRecorder.configure(filesDir, TestClock())
        FieldReportRecorder.record(RecorderEvent.DestinationChanged(RecorderDestination.NOW))

        assertTrue(FieldReportRecorder.events().isEmpty())
        assertFalse(File(filesDir, "field-report").exists(), "a release build must not even create the directory")
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
}
