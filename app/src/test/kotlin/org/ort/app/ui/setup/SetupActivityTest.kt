package org.ort.app.ui.setup

import android.Manifest
import android.app.Application
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.MainActivity
import org.ort.app.ui.ReaderActivity
import org.ort.app.ui.navigation.ReaderDestination
import org.ort.pipeline.capture.InputStatus
import org.ort.pipeline.capture.LevelStatus
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers

/**
 * R-080 (ui-conformance-plan WP9) — the Activity-level half of the guided sequence:
 * [SetupStateMachine]'s pure decisions ([SetupStateMachineTest]) driven through a real `Activity`
 * with real `SharedPreferences` and shadowed permission state, the same split
 * `MainActivityTest`/`SetupScreenSelectionTest` established pre-WP9.
 *
 * Uses [ActivityScenario] (`.use { }`, auto-closing) rather than a bare
 * `Robolectric.buildActivity(...)` — `ReaderActivityTest`'s own doc comment documents why: the raw
 * `ActivityController` path leaves a live `Recomposer` registration behind under this Robolectric
 * version no matter how the lifecycle is driven afterward, which then poisons the next Compose
 * test's idle-check in the same `:app` suite run (found by actually running the full suite, not by
 * inspection — the first version of this file used `Robolectric.buildActivity` and hung
 * `WelcomeScreenTest`/`VerifyScreenTest` with `AppNotIdleException` whenever they ran after it).
 * `ActivityScenario.close()` is the same disposal path `createAndroidComposeRule`'s
 * `ActivityScenarioRule` calls, so preconditions ([SharedPreferences]/permissions) can still be set
 * up before [ActivityScenario.launch] — the timing `createAndroidComposeRule` itself does not allow.
 */
@RunWith(RobolectricTestRunner::class)
class SetupActivityTest {

    @After
    fun tearDown() {
        clearPrefs()
        // Process-wide singletons (same pattern as CaptureState/RigStatus elsewhere in this
        // suite) -- reset so a stray value from this class never leaks into a later test.
        InputStatus.reset()
        LevelStatus.reset()
    }

    private fun clearPrefs() {
        ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)
            .edit().clear().commit()
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

    /** Every gate except [SharedPreferencesSetupStore.KEY_SETUP_COMPLETE] itself satisfied — the
     * natural resume point is [SetupStep.READY]. Shared by the round-3 tests below so each states
     * only what it adds. */
    private fun storeEverySetupGateExceptComplete() {
        ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)
            .edit()
            .putBoolean(SharedPreferencesSetupStore.KEY_WELCOME_SEEN, true)
            .putBoolean(SharedPreferencesSetupStore.KEY_INPUT_VERIFIED, true)
            .putString(SharedPreferencesSetupStore.KEY_SELECTED_INPUT_ID, "usb-1")
            .putBoolean(SharedPreferencesSetupStore.KEY_LEVEL_IN_BAND, true)
            .putBoolean(SharedPreferencesSetupStore.KEY_OVERNIGHT_SEEN, true)
            .putString(SharedPreferencesSetupStore.KEY_RADIO_CHOICE, RadioChoice.NONE.name)
            .apply()
    }

    @Test
    fun `R_080 a fresh install lands on Welcome before any permission is checked`() {
        deny(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

        ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> assertEquals(SetupStep.WELCOME, activity.currentStepForTest) }
        }
    }

    @Test
    fun `R_080 once Welcome is seen and the microphone is not granted, resumes on Microphone`() {
        ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)
            .edit().putBoolean(SharedPreferencesSetupStore.KEY_WELCOME_SEEN, true).apply()
        deny(Manifest.permission.RECORD_AUDIO)

        ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> assertEquals(SetupStep.MICROPHONE, activity.currentStepForTest) }
        }
    }

    @Test
    fun `R_080 with every gate already satisfied, SetupActivity hands back to MainActivity immediately`() {
        val prefs = ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(SharedPreferencesSetupStore.KEY_WELCOME_SEEN, true)
            .putBoolean(SharedPreferencesSetupStore.KEY_INPUT_VERIFIED, true)
            .putString(SharedPreferencesSetupStore.KEY_SELECTED_INPUT_ID, "usb-1")
            .putBoolean(SharedPreferencesSetupStore.KEY_LEVEL_IN_BAND, true)
            .putBoolean(SharedPreferencesSetupStore.KEY_OVERNIGHT_SEEN, true)
            .putString(SharedPreferencesSetupStore.KEY_RADIO_CHOICE, RadioChoice.NONE.name)
            .putBoolean(SharedPreferencesSetupStore.KEY_SETUP_COMPLETE, true)
            .apply()
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

        // The activity finishes itself inside onCreate (refreshStep() -> handBackToMainActivity()),
        // so by the time any onActivity{} callback could run it is already DESTROYED and
        // ActivityScenario refuses to hand it back (NullPointerException, found by actually running
        // this, not by inspection) -- the application-level shadow still saw the startActivity call.
        ActivityScenario.launch(SetupActivity::class.java).use {
            val app = ApplicationProvider.getApplicationContext<Application>()
            val next = shadowOf(app).nextStartedActivity
            assertEquals(MainActivity::class.java.name, next?.component?.className)
        }
    }

    @Test
    fun `R_085 requesting the microphone once and then a permanent denial resumes on MicrophoneDenied`() {
        val prefs = ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(SharedPreferencesSetupStore.KEY_WELCOME_SEEN, true)
            .putBoolean(SharedPreferencesSetupStore.KEY_MIC_REQUESTED, true)
            .apply()
        deny(Manifest.permission.RECORD_AUDIO)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        shadowOf(context.packageManager)
            .setShouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO, false)

        ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> assertEquals(SetupStep.MICROPHONE_DENIED, activity.currentStepForTest) }
        }
    }

    // --- WP9 round 3: EXTRA_STEP entry point, S12 Install, MainActivity destination pass-through --

    /**
     * WP3's `ReaderNavigator.openSetupInput()` needs this for F1's "Choose another input" and
     * `Settings-Capture`'s "Re-verify" — both fired *during a running session*, when every gate is
     * already satisfied and the ordinary resume flow would hand straight back to `MainActivity`.
     */
    @Test
    fun R_080_setup_opens_at_the_requested_step() {
        storeEverySetupGateExceptComplete()
        ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)
            .edit().putBoolean(SharedPreferencesSetupStore.KEY_SETUP_COMPLETE, true).apply()
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = Intent(context, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_STEP, SetupStep.INPUT.name)
        ActivityScenario.launch<SetupActivity>(intent).use { scenario ->
            scenario.onActivity { activity -> assertEquals(SetupStep.INPUT, activity.currentStepForTest) }
        }
    }

    @Test
    fun `R_080 a requested step ahead of an unmet gate is ignored, never skipping a verification`() {
        deny(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = Intent(context, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_STEP, SetupStep.RADIO.name)
        ActivityScenario.launch<SetupActivity>(intent).use { scenario ->
            scenario.onActivity { activity -> assertEquals(SetupStep.WELCOME, activity.currentStepForTest) }
        }
    }

    @Test
    fun `R_080 an unrecognised requested step is ignored, falling back to the ordinary resume`() {
        deny(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = Intent(context, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_STEP, "NOT_A_REAL_STEP")
        ActivityScenario.launch<SetupActivity>(intent).use { scenario ->
            scenario.onActivity { activity -> assertEquals(SetupStep.WELCOME, activity.currentStepForTest) }
        }
    }

    /** S12's `Install` (`Setup-Done.dc.html`) opens `ReaderActivity` at its `SETTINGS` destination
     * rather than blocking on `Start capture` first. */
    @Test
    fun R_080_ready_install_opens_the_reader_at_settings() {
        storeEverySetupGateExceptComplete() // KEY_SETUP_COMPLETE left unset -- natural resume is READY.
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

        ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(SetupStep.READY, activity.currentStepForTest)
                activity.onInstallModel()
            }
            val app = ApplicationProvider.getApplicationContext<Application>()
            val next = shadowOf(app).nextStartedActivity
            assertEquals(ReaderActivity::class.java.name, next?.component?.className)
            assertEquals(ReaderDestination.SETTINGS.name, next?.getStringExtra(ReaderActivity.EXTRA_DESTINATION))
        }
    }
}
