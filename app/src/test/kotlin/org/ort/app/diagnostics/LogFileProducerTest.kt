package org.ort.app.diagnostics

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * WP11e (register R-137, brief step 5): "Find the real writers … If no log file writer exists yet
 * for a board-listed log, produce an honest empty file with a header line 'no entries recorded by
 * this build' — never fabricate log content." Grepped before writing this (`lifecycle\.log|
 * capture\.log|pipeline\.log|rig\.log`, whole tree) — no writer exists for any of the four
 * board-listed logs, so every one of them takes the honest placeholder path today.
 * [DiagnosticsLogPaths.logFile] is the documented convention a future log writer should follow so
 * this producer picks its real output up automatically, proven here by writing to that exact path.
 */
@RunWith(RobolectricTestRunner::class)
class LogFileProducerTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `FR_OBS_1 with no writer, the log is an honest placeholder, never fabricated content`() = runTest {
        val bytes = LogFileProducer("lifecycle.log").produce(context)
        val text = String(bytes, Charsets.UTF_8)

        assertTrue(text.contains("no entries recorded by this build"))
    }

    @Test
    fun `FR_OBS_1 a real log file at the documented convention path is read and scrubbed`() = runTest {
        val file = DiagnosticsLogPaths.logFile(context, "capture.log")
        file.parentFile?.mkdirs()
        file.writeText("resolved K7ABC at 0.94\nroute verified: usb-serial attached")

        val bytes = LogFileProducer("capture.log").produce(context)
        val text = String(bytes, Charsets.UTF_8)

        assertEquals("resolved [callsign] at 0.94\nroute verified: usb-serial attached", text)
        assertFalse(text.contains("K7ABC"))
    }
}
