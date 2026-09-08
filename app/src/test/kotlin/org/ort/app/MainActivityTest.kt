package org.ort.app

import android.Manifest
import android.app.Application
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.ReaderActivity
import org.ort.app.ui.setup.SetupActivity
import org.ort.app.ui.setup.SharedPreferencesSetupStore
import org.ort.pipeline.capture.CaptureState
import org.ort.testing.Requirement
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * R-080 (ui-conformance-plan WP9): `MainActivity` is now a pure router — setup incomplete →
 * [SetupActivity]; complete and permitted → start capture + [ReaderActivity], unchanged from
 * before WP9 (F-022 / FR-UI-7, kept green below with the exact same assertions this file already
 * had). Every rendering test the pre-WP9 flow had for the three interim permission screens has
 * moved to `ui/setup` (each screen's own `*ScreenTest.kt`), next to the composables — there is no Compose
 * content left in `MainActivity` to test directly.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityTest {

    /**
     * Robolectric never tears an undestroyed `Activity` down on its own — see
     * `ReaderActivityTest`'s identical comment. [destroyAfterTest] is called at the end of every
     * test's body and again in [tearDown] as a backstop.
     */
    private var controllerUnderTest: ActivityController<MainActivity>? = null

    private fun destroyAfterTest() {
        try {
            controllerUnderTest?.pause()?.stop()?.destroy()
        } catch (e: IllegalStateException) {
            controllerUnderTest?.destroy()
        }
        controllerUnderTest = null
    }

    @After
    fun tearDown() {
        destroyAfterTest()
        // CaptureState is a process-wide singleton; leaving a test's `capturing(...)` call live
        // would leak into the next test the same way an undestroyed Activity does.
        CaptureState.idle(clearSession = true)
    }

    private fun buildAndResume(): MainActivity {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controllerUnderTest = controller
        return controller.create().start().resume().get()
    }

    private fun grant(vararg permissions: String) {
        ReflectionHelpers.callInstanceMethod<Unit>(
            shadowOf(InstrumentationRegistry.getInstrumentation()),
            "grantPermissions",
            ReflectionHelpers.ClassParameter(Array<String>::class.java, arrayOf(*permissions)),
        )
    }

    private fun deny(vararg permissions: String) {
        ReflectionHelpers.callInstanceMethod<Unit>(
            shadowOf(InstrumentationRegistry.getInstrumentation()),
            "denyPermissions",
            ReflectionHelpers.ClassParameter(Array<String>::class.java, arrayOf(*permissions)),
        )
    }

    private fun markSetupComplete() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)
            .edit().putBoolean(SharedPreferencesSetupStore.KEY_SETUP_COMPLETE, true).apply()
    }

    // --- R-080: the router split -----------------------------------------------------------------

    @Test
    fun `R_080 a fresh install with setup not complete is routed to SetupActivity, never shown a screen here`() {
        deny(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

        val activity = buildAndResume()

        assertTrue(activity.isFinishing)
        val nextActivity = shadowOf(activity).nextStartedActivity
        assertEquals(SetupActivity::class.java.name, nextActivity?.component?.className)
    }

    @Test
    fun `R_080 permissions granted but setup not marked complete still routes to SetupActivity`() {
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

        val activity = buildAndResume()

        val nextActivity = shadowOf(activity).nextStartedActivity
        assertEquals(SetupActivity::class.java.name, nextActivity?.component?.className)
    }

    @Test
    fun `R_080 setup complete but a permission since revoked routes back to SetupActivity, never starts capture`() {
        markSetupComplete()
        deny(Manifest.permission.RECORD_AUDIO)

        val activity = buildAndResume()

        val nextActivity = shadowOf(activity).nextStartedActivity
        assertEquals(SetupActivity::class.java.name, nextActivity?.component?.className)
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertNull("a revoked permission must not start capture", shadowOf(app).nextStartedService)
    }

    @Test
    fun `R_080 setup complete and permitted starts capture and launches the reader directly`() {
        markSetupComplete()
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

        val activity = buildAndResume()

        assertTrue(activity.isFinishing)
        val nextActivity = shadowOf(activity).nextStartedActivity
        assertEquals(ReaderActivity::class.java.name, nextActivity?.component?.className)
    }

    // --- F-022 / FR-UI-7: same-process session-routing (unchanged from before WP9) --------------

    @Test
    @Requirement("FR-UI-7")
    fun `FR_UI_7 relaunching MainActivity during an active session starts no second capture session`() {
        markSetupComplete()
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        CaptureState.capturing("LIVE-SESSION-1")

        buildAndResume()

        val app = ApplicationProvider.getApplicationContext<Application>()
        assertNull(
            "a live session must not be joined by a second startService/startForegroundService call",
            shadowOf(app).nextStartedService,
        )
    }

    @Test
    @Requirement("FR-UI-7")
    fun `FR_UI_7 relaunching MainActivity during an active session hands the reader the live session id`() {
        markSetupComplete()
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        CaptureState.capturing("LIVE-SESSION-2")

        val activity = buildAndResume()

        val nextActivity = shadowOf(activity).nextStartedActivity
        assertEquals(
            "the reader must poll the session that is actually capturing, not a freshly minted one",
            "LIVE-SESSION-2",
            nextActivity?.getStringExtra(ReaderActivity.EXTRA_SESSION_ID),
        )
    }

    @Test
    @Requirement("FR-UI-7")
    fun `FR_UI_7 with no active session MainActivity starts capture normally`() {
        markSetupComplete()
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

        buildAndResume()

        val app = ApplicationProvider.getApplicationContext<Application>()
        assertEquals(
            "with nothing already capturing, MainActivity must still start the service as before",
            REAL_CAPTURE_SERVICE_CLASS_NAME,
            shadowOf(app).nextStartedService?.component?.className,
        )
    }

    // --- R-008: dark system bars regardless of night mode ------------------------------------

    /**
     * `enableEdgeToEdge()`'s runtime effect is what `WindowInsetsControllerCompat
     * .isAppearanceLightStatusBars`/`isAppearanceLightNavigationBars` report — `false` means "dark
     * background, light icons", which is what this app must always show (guide §12: no light
     * theme). The no-arg overload (`SystemBarStyle.auto`) would report `true` here whenever the
     * device is *not* in night mode; `OrtSystemBarStyle` (`SystemBarStyle.dark(...)`) must report
     * `false` unconditionally — proven twice, once under Robolectric's default (day) qualifiers and
     * once forced into night mode.
     */
    @Test
    fun `R_008_system_bar_style_is_dark_regardless_of_night_mode in day mode`() {
        deny(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

        val activity = buildAndResume()

        val insetsController = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        assertFalse("status bar must render light icons on a dark ground", insetsController.isAppearanceLightStatusBars)
        assertFalse(
            "navigation bar must render light icons on a dark ground",
            insetsController.isAppearanceLightNavigationBars,
        )
    }

    @Test
    @Config(qualifiers = "night")
    fun `R_008_system_bar_style_is_dark_regardless_of_night_mode in night mode`() {
        deny(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

        val activity = buildAndResume()

        val insetsController = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        assertFalse(
            "SystemBarStyle.auto would flip this true in night mode; OrtSystemBarStyle must not",
            insetsController.isAppearanceLightStatusBars,
        )
        assertFalse(
            "SystemBarStyle.auto would flip this true in night mode; OrtSystemBarStyle must not",
            insetsController.isAppearanceLightNavigationBars,
        )
    }

    private companion object {
        const val REAL_CAPTURE_SERVICE_CLASS_NAME = "org.ort.pipeline.capture.RealCaptureService"
    }
}
