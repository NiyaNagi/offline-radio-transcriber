package org.ort.app.ui.setup

import android.Manifest
import android.app.Application
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.capture.android.AudioDeviceDescriptor
import org.ort.capture.android.AudioDeviceKind
import org.ort.core.capture.CaptureMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers

/**
 * R-1127/R-282 (register): split out of `SetupActivityTest` the same way
 * `SetupActivityVerifyOverrideTest` was — proves [RenderRouteMismatch] backfills [SetupActivity
 * .verifyStateForTest] from [DebugRouteCheckOverride.activeOverride] on a cold
 * [SetupActivity.EXTRA_STEP] launch straight at [SetupStep.ROUTE_MISMATCH], the identical gap
 * [SetupActivityVerifyOverrideTest] already proves closed for [SetupStep.VERIFY] via the same
 * override object.
 */
@RunWith(RobolectricTestRunner::class)
class SetupActivityRouteMismatchOverrideTest {

    @After
    fun tearDown() {
        ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)
            .edit().clear().commit()
        DebugRouteCheckOverride.clear()
        DebugRouteCheckOverride.isDebugBuild = { org.ort.app.BuildConfig.DEBUG }
    }

    private fun grant(vararg permissions: String) {
        ReflectionHelpers.callInstanceMethod<Unit>(
            shadowOf(InstrumentationRegistry.getInstrumentation()),
            "grantPermissions",
            ReflectionHelpers.ClassParameter(Array<String>::class.java, arrayOf(*permissions)),
        )
    }

    /** A verified input, but deliberately no further gate ([SharedPreferencesSetupStore
     * .KEY_LEVEL_IN_BAND] left unset) — `stepFor`'s natural resolution then lands on
     * [SetupStep.LEVEL], past [SetupStep.ROUTE_MISMATCH]'s own ordinal position, which is what lets
     * `SetupActivity.tryOpenAtRequestedStep` honour the cold `EXTRA_STEP` at all (`VERIFY`/
     * `ROUTE_MISMATCH` are themselves never returned by `stepFor`). */
    private fun storeVerifiedInputOnly() {
        ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)
            .edit()
            .putBoolean(SharedPreferencesSetupStore.KEY_WELCOME_SEEN, true)
            .putBoolean(SharedPreferencesSetupStore.KEY_JURISDICTION_NOTICE_SEEN, true)
            .putString(SharedPreferencesSetupStore.KEY_CAPTURE_MODE, CaptureMode.USB_RADIO.name)
            .putString(SharedPreferencesSetupStore.KEY_SELECTED_INPUT_ID, "usb-1")
            .putBoolean(SharedPreferencesSetupStore.KEY_INPUT_VERIFIED, true)
            .apply()
    }

    @Test
    fun `R_1127 a cold EXTRA_STEP ROUTE_MISMATCH launch renders the seeded DebugRouteCheckOverride state`() {
        storeVerifiedInputOnly()
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        val seeded = RouteCheckState.Mismatch(
            selected = AudioDeviceDescriptor(id = "usb-1", kind = AudioDeviceKind.USB_DEVICE, label = "USB Audio Device"),
            routed = AudioDeviceDescriptor(id = "builtin-mic", kind = AudioDeviceKind.BUILT_IN_MIC, label = "Built-in microphone"),
            reason = "test",
        )
        DebugRouteCheckOverride.isDebugBuild = { true }
        DebugRouteCheckOverride.show(seeded)

        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = Intent(context, SetupActivity::class.java)
            .putExtra(SetupActivity.EXTRA_STEP, SetupStep.ROUTE_MISMATCH.name)
        ActivityScenario.launch<SetupActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(SetupStep.ROUTE_MISMATCH, activity.currentStepForTest)
                assertEquals(seeded, activity.verifyStateForTest)
            }
        }
    }

    @Test
    fun `R_1127 DebugRouteCheckOverride is ignored on ROUTE_MISMATCH the moment isDebugBuild reports false`() {
        storeVerifiedInputOnly()
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        val seeded = RouteCheckState.Mismatch(
            selected = AudioDeviceDescriptor(id = "usb-1", kind = AudioDeviceKind.USB_DEVICE, label = "USB Audio Device"),
            routed = null,
            reason = "test",
        )
        DebugRouteCheckOverride.isDebugBuild = { false }
        DebugRouteCheckOverride.show(seeded)

        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = Intent(context, SetupActivity::class.java)
            .putExtra(SetupActivity.EXTRA_STEP, SetupStep.ROUTE_MISMATCH.name)
        ActivityScenario.launch<SetupActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(SetupStep.ROUTE_MISMATCH, activity.currentStepForTest)
                // Ignored: verifyState was never set by a real check either (no matching device
                // exists under Robolectric), so this stays honestly null.
                assertNull(activity.verifyStateForTest)
            }
        }
    }
}
