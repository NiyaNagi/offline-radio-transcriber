package org.ort.app

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.ReaderActivity
import org.ort.app.ui.theme.OrtTheme
import org.ort.pipeline.capture.CaptureState
import org.ort.testing.Requirement
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * Two groups of tests, kept in one file because they exercise the same activity's lifecycle from
 * two different angles:
 *
 * - **F-022 / FR-UI-7** (main, pre-existing): a foreground service is a singleton per process
 *   (`RealCaptureService.onStartCommand` already ignores a second start command), but before that
 *   fix nothing on the `:app` side knew that — relaunching [MainActivity] while capture was
 *   already running always minted a fresh [org.ort.core.Ulid] session id and handed it straight to
 *   [ReaderActivity], so the reader polled a session nothing was capturing into and showed zero
 *   overs while capture was genuinely live (constitution IV, "never lies"; FR-UI-7's capture
 *   status surface must reflect the real running state). [CaptureState.sessionId] (already
 *   published by `RealCaptureService.startCapture()`) is the one place in-process that knows which
 *   session, if any, is genuinely capturing right now.
 * - **R-001, R-002, R-085** (ui-conformance-plan WP1): two layers, kept separate so a failure is
 *   legible —
 *   - Rendering: the three private-turned-`internal` setup composables actually show the copy
 *     R-002 and R-085 describe, called the same way [MainActivity.onCreate]'s own `setContent`
 *     calls them (`createComposeRule`, the same pattern `ReaderAccessibilityTest` already
 *     establishes for `OrtNavHost`).
 *   - Integration: [MainActivity] itself, driven through Robolectric's real `Activity` lifecycle
 *     with shadowed permission state, resolves to the right [SetupScreen]
 *     ([currentScreenForTest]) and, once both permissions are resolved, starts capture, launches
 *     [ReaderActivity] and finishes — the behaviour `PermissionsFlowTest`'s tested contract exists
 *     to protect (AGENTS.md: "do not regress").
 *
 * [SetupScreenSelectionTest] covers the pure decision table (every branch of [setupScreenFor]) with
 * no `Context` at all; this file exists for the parts that genuinely need one.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /**
     * Every integration test below builds a real `Activity` whose `onCreate` composes real Compose
     * content into it. Robolectric never tears that down on its own, and an undestroyed Activity's
     * composition otherwise leaks a live `Recomposer`/Choreographer registration into whichever test
     * class runs next in the same JVM — this is what caused `ActivityPatternChartTest`'s
     * `AppNotIdleException` ("Compose did not get idle... in 60 SECONDS") when the full suite ran,
     * despite `ActivityPatternChartTest` passing on its own: the leak, not that test, was the bug.
     * [destroyAfterTest] is called at the end of every integration test's body and again in
     * [tearDown] as a backstop.
     */
    private var controllerUnderTest: ActivityController<MainActivity>? = null

    private fun destroyAfterTest() {
        // The full lifecycle, not destroy() straight from RESUMED: see ReaderActivityTest's
        // identical comment — this is what actually cancels a lifecycle-aware Recomposer/its
        // coroutines under Robolectric, which a bare destroy() did not.
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

    // --- F-022 / FR-UI-7: same-process session-routing (main) -----------------------------------

    @Test
    @Requirement("FR-UI-7")
    fun `FR_UI_7 relaunching MainActivity during an active session starts no second capture session`() {
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
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

        buildAndResume()

        val app = ApplicationProvider.getApplicationContext<Application>()
        assertEquals(
            "with nothing already capturing, MainActivity must still start the service as before",
            REAL_CAPTURE_SERVICE_CLASS_NAME,
            shadowOf(app).nextStartedService?.component?.className,
        )
    }

    // --- Rendering (ui-conformance-plan WP1) -----------------------------------------------------

    @Test
    fun `R_002 MainActivity shows Allow microphone when not granted`() {
        composeTestRule.setContent {
            OrtTheme { MicrophoneSetupScreen(onAllow = {}) }
        }
        composeTestRule.onNodeWithText("Microphone").assertIsDisplayed()
        composeTestRule.onNodeWithText("Allow microphone").assertIsDisplayed()
    }

    @Test
    fun `R_085 MainActivity shows Open app settings when denied permanently`() {
        composeTestRule.setContent {
            OrtTheme { MicrophoneDeniedScreen(onOpenSettings = {}, onCheckAgain = {}) }
        }
        composeTestRule.onNodeWithText("Microphone refused").assertIsDisplayed()
        composeTestRule.onNodeWithText("Android will not ask again").assertIsDisplayed()
        composeTestRule.onNodeWithText("Open app settings").assertIsDisplayed()
        composeTestRule.onNodeWithText("Check again").assertIsDisplayed()
    }

    @Test
    fun `MainActivity shows Allow notifications and Skip when only notifications remain`() {
        composeTestRule.setContent {
            OrtTheme { NotificationsSetupScreen(onAllow = {}, onSkip = {}) }
        }
        composeTestRule.onNodeWithText("Allow notifications").assertIsDisplayed()
        composeTestRule.onNodeWithText("Skip").assertIsDisplayed()
    }

    // --- Integration (ui-conformance-plan WP1) ---------------------------------------------------

    /**
     * `ShadowInstrumentation.grantPermissions`/`denyPermissions` are package-private in
     * Robolectric 4.14 (confirmed against the actual jar — not callable from `org.ort.app`), so
     * this goes through [ReflectionHelpers], the same indirection Robolectric's own docs point at
     * for shadow-only methods a test package cannot otherwise reach.
     */
    private fun deny(vararg permissions: String) {
        ReflectionHelpers.callInstanceMethod<Unit>(
            shadowOf(InstrumentationRegistry.getInstrumentation()),
            "denyPermissions",
            ReflectionHelpers.ClassParameter(Array<String>::class.java, arrayOf(*permissions)),
        )
    }

    private fun grant(vararg permissions: String) {
        ReflectionHelpers.callInstanceMethod<Unit>(
            shadowOf(InstrumentationRegistry.getInstrumentation()),
            "grantPermissions",
            ReflectionHelpers.ClassParameter(Array<String>::class.java, arrayOf(*permissions)),
        )
    }

    private fun setShouldShowRationale(permission: String, value: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context.packageManager).setShouldShowRequestPermissionRationale(permission, value)
    }

    @Test
    fun `R_002 a fresh install reaches the request-microphone screen, never asking the OS automatically`() {
        deny(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        setShouldShowRationale(Manifest.permission.RECORD_AUDIO, false) // never asked yet

        val activity = buildAndResume()

        assertEquals(SetupScreen.REQUEST_MICROPHONE, activity.currentScreenForTest)
        // No permission dialog is shown as a side effect of merely landing on the screen (R-002).
        assertEquals(null, shadowOf(activity).lastRequestedPermission)
    }

    @Test
    fun `R_085 a permission requested once and then refused twice reaches the microphone-denied screen`() {
        deny(Manifest.permission.RECORD_AUDIO)
        setShouldShowRationale(Manifest.permission.RECORD_AUDIO, false)
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(MainActivity.PREFS_NAME, Activity.MODE_PRIVATE)
            .edit().putBoolean(MainActivity.KEY_MIC_REQUESTED, true).apply()

        val activity = buildAndResume()

        assertEquals(SetupScreen.MICROPHONE_DENIED, activity.currentScreenForTest)
    }

    @Test
    fun `microphone granted but notifications not reaches the request-notifications screen`() {
        grant(Manifest.permission.RECORD_AUDIO)
        deny(Manifest.permission.POST_NOTIFICATIONS)

        val activity = buildAndResume()

        assertEquals(SetupScreen.REQUEST_NOTIFICATIONS, activity.currentScreenForTest)
    }

    @Test
    fun `MainActivity_launches_ReaderActivity_and_finishes_when_permitted`() {
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

        val activity = buildAndResume()

        assertEquals(null, activity.currentScreenForTest)
        assertTrue(activity.isFinishing)
        val nextActivity = shadowOf(activity).nextStartedActivity
        assertEquals(ReaderActivity::class.java.name, nextActivity.component?.className)
    }

    @Test
    fun `skipping notifications proceeds to capture without the OS ever granting the permission`() {
        grant(Manifest.permission.RECORD_AUDIO)
        deny(Manifest.permission.POST_NOTIFICATIONS)
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(MainActivity.PREFS_NAME, Activity.MODE_PRIVATE)
            .edit().putBoolean(MainActivity.KEY_NOTIFICATIONS_SKIPPED, true).apply()

        val activity = buildAndResume()

        assertEquals(null, activity.currentScreenForTest)
        assertTrue(activity.isFinishing)
    }

    // --- R-008: dark system bars regardless of night mode ----------------------------------------

    /**
     * `enableEdgeToEdge()`'s runtime effect is what `WindowInsetsControllerCompat
     * .isAppearanceLightStatusBars`/`isAppearanceLightNavigationBars` report — `false` means "dark
     * background, light icons", which is what this app must always show (guide §12: no light
     * theme). The no-arg overload (`SystemBarStyle.auto`) would report `true` here whenever the
     * device is *not* in night mode; `OrtSystemBarStyle` (`SystemBarStyle.dark(...)`) must report
     * `false` unconditionally — proven twice, once under Robolectric's default (day) qualifiers and
     * once forced into night mode, so a regression back to `auto` would fail at least the second
     * case even though the first alone cannot tell `auto` and `dark` apart while genuinely in day.
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
