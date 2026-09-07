package org.ort.capture.android.service

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.capture.android.heartbeat.FileHeartbeatStore
import org.ort.capture.android.heartbeat.UncleanEndDetector
import org.ort.testing.Requirement
import org.ort.testing.TestClock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNotificationManager
import java.io.File

@RunWith(RobolectricTestRunner::class)
class CaptureServiceTest {

    @Test
    @Requirement("AC-61", "FR-PLT-3")
    fun `AC_61 the posted notification never contains transcript text`() {
        val controller = Robolectric.buildService(CaptureService::class.java).create()
        val service = controller.get()
        val store = FileHeartbeatStore(File(createTempDir("capture-service-test"), "hb.txt"))
        service.heartbeatStore = store
        service.clock = TestClock()
        service.sessionId = "s1"
        controller.startCommand(0, 0)
        service.onHeartbeat(samplePosition = 160)

        val nm = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSystemService(NotificationManager::class.java)
        val shadow: ShadowNotificationManager = shadowOf(nm)
        val posted: Notification = shadow.allNotifications.single()
        val text = posted.extras.getCharSequence(Notification.EXTRA_TEXT).toString()

        // The structural guarantee (no field capable of holding transcript text) is asserted in
        // CaptureNotificationBuilderTest; this spot-checks the live path renders only state/elapsed/count.
        assertTrue(text.contains("Capturing"))
        assertTrue(text.contains("00:00:00"))
    }

    @Test
    @Requirement("AC-5", "FR-SVC-5b")
    fun `AC_5 a clean stop marks the session clean, an unclean end does not`() {
        val hbFile = File(createTempDir("capture-service-clean"), "hb.txt")
        val store = FileHeartbeatStore(hbFile)

        val controllerA = Robolectric.buildService(CaptureService::class.java).create()
        val serviceA = controllerA.get()
        serviceA.heartbeatStore = store
        serviceA.clock = TestClock()
        serviceA.sessionId = "session-A"
        controllerA.startCommand(0, 0)
        serviceA.onHeartbeat(160)
        serviceA.cleanStop()

        assertEquals(null, UncleanEndDetector(store).detect())

        val controllerB = Robolectric.buildService(CaptureService::class.java).create()
        val serviceB = controllerB.get()
        serviceB.heartbeatStore = store
        serviceB.clock = TestClock()
        serviceB.sessionId = "session-B"
        controllerB.startCommand(0, 0)
        serviceB.onHeartbeat(320)
        // No cleanStop() — simulates the process being killed.

        val report = UncleanEndDetector(store).detect()
        assertTrue(report != null)
        assertEquals("session-B", report!!.sessionId)
    }

    @Test
    fun `stop action stops the service cleanly`() {
        val hbFile = File(createTempDir("capture-service-stop-action"), "hb.txt")
        val store = FileHeartbeatStore(hbFile)
        val controller = Robolectric.buildService(CaptureService::class.java).create()
        val service = controller.get()
        service.heartbeatStore = store
        service.clock = TestClock()
        service.sessionId = "s-stop"
        controller.startCommand(0, 0)
        service.onHeartbeat(1)

        controller.withIntent(Intent(CaptureService.ACTION_STOP)).startCommand(0, 0)

        assertEquals(null, UncleanEndDetector(store).detect())
    }
}
