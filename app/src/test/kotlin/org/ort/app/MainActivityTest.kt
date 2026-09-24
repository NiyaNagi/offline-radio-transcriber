package org.ort.app

import android.Manifest
import android.app.Application
import android.content.Intent
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
import org.ort.app.ui.navigation.ReaderDestination
import org.ort.app.ui.setup.DebugOvernightSurvivalOverride
import org.ort.app.ui.setup.FakeOvernightSurvivalChecker
import org.ort.app.ui.setup.RadioChoice
import org.ort.app.ui.setup.SetupActivity
import org.ort.app.ui.setup.SharedPreferencesSetupStore
import org.ort.core.capture.CaptureMode
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

    // R-1188: `OvernightNagState.reset()` stood on both sides of every test here — that holder is
    // deleted with the overnight step, and F24 keys its dismissal to a persisted watermark instead.
    // The process-wide resets below are unaffected and still necessary for the same reason.

    @After
    fun tearDown() {
        destroyAfterTest()
        // CaptureState is a process-wide singleton; leaving a test's `capturing(...)` call live
        // would leak into the next test the same way an undestroyed Activity does.
        CaptureState.idle(clearSession = true)
        // R-1104: the same belt-and-suspenders reset `SetupActivityTest` already applies for this
        // package's own overnight-survival test seam — a process-wide singleton left live would
        // leak into whichever test runs next in this JVM.
        DebugOvernightSurvivalOverride.clear()
        DebugOvernightSurvivalOverride.isDebugBuild = { org.ort.app.BuildConfig.DEBUG }
    }

    private fun buildAndResume(): MainActivity {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controllerUnderTest = controller
        return controller.create().start().resume().get()
    }

    private fun buildAndResume(intent: Intent): MainActivity {
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent)
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

    /**
     * R-1104: also seeds [SharedPreferencesSetupStore.KEY_OVERNIGHT_SURVIVAL_PROVEN] `true` — the
     * same reasoning `SetupActivityTest.storeSetupAlreadyComplete()` already documents for the
     * identical shape: every test below that calls this one is exercising something else entirely
     * (the router split, F-022's same-process guard, R-008's system bar style), and without this,
     * [MainActivity.overnightSurvivalStillUnproven] would now route every one of them to
     * [SetupActivity] instead of [ReaderActivity] — a real `RealOvernightSurvivalChecker` reading a
     * fresh, empty Robolectric `:data` instance always finds no survival evidence. Tests that mean
     * to exercise the R-1104 gate itself call [markSetupCompleteWithUnprovenSurvival] instead.
     */
    private fun markSetupComplete() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)
            .edit()
            .putBoolean(SharedPreferencesSetupStore.KEY_SETUP_COMPLETE, true)
            .putBoolean(SharedPreferencesSetupStore.KEY_OVERNIGHT_SURVIVAL_PROVEN, true)
            .apply()
    }

    /** R-1104: setup complete, but deliberately without [SharedPreferencesSetupStore
     * .KEY_OVERNIGHT_SURVIVAL_PROVEN] — the state an operator who has never proven overnight
     * survival is actually in. */
    private fun markSetupCompleteWithUnprovenSurvival() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)
            .edit().putBoolean(SharedPreferencesSetupStore.KEY_SETUP_COMPLETE, true).apply()
    }

    /**
     * R-1161: the state the operator is actually in the instant `Start capture` is tapped on S12 —
     * every setup gate satisfied, [SharedPreferencesSetupStore.KEY_OVERNIGHT_SEEN] among them, and
     * overnight survival genuinely unproven because no session has ever been recorded.
     * [markSetupCompleteWithUnprovenSurvival] is not enough for the round trip below:
     * [SetupActivity] has to be able to resolve a step from this same store, and every gate but
     * Overnight must be clear for `Skip for now` to hand back at all. Mirrors
     * `SetupActivityTest.storeEverySetupGateExceptComplete()` — including the model fixture, which
     * the `play` flavor's READY gate genuinely needs (that helper's own doc comment).
     */
    private fun markPostSetupWithUnprovenSurvival() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)
            .edit()
            .putBoolean(SharedPreferencesSetupStore.KEY_WELCOME_SEEN, true)
            .putBoolean(SharedPreferencesSetupStore.KEY_JURISDICTION_NOTICE_SEEN, true)
            .putString(SharedPreferencesSetupStore.KEY_CAPTURE_MODE, CaptureMode.USB_RADIO.name)
            .putString(SharedPreferencesSetupStore.KEY_SELECTED_INPUT_ID, "usb-1")
            .putBoolean(SharedPreferencesSetupStore.KEY_INPUT_VERIFIED, true)
            .putBoolean(SharedPreferencesSetupStore.KEY_LEVEL_IN_BAND, true)
            .putBoolean(SharedPreferencesSetupStore.KEY_OVERNIGHT_SEEN, true)
            .putString(SharedPreferencesSetupStore.KEY_RADIO_CHOICE, RadioChoice.NONE.name)
            .putBoolean(SharedPreferencesSetupStore.KEY_ANALYTICS_CONSENT_SEEN, true)
            .putBoolean(SharedPreferencesSetupStore.KEY_SETUP_COMPLETE, true)
            .apply()
        org.ort.app.debug.ScenarioFixtures.installEveryModelFixtureAtRealSize(
            ApplicationProvider.getApplicationContext(),
        )
    }

    private fun overnightStepSeenInPrefs(): Boolean {
        val context = ApplicationProvider.getApplicationContext<Application>()
        return context.getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)
            .getBoolean(SharedPreferencesSetupStore.KEY_OVERNIGHT_SEEN, false)
    }

    private fun overnightSurvivalProvenInPrefs(): Boolean {
        val context = ApplicationProvider.getApplicationContext<Application>()
        return context.getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)
            .getBoolean(SharedPreferencesSetupStore.KEY_OVERNIGHT_SURVIVAL_PROVEN, false)
    }

    // --- R-080: the router split -----------------------------------------------------------------

    // --- E2-I03 (spec/e2e-capture-modes-plan.md, "owed by WPE") ------------------------------------

    @Test
    @Requirement("AC-87")
    fun `E2_I03 onResume marks ForegroundActivityTracker active`() {
        val before = org.ort.core.SystemClock.wallMillis()
        deny(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

        buildAndResume()

        assertTrue(
            "expected ForegroundActivityTracker.lastActiveAtMillis to advance to at least $before, " +
                "was ${org.ort.pipeline.digest.ForegroundActivityTracker.lastActiveAtMillis}",
            org.ort.pipeline.digest.ForegroundActivityTracker.lastActiveAtMillis >= before,
        )
    }

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

    // --- R-1104: the fast path re-checks overnight survival too, not only permissions -----------

    /**
     * **AC-199 at the router, and the exact inversion of what this test used to assert.** It used to
     * demand that unproven overnight survival *"send the operator back through Setup, never straight
     * into capture"*. That was R-1104 over-implementing AC-189, and it is what deadlocked every
     * first-run operator (R-1161): `hasProvenSurvival()`'s only admissible evidence is a recorded
     * session, only [MainActivity.startCaptureAndShowStatus] can create one, and that was the branch
     * the refusal never took.
     *
     * **Inverted rather than deleted**, which is this register's own discipline for a test whose
     * premise turned out to be the defect: the strictly stronger form asserts capture actually starts.
     * AC-189 as amended says so in terms — the prompt is post-capture and SHALL NOT gate capture.
     */
    @Test
    @Requirement("AC-199")
    fun `AC_199 setup complete with overnight survival never proven still starts capture`() {
        markSetupCompleteWithUnprovenSurvival()
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        DebugOvernightSurvivalOverride.show(FakeOvernightSurvivalChecker(proven = false))

        val activity = buildAndResume()

        assertEquals(
            "no setup step may gate capture on evidence only capture can produce (AC-199)",
            ReaderActivity::class.java.name,
            shadowOf(activity).nextStartedActivity?.component?.className,
        )
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertEquals(
            "capture must genuinely start -- it is the only thing that can ever produce the session " +
                "evidence hasProvenSurvival() asks for",
            REAL_CAPTURE_SERVICE_CLASS_NAME,
            shadowOf(app).nextStartedService?.component?.className,
        )
    }

    @Test
    @Requirement("R-1104")
    fun `R_1104 overnight survival already proven and persisted starts capture without consulting the checker again`() {
        markSetupComplete()
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        val checker = FakeOvernightSurvivalChecker(proven = true)
        DebugOvernightSurvivalOverride.show(checker)

        val activity = buildAndResume()

        val nextActivity = shadowOf(activity).nextStartedActivity
        assertEquals(ReaderActivity::class.java.name, nextActivity?.component?.className)
        assertEquals(
            "AC-189's own \"until\": once already latched true in the store, this must never query " +
                "the checker again on a later launch",
            0,
            checker.callCount,
        )
    }

    @Test
    @Requirement("R-1104")
    fun `R_1104 overnight survival newly provable this launch latches true and proceeds directly, no detour`() {
        markSetupCompleteWithUnprovenSurvival()
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        DebugOvernightSurvivalOverride.show(FakeOvernightSurvivalChecker(proven = true))

        val activity = buildAndResume()

        val nextActivity = shadowOf(activity).nextStartedActivity
        assertEquals(
            "survival provable right now must not force an unnecessary detour through Setup",
            ReaderActivity::class.java.name,
            nextActivity?.component?.className,
        )
        assertTrue(
            "the newly-proven fact must be persisted so it is never re-checked on a later launch",
            overnightSurvivalProvenInPrefs(),
        )
    }

    @Test
    @Requirement("R-1104")
    fun `R_1104 a permission revoked short-circuits before ever consulting the overnight checker`() {
        markSetupCompleteWithUnprovenSurvival()
        deny(Manifest.permission.RECORD_AUDIO)
        val checker = FakeOvernightSurvivalChecker(proven = false)
        DebugOvernightSurvivalOverride.show(checker)

        buildAndResume()

        assertEquals(
            "a revoked permission already routes to Setup on its own; the overnight checker (a " +
                ":data read) must never run when that alone already decided the route",
            0,
            checker.callCount,
        )
    }

    // --- R-1161: the detour is a nag, not a block -----------------------------------------------

    /**
     * **R-1161** (register; AC-189, constitution IV) — the operator's own report, driven end to end:
     * *"after finishing the setup, i get dropped back into the running overnight screen and it loops
     * when i click skip for now, i cant exit it."*
     *
     * This is deliberately the one test that crosses both activities, because crossing them is
     * exactly what no existing test did (R-1163): `MainActivityTest` and `SetupActivityTest` had
     * each met one half of the cycle and seeded past it in a fixture, so the composition was never
     * executed anywhere. Route → `SetupActivity` → `Skip for now` → hand back → route again. Before
     * the fix the second route repeated the first — [SetupActivity] again, no capture service —
     * forever, and `hasProvenSurvival()`'s only evidence is a recorded session that only
     * `startCaptureAndShowStatus` can create, so the sole exit condition required the very thing the
     * loop prevented.
     *
     * Both halves are asserted so a later change cannot quietly drop either: AC-189
     * (`spec/functional-spec.md`) requires the step to **reappear** on a relevant subsequent launch,
     * and says nothing at all about refusing capture until survival is proven — so the first route
     * must still detour, and the second must still reach capture.
     */
    /**
     * **AC-199, driven as the whole route the criterion names**: launch → capture, with *every*
     * capture-produced signal reporting unproven. This is the test AC-199 asks for in terms — "driving
     * the full launch-to-capture route with every such signal (overnight survival among them)
     * reporting unproven, and asserting that capture still starts — **not merely that some individual
     * step advances**".
     *
     * It is deliberately the one test that crosses both activities, because crossing them is exactly
     * what no existing test did (R-1163): `MainActivityTest` and `SetupActivityTest` had each met one
     * half of the cycle and seeded past it in a fixture, so the composition was never executed
     * anywhere. Before P39 the first route detoured to Setup, Setup's own two buttons handed back, and
     * the router detoured again — forever, with no way out of the app but uninstalling.
     *
     * The **second** route is what makes this a route test rather than a step test: it proves the
     * round trip terminates, which is the property a per-step assertion cannot see.
     */
    @Test
    @Requirement("AC-199")
    fun `AC_199 R-1161 a launch with every capture-produced signal unproven reaches capture, twice over`() {
        val checker = FakeOvernightSurvivalChecker(proven = false)
        DebugOvernightSurvivalOverride.show(checker)
        markPostSetupWithUnprovenSurvival()
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        val app = ApplicationProvider.getApplicationContext<Application>()

        val firstRoute = buildAndResume()
        assertEquals(
            "AC-199: nothing may divert this launch over evidence only a capture can produce",
            ReaderActivity::class.java.name,
            shadowOf(firstRoute).nextStartedActivity?.component?.className,
        )
        assertEquals(
            REAL_CAPTURE_SERVICE_CLASS_NAME,
            shadowOf(app).nextStartedService?.component?.className,
        )
        destroyAfterTest()

        val secondRoute = buildAndResume()
        assertEquals(
            "the trap: before P39 the second route repeated the first, with no way out of the app at all",
            ReaderActivity::class.java.name,
            shadowOf(secondRoute).nextStartedActivity?.component?.className,
        )
    }

    /**
     * **AC-199, and the structural half of it.** The router must not merely happen to reach capture —
     * it must be incapable of clearing the flag that made the old flow resume on the overnight step.
     * `overnightStepSeen` is the operator's own answer to a prompt; the router resetting it is how
     * AC-189's "reappears" got fused to a refusal, and the composition of the two was the deadlock.
     */
    @Test
    @Requirement("AC-199")
    fun `AC_199 the router never clears the operator's own answer to the overnight prompt`() {
        DebugOvernightSurvivalOverride.show(FakeOvernightSurvivalChecker(proven = false))
        // markPostSetupWithUnprovenSurvival() already records the overnight step as answered -- that
        // is the state an operator is in the instant `Start capture` is tapped.
        markPostSetupWithUnprovenSurvival()
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

        buildAndResume()

        assertTrue(
            "an answer the operator has already given must survive the router",
            overnightStepSeenInPrefs(),
        )
    }

    /**
     * ui-conformance-plan WP9 round 3: forward wiring, not a fix to an observed bug — see
     * `MainActivity.startCaptureAndShowStatus`'s own doc comment for why `RealCaptureService`'s
     * notification does not exercise this path today (it targets `ReaderActivity` directly).
     */
    @Test
    fun `R_080 MainActivity passes through a destination extra from its own launching intent`() {
        markSetupComplete()
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_DESTINATION, ReaderDestination.SETTINGS.name)

        buildAndResume(intent)

        val app = ApplicationProvider.getApplicationContext<Application>()
        val nextActivity = shadowOf(app).nextStartedActivity
        assertEquals(ReaderActivity::class.java.name, nextActivity?.component?.className)
        assertEquals(ReaderDestination.SETTINGS.name, nextActivity?.getStringExtra(ReaderActivity.EXTRA_DESTINATION))
    }

    @Test
    fun `R_080 MainActivity sends no destination extra when its own launching intent carried none`() {
        markSetupComplete()
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

        val activity = buildAndResume()

        val nextActivity = shadowOf(activity).nextStartedActivity
        assertNull(nextActivity?.getStringExtra(ReaderActivity.EXTRA_DESTINATION))
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
