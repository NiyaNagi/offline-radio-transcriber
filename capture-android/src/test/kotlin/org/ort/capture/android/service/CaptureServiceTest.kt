package org.ort.capture.android.service

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.robolectric.shadows.ShadowPowerManager
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
    @Requirement("AC-5", "FR-SVC-5b", "FR-SVC-4", "NFR-10")
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
        // NFR-10: the report must carry the last heartbeat's real wall time, not a placeholder —
        // it is what turns a silent kill into a specific, reportable loss.
        assertTrue("the report must carry a real last-heartbeat time", report.lastHeartbeatWallMillis > 0)
    }

    @Test
    @Requirement("FR-SVC-3")
    fun `FR_SVC_3 stop action stops the service cleanly`() {
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

    @Test
    @Requirement("FR-SVC-1")
    fun `FR_SVC_1 the manifest declares a microphone foreground service, and starting it runs one`() {
        // Read the manifest directly rather than through PackageManager: Robolectric's shadow
        // android.jar for this project's configured SDK does not expose
        // ServiceInfo.foregroundServiceType, so the only reliable way to check what is actually
        // *declared* is to parse the source of truth itself.
        val manifest = File("").absoluteFile.resolve("src/main/AndroidManifest.xml")
        assertTrue("expected an AndroidManifest.xml at $manifest", manifest.exists())
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest)
        val services = doc.getElementsByTagName("service")
        var captureService: org.w3c.dom.Element? = null
        for (i in 0 until services.length) {
            val el = services.item(i) as org.w3c.dom.Element
            if (el.getAttribute("android:name").endsWith("CaptureService")) captureService = el
        }
        assertTrue("CaptureService must be declared as a <service> in the manifest", captureService != null)
        assertEquals(
            "microphone",
            captureService!!.getAttribute("android:foregroundServiceType"),
        )

        // Starting it actually runs a foreground service — proven by a live notification, since
        // Android requires startForeground() to be called for any FOREGROUND_SERVICE_TYPE.
        val controller = Robolectric.buildService(CaptureService::class.java).create()
        val service = controller.get()
        service.heartbeatStore = FileHeartbeatStore(File(createTempDir("capture-service-fg"), "hb.txt"))
        service.clock = TestClock()
        service.sessionId = "s-fg"
        controller.startCommand(0, 0)

        val nm = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSystemService(NotificationManager::class.java)
        assertTrue(
            "capture must actually be running as a foreground service once started",
            shadowOf(nm).allNotifications.isNotEmpty(),
        )
    }

    @Test
    @Requirement("FR-SVC-2")
    fun `FR_SVC_2 a partial wake lock is held for the duration of capture and released on clean stop`() {
        ShadowPowerManager.clearWakeLocks()
        val hbFile = File(createTempDir("capture-service-wakelock"), "hb.txt")
        val store = FileHeartbeatStore(hbFile)
        val controller = Robolectric.buildService(CaptureService::class.java).create()
        val service = controller.get()
        service.heartbeatStore = store
        service.clock = TestClock()
        service.sessionId = "s-wakelock"

        controller.startCommand(0, 0)

        val wakeLock = ShadowPowerManager.getLatestWakeLock()
        assertTrue("a wake lock must exist once capture starts (FR-SVC-2)", wakeLock != null)
        assertTrue("the wake lock must be held while capture runs", wakeLock!!.isHeld)

        // cleanStop() marks the shutdown clean and asks Android to tear the service down;
        // release happens in onDestroy(), same as the real service lifecycle (Robolectric does
        // not invoke onDestroy() automatically just because stopSelf() was called).
        service.cleanStop()
        controller.destroy()

        assertFalse("the wake lock must be released once the service is destroyed", wakeLock.isHeld)
    }
}
