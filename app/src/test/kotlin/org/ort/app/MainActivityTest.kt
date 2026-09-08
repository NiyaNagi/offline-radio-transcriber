package org.ort.app

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.ReaderActivity
import org.ort.pipeline.capture.CaptureState
import org.ort.testing.Requirement
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * audit F-022: a foreground service is a singleton per process (`RealCaptureService.onStartCommand`
 * already ignores a second start command), but before this fix nothing on the `:app` side knew
 * that -- relaunching [MainActivity] while capture was already running always minted a fresh
 * [org.ort.core.Ulid] session id and handed it straight to [ReaderActivity], so the reader polled a
 * session nothing was capturing into and showed zero overs while capture was genuinely live
 * (constitution IV, "never lies"; FR-UI-7's capture status surface must reflect the real running
 * state). [CaptureState.sessionId] (already published by `RealCaptureService.startCapture()`) is
 * the one place in-process that knows which session, if any, is genuinely capturing right now.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityTest {

    @Before
    fun grantPermissions() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun resetCaptureState() {
        CaptureState.idle(clearSession = true)
    }

    @Test
    @Requirement("FR-UI-7")
    fun `FR_UI_7 relaunching MainActivity during an active session starts no second capture session`() {
        CaptureState.capturing("LIVE-SESSION-1")

        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val app = ApplicationProvider.getApplicationContext<Application>()
            assertNull(
                "a live session must not be joined by a second startService/startForegroundService call",
                shadowOf(app).nextStartedService,
            )
        } finally {
            controller.destroy()
        }
    }

    @Test
    @Requirement("FR-UI-7")
    fun `FR_UI_7 relaunching MainActivity during an active session hands the reader the live session id`() {
        CaptureState.capturing("LIVE-SESSION-2")

        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            val nextActivity = shadowOf(activity).nextStartedActivity
            assertEquals(
                "the reader must poll the session that is actually capturing, not a freshly minted one",
                "LIVE-SESSION-2",
                nextActivity?.getStringExtra(ReaderActivity.EXTRA_SESSION_ID),
            )
        } finally {
            controller.destroy()
        }
    }

    @Test
    @Requirement("FR-UI-7")
    fun `FR_UI_7 with no active session MainActivity starts capture normally`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val app = ApplicationProvider.getApplicationContext<Application>()
            assertEquals(
                "with nothing already capturing, MainActivity must still start the service as before",
                REAL_CAPTURE_SERVICE_CLASS_NAME,
                shadowOf(app).nextStartedService?.component?.className,
            )
        } finally {
            controller.destroy()
        }
    }

    private companion object {
        const val REAL_CAPTURE_SERVICE_CLASS_NAME = "org.ort.pipeline.capture.RealCaptureService"
    }
}
