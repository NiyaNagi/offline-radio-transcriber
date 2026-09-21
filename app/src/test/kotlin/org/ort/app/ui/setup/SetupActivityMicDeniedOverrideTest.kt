package org.ort.app.ui.setup

import android.Manifest
import android.app.Application
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.capture.CaptureMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers

/**
 * R-1127/R-1107/R-085 (register): split out of `SetupActivityTest` the same way
 * `SetupActivityVerifyOverrideTest` was (detekt's `LargeClass` ceiling) — proves
 * [DebugMicPermissionOverride] closes both halves of S02b's own capture gap. `RECORD_AUDIO` is
 * granted in every case here, matching `tools/ui-audit/install.ps1`'s own unconditional grant —
 * the whole point of this seam is that the override must still make the honest gate fire with the
 * OS permission genuinely present, never by relying on it being absent.
 */
@RunWith(RobolectricTestRunner::class)
class SetupActivityMicDeniedOverrideTest {

    @After
    fun tearDown() {
        ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)
            .edit().clear().commit()
        DebugMicPermissionOverride.clear()
        DebugMicPermissionOverride.isDebugBuild = { org.ort.app.BuildConfig.DEBUG }
    }

    private fun grant(vararg permissions: String) {
        ReflectionHelpers.callInstanceMethod<Unit>(
            shadowOf(InstrumentationRegistry.getInstrumentation()),
            "grantPermissions",
            ReflectionHelpers.ClassParameter(Array<String>::class.java, arrayOf(*permissions)),
        )
    }

    /** Enough to satisfy every gate [SetupStateMachine.stepFor] checks before the `RECORD_AUDIO`
     * gate itself — welcome, the jurisdiction notice, and a capture mode. */
    private fun storeWelcomeJurisdictionAndMode() {
        ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)
            .edit()
            .putBoolean(SharedPreferencesSetupStore.KEY_WELCOME_SEEN, true)
            .putBoolean(SharedPreferencesSetupStore.KEY_JURISDICTION_NOTICE_SEEN, true)
            .putString(SharedPreferencesSetupStore.KEY_CAPTURE_MODE, CaptureMode.LOCAL_MICROPHONE.name)
            .apply()
    }

    @Test
    fun `R_1127 a cold EXTRA_STEP MICROPHONE_DENIED launch survives onResume, override active, RECORD_AUDIO granted`() {
        storeWelcomeJurisdictionAndMode()
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        DebugMicPermissionOverride.isDebugBuild = { true }
        DebugMicPermissionOverride.show()

        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = Intent(context, SetupActivity::class.java)
            .putExtra(SetupActivity.EXTRA_STEP, SetupStep.MICROPHONE_DENIED.name)
        ActivityScenario.launch<SetupActivity>(intent).use { scenario ->
            // ActivityScenario.launch already drives onCreate -> onStart -> onResume, so this
            // proves the step survives the exact onResume re-check the register named, not only
            // the initial onCreate render.
            scenario.onActivity { activity ->
                assertEquals(SetupStep.MICROPHONE_DENIED, activity.currentStepForTest)
            }
        }
    }

    @Test
    fun `R_1127 discrimination - without the override, RECORD_AUDIO granted stomps the forced step on resume`() {
        storeWelcomeJurisdictionAndMode()
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        // No override shown — reproduces the pre-fix bug this class's own sibling test closes.

        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = Intent(context, SetupActivity::class.java)
            .putExtra(SetupActivity.EXTRA_STEP, SetupStep.MICROPHONE_DENIED.name)
        ActivityScenario.launch<SetupActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                // The honest re-derivation moves on once RECORD_AUDIO is genuinely granted — this
                // is the stomp the register described, not something this fix should suppress.
                assertNotEquals(SetupStep.MICROPHONE_DENIED, activity.currentStepForTest)
            }
        }
    }

    @Test
    fun `R_1127 the override is ignored the moment isDebugBuild reports false`() {
        storeWelcomeJurisdictionAndMode()
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        DebugMicPermissionOverride.isDebugBuild = { false }
        DebugMicPermissionOverride.show()

        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = Intent(context, SetupActivity::class.java)
            .putExtra(SetupActivity.EXTRA_STEP, SetupStep.MICROPHONE_DENIED.name)
        ActivityScenario.launch<SetupActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertNotEquals(SetupStep.MICROPHONE_DENIED, activity.currentStepForTest)
            }
        }
    }
}
