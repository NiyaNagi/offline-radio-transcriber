package org.ort.app.ui.setup

import android.Manifest
import android.app.Application
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.debug.Scenarios
import org.ort.pipeline.capture.AsrAvailability
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.InputStatus
import org.ort.pipeline.capture.LevelStatus
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.capture.ShedStatus
import org.ort.pipeline.capture.StorageForecast
import org.ort.pipeline.capture.ThermalStatus
import org.ort.pipeline.capture.VadAvailability
import org.ort.pipeline.rig.SharedPreferencesCaptureConfigurationStore
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers

/**
 * R-1084 (register): the screenshot tour's `setup-models/S11a-models*` steps launch
 * [SetupActivity] cold with [SetupActivity.EXTRA_STEP] = `MODELS`, exactly the same seam
 * [SetupActivityVerifyOverrideTest] already proves for S05/[DebugRouteCheckOverride] — but the
 * register found the tour reporting `never settled to setup step 'MODELS' ... observed step
 * 'INPUT'` on the `full` variant, where every model is genuinely bundled.
 *
 * **What is actually true, confirmed by reading the code before writing this test**: on `full`,
 * [SetupStateMachine.stepFor] always resolves `requiredModelsInstalled = true`
 * ([SetupActivity.currentSnapshot] recomputes it from the real [org.ort.app.ui.data.ModelsController]
 * state, ignoring any override), so the *natural* resume point can never itself land on
 * [SetupStep.MODELS] on this flavor — exactly as the register observed for S05/[SetupStep.VERIFY],
 * which is likewise never returned by `stepFor`. That is not what blocks the request:
 * [SetupActivity.tryOpenAtRequestedStep] only rejects a requested step whose ordinal is *after*
 * the natural resume point (an attempt to skip an unmet earlier gate) — [SetupStep.MODELS]'s own
 * ordinal sits comfortably *before* [SetupStep.READY], the natural point once every gate has
 * cleared, so a `MODELS` request is honored precisely when the store's earlier gates (input,
 * level, overnight, radio) are already satisfied, the same shape
 * [SetupActivityVerifyOverrideTest.storeEverySetupGateExceptComplete] already establishes for
 * `VERIFY`.
 *
 * The real bug: `Scenarios.kt`'s own `setup-models` scenario left every gate after `captureMode`
 * unset, so the natural resume point was `INPUT` — *before* `MODELS` — and the request was
 * silently denied, falling back to the ordinary resume flow. This test drives the real,
 * production [SetupActivity]/[SetupStateMachine] gate through the real [Scenarios.load] (not a
 * hand-built store, so a regression in the scenario itself is caught, not only a regression in
 * the gate) and fails for exactly that reason before `setupModels()` is fixed to seed the same
 * later gates `setupVerified`/`setupRadio` already do.
 */
@RunWith(RobolectricTestRunner::class)
class SetupActivityModelsOverrideTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun resetProcessWideState() {
        AsrAvailability.reset()
        VadAvailability.reset()
        CaptureState.idle(clearSession = true)
        ShedStatus.reset()
        ThermalStatus.reset()
        RigStatus.reset()
        StorageForecast.reset()
        LevelStatus.reset()
        InputStatus.reset()
        DebugModelsSetupOverride.clear()
        DebugModelsSetupOverride.isDebugBuild = { org.ort.app.BuildConfig.DEBUG }
        context.getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences(
            SharedPreferencesCaptureConfigurationStore.PREFS_NAME,
            Application.MODE_PRIVATE,
        ).edit().clear().commit()
    }

    private fun grant(vararg permissions: String) {
        ReflectionHelpers.callInstanceMethod<Unit>(
            shadowOf(InstrumentationRegistry.getInstrumentation()),
            "grantPermissions",
            ReflectionHelpers.ClassParameter(Array<String>::class.java, arrayOf(*permissions)),
        )
    }

    @Test
    fun `R_1084 a cold EXTRA_STEP MODELS launch settles on MODELS after the real setup-models scenario loads`() =
        runTest {
            Scenarios.load(context, "setup-models")
            grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

            val intent = Intent(context, SetupActivity::class.java)
                .putExtra(SetupActivity.EXTRA_STEP, SetupStep.MODELS.name)
            ActivityScenario.launch<SetupActivity>(intent).use { scenario ->
                scenario.onActivity { activity ->
                    assertEquals(SetupStep.MODELS, activity.currentStepForTest)
                }
            }
        }
}
