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
import org.ort.core.capture.CaptureMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers

/**
 * R-943 (register, reviewer A4 run 5, halt): split out of `SetupActivityTest` (detekt's own
 * `LargeClass` ceiling — that file already covers every other `SetupActivity` seam) to prove
 * [DebugRouteCheckOverride] reaches S05 from exactly the shape the register names: a cold
 * [SetupActivity.EXTRA_STEP] launch straight at [SetupStep.VERIFY], no S04 tap ever made, and no
 * real audio device under Robolectric matching the stored selection, so [RealRouteCheck] could
 * never have started. [DebugRouteCheckOverride.show] must still reach
 * [SetupActivity.verifyStateForTest], proving [SetupActivity.RenderVerify] reads it *ahead of*, not
 * merely alongside, the real check's own "a selection exists" gate — the same "override wins
 * outright" shape [DebugRigLinkPortOverride] already establishes for S10b
 * ([DebugRigLinkPortOverrideTest]'s own doc comment).
 */
@RunWith(RobolectricTestRunner::class)
class SetupActivityVerifyOverrideTest {

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

    /** Every gate except [SharedPreferencesSetupStore.KEY_SETUP_COMPLETE] itself satisfied — the
     * natural resume point is [SetupStep.READY], the same base `SetupActivityTest`'s own
     * `storeEverySetupGateExceptComplete` uses, so [SetupStep.VERIFY]'s `EXTRA_STEP` request (its
     * own ordinal well before [SetupStep.READY]'s) is honored rather than rejected. */
    private fun storeEverySetupGateExceptComplete() {
        ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)
            .edit()
            .putBoolean(SharedPreferencesSetupStore.KEY_WELCOME_SEEN, true)
            .putString(SharedPreferencesSetupStore.KEY_CAPTURE_MODE, CaptureMode.USB_RADIO.name)
            .putBoolean(SharedPreferencesSetupStore.KEY_INPUT_VERIFIED, true)
            .putString(SharedPreferencesSetupStore.KEY_SELECTED_INPUT_ID, "usb-1")
            .putBoolean(SharedPreferencesSetupStore.KEY_LEVEL_IN_BAND, true)
            .putBoolean(SharedPreferencesSetupStore.KEY_OVERNIGHT_SEEN, true)
            .putString(SharedPreferencesSetupStore.KEY_RADIO_CHOICE, RadioChoice.NONE.name)
            .apply()
    }

    @Test
    fun `R_943 a cold EXTRA_STEP VERIFY launch renders the seeded DebugRouteCheckOverride state`() {
        storeEverySetupGateExceptComplete()
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        val seeded = RouteCheckState.InProgress(
            passed = setOf(RouteCheckStage.NATIVE_RATE, RouteCheckStage.ROUTE_MATCH),
            nativeRateHz = 48_000,
            elapsedListeningMillis = 6_000L,
            routedDeviceLabel = "USB Audio Device",
            levelBars = listOf(0.1f, 0.3f, 0.5f, 0.4f, 0.2f),
            noiseFloorDbfs = -58.0,
        )
        DebugRouteCheckOverride.isDebugBuild = { true }
        DebugRouteCheckOverride.show(seeded)

        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = Intent(context, SetupActivity::class.java)
            .putExtra(SetupActivity.EXTRA_STEP, SetupStep.VERIFY.name)
        ActivityScenario.launch<SetupActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(SetupStep.VERIFY, activity.currentStepForTest)
                assertEquals(seeded, activity.verifyStateForTest)
            }
        }
    }

    @Test
    fun `R_943 DebugRouteCheckOverride is ignored the moment isDebugBuild reports false`() {
        storeEverySetupGateExceptComplete()
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        val seeded = RouteCheckState.InProgress(
            passed = setOf(RouteCheckStage.NATIVE_RATE),
            nativeRateHz = 48_000,
            elapsedListeningMillis = 1_000L,
        )
        DebugRouteCheckOverride.isDebugBuild = { false }
        DebugRouteCheckOverride.show(seeded)

        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = Intent(context, SetupActivity::class.java)
            .putExtra(SetupActivity.EXTRA_STEP, SetupStep.VERIFY.name)
        ActivityScenario.launch<SetupActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(SetupStep.VERIFY, activity.currentStepForTest)
                // Ignored: never the seeded snapshot. No real device matches "usb-1" under
                // Robolectric either, so the real RealRouteCheck path never produces one -- the
                // honest null "nothing to show yet" shape, never the debug seed.
                assertEquals(null, activity.verifyStateForTest)
            }
        }
    }
}
