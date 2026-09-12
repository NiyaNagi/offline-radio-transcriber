package org.ort.app.fieldreport.wiring

import androidx.activity.ComponentActivity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.fieldreport.recorder.FieldReportRecorder
import org.ort.app.fieldreport.recorder.RecorderDestination
import org.ort.app.fieldreport.recorder.RecorderEvent
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * WPR2: nothing called [FieldReportRecorder.configure] before this round, so in a running app no
 * session-recorder file was ever written regardless of how safe the recorder itself already was —
 * this suite proves [FieldReportAppWiring.configureOnce] actually starts it, and that
 * [FieldReportAppWiring.attachWindow]/[FieldReportAppWiring.detachWindow] repoint the frame
 * capturer **without** ever discarding events recorded before a window existed (the whole reason
 * this object exists rather than a second [FieldReportRecorder.configure] call from an `Activity`
 * — see that object's own doc comment).
 */
@RunWith(RobolectricTestRunner::class)
class FieldReportAppWiringTest {

    @After
    fun shutdown() {
        FieldReportRecorder.shutdown()
        FieldReportAppWiring.detachWindow()
    }

    @Test
    fun `configureOnce actually starts the recorder — an event reaches the real log file on disk`() = runTest {
        val filesDir = File.createTempFile("field-report-wiring", "").apply {
            delete()
            mkdirs()
        }

        FieldReportAppWiring.configureOnce(filesDir)
        FieldReportRecorder.record(RecorderEvent.DestinationChanged(RecorderDestination.NOW))
        FieldReportRecorder.flush()

        val logFile = File(File(filesDir, "field-report"), FieldReportRecorder.LOG_FILE_NAME)
        assertTrue("expected ${logFile.path} to exist once configureOnce actually wired the recorder", logFile.isFile)
        assertTrue(logFile.readText().contains("destination_changed destination=NOW"))
    }

    @Test
    fun `attachWindow repoints the delegating capturer without touching the recorder's own buffer`() = runTest {
        val filesDir = File.createTempFile("field-report-wiring", "").apply {
            delete()
            mkdirs()
        }
        FieldReportAppWiring.configureOnce(filesDir)
        FieldReportRecorder.record(RecorderEvent.DestinationChanged(RecorderDestination.LOG))
        FieldReportRecorder.flush()
        val eventsBeforeAttach = FieldReportRecorder.events()
        assertTrue(eventsBeforeAttach.isNotEmpty())

        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        FieldReportAppWiring.attachWindow(activity.window)

        assertTrue(
            "attaching a window must never reset the ring buffer recorded before it existed",
            FieldReportRecorder.events() == eventsBeforeAttach,
        )
        assertTrue(FieldReportAppWiring.delegatingCapturer.delegate != null)
    }

    @Test
    fun `detachWindow clears the delegate so a destroyed activity's window is never captured again`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        FieldReportAppWiring.attachWindow(activity.window)
        assertTrue(FieldReportAppWiring.delegatingCapturer.delegate != null)

        FieldReportAppWiring.detachWindow()

        assertNull(FieldReportAppWiring.delegatingCapturer.delegate)
    }

    @Test
    fun `the delegating capturer reports no capture available before any window is attached`() = runTest {
        FieldReportAppWiring.detachWindow()
        assertNull(FieldReportAppWiring.delegatingCapturer.capture())
    }
}
