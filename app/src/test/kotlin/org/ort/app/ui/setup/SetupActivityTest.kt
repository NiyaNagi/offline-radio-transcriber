package org.ort.app.ui.setup

import android.Manifest
import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.ort.app.analytics.AnalyticsAppWiring
import org.ort.app.analytics.AnalyticsUploadRunOutcome
import org.ort.app.ui.ReaderActivity
import org.ort.app.ui.navigation.ReaderDestination
import org.ort.capture.android.CaptureGain
import org.ort.core.capture.CaptureMode
import org.ort.pipeline.capture.InputStatus
import org.ort.pipeline.capture.LevelStatus
import org.ort.pipeline.capture.RigStatus
import org.ort.rig.NullRigModule
import org.ort.rig.RigTransportKind
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
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
// R-1005b/c added the Bluetooth-step onResume and continue-without-connecting coverage on top of
// this file's own pre-existing breadth (this class's own doc comment: the Activity-level half of
// the whole guided sequence, deliberately one place per SetupActivity's own LargeClass rationale).
@Suppress("LargeClass")
@RunWith(RobolectricTestRunner::class)
class SetupActivityTest {

    @After
    fun tearDown() {
        clearPrefs()
        // Process-wide singletons (same pattern as CaptureState elsewhere in this suite) -- reset
        // so a stray value from this class never leaks into a later test.
        InputStatus.reset()
        LevelStatus.reset()
        RigStatus.reset()
        // R-802/tour-builder follow-up tests flip both debug seams -- a safety net alongside each
        // test's own try/finally, the same belt-and-suspenders style DebugRigLinkPortOverrideTest
        // itself uses, so a failing assertion mid-test can never leak a stub port or a flipped
        // isDebugBuild into an unrelated, later test in this same suite run.
        DebugRigLinkPortOverride.clear()
        DebugRigLinkPortOverride.isDebugBuild = { org.ort.app.BuildConfig.DEBUG }
        SetupActivity.isDebugBuild = { org.ort.app.BuildConfig.DEBUG }
        // P22 (AC-189): the same belt-and-suspenders reset for this package's own overnight-
        // survival test seam.
        DebugOvernightSurvivalOverride.clear()
        DebugOvernightSurvivalOverride.isDebugBuild = { org.ort.app.BuildConfig.DEBUG }
        AnalyticsAppWiring.resetForTest()
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
     * only what it adds.
     *
     * **D43/FR-AST-12 (play-flavor unit-test follow-up):** [SetupStateMachine.stepFor]'s
     * READY gate has read a real `requiredModelsInstalled` fact
     * ([SetupActivity.currentSnapshot]'s own `ModelsController.currentState` read) since P22 — on
     * the `full` flavor every [org.ort.app.ui.data.ModelCatalogEntry] is `bundled = true`, so that
     * fact was always vacuously satisfied and no fixture here ever needed to say so; on `play`
     * (D43, FR-AST-13) nothing is bundled at all, so a fresh Robolectric `filesDir` genuinely has
     * every model outstanding unless a fixture states otherwise. "Every gate... satisfied" now
     * includes this one on both flavors, so it is stated here explicitly — the same fix this
     * function's own history already applied once for [SetupSnapshot.jurisdictionNoticeSeen]
     * (P22's own regression, see that field's doc comment) — rather than leaving every caller of
     * this helper to resume on [SetupStep.MODELS] instead of [SetupStep.READY] on `play`.
     *
     * **P28 (D42, FR-ANL-10, AC-180) applies the identical fix a third time**, for
     * [SetupSnapshot.analyticsConsentSeen] — the new gate sits between `requiredModelsInstalled`
     * and `setupComplete`, so "every gate except complete" now includes it too.
     */
    private fun storeEverySetupGateExceptComplete() {
        ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)
            .edit()
            .putBoolean(SharedPreferencesSetupStore.KEY_WELCOME_SEEN, true)
            .putBoolean(SharedPreferencesSetupStore.KEY_JURISDICTION_NOTICE_SEEN, true)
            .putString(SharedPreferencesSetupStore.KEY_CAPTURE_MODE, CaptureMode.USB_RADIO.name)
            .putBoolean(SharedPreferencesSetupStore.KEY_INPUT_VERIFIED, true)
            .putString(SharedPreferencesSetupStore.KEY_SELECTED_INPUT_ID, "usb-1")
            .putBoolean(SharedPreferencesSetupStore.KEY_LEVEL_IN_BAND, true)
            .putBoolean(SharedPreferencesSetupStore.KEY_OVERNIGHT_SEEN, true)
            .putString(SharedPreferencesSetupStore.KEY_RADIO_CHOICE, RadioChoice.NONE.name)
            .putBoolean(SharedPreferencesSetupStore.KEY_ANALYTICS_CONSENT_SEEN, true)
            .apply()
        org.ort.app.debug.ScenarioFixtures.installEveryModelFixtureAtRealSize(
            ApplicationProvider.getApplicationContext(),
        )
    }

    @Test
    fun `R_080 a fresh install lands on Welcome before any permission is checked`() {
        deny(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

        ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> assertEquals(SetupStep.WELCOME, activity.currentStepForTest) }
        }
    }

    /**
     * **AC-204**, the resume half: there is no microphone explainer step to resume onto any more. An
     * ungranted microphone lands on the mode surface — the one carrying the rationale and firing the
     * dialog — so one more tap re-fires the system request with the reason already on screen.
     */
    @Test
    fun `AC_204 once Welcome is seen and the microphone is not granted, resumes on Mode, not an explainer`() {
        ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)
            .edit()
            .putBoolean(SharedPreferencesSetupStore.KEY_WELCOME_SEEN, true)
            .putString(SharedPreferencesSetupStore.KEY_CAPTURE_MODE, CaptureMode.USB_RADIO.name)
            .apply()
        deny(Manifest.permission.RECORD_AUDIO)

        ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> assertEquals(SetupStep.MODE, activity.currentStepForTest) }
        }
    }

    /**
     * **AC-204, the half that matters most**: choosing a mode fires the system microphone request
     * *itself*. The explainer screen that used to sit in front of it is deleted, so if this tap did
     * not request the permission, nothing would — and the flow would silently stall on a screen with
     * no primary button at all.
     */
    @Test
    fun `AC_204 choosing a mode requests RECORD_AUDIO directly, with no explainer step in between`() {
        setupPrefs().edit().putBoolean(SharedPreferencesSetupStore.KEY_WELCOME_SEEN, true).commit()
        deny(Manifest.permission.RECORD_AUDIO)

        ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(SetupStep.MODE, activity.currentStepForTest)
                activity.onChooseMode(CaptureMode.LOCAL_MICROPHONE)
            }
            scenario.onActivity { activity ->
                val requested = shadowOf(activity).lastRequestedPermission?.requestedPermissions?.toList()
                assertTrue(
                    "the mode tap must fire the system microphone dialog itself, got $requested",
                    requested?.contains(Manifest.permission.RECORD_AUDIO) == true,
                )
            }
        }
    }

    /**
     * **AC-198, walked as a real first run rather than reasoned about.** Every answer is given through
     * the activity's own callbacks, in the order the flow asks for them, and the distinct screens are
     * counted: four, with every model present. The system permission dialog is not a screen and is not
     * counted (AC-198 says so in terms).
     *
     * `SetupStateMachineTest` walks the same ladder as a pure function; this walks it through the real
     * `SharedPreferences`, the real `ModelsController` read and the real step dispatch, which is where
     * a gate that the state machine does not know about — a `tryOpenAtRequestedStep`, an `onResume`
     * re-derivation — would show up.
     */
    @Test
    fun `AC_198 a real first run reaches Ready in four screens, the system dialog not counted`() {
        org.ort.app.debug.ScenarioFixtures.installEveryModelFixtureAtRealSize(
            ApplicationProvider.getApplicationContext(),
        )
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

        val walked = mutableListOf<SetupStep>()
        ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
            repeat(FIRST_RUN_WALK_BOUND) {
                var done = false
                scenario.onActivity { activity ->
                    val step = checkNotNull(activity.currentStepForTest) { "setup must always name a step" }
                    if (walked.lastOrNull() != step) walked += step
                    when (step) {
                        SetupStep.WELCOME -> activity.onBegin()
                        SetupStep.MODE -> activity.onChooseMode(CaptureMode.LOCAL_MICROPHONE)
                        // The real sequence this screen asks for, through its own callbacks: pick a
                        // route, run the check, and continue once it passes. `onStartVerify` is what
                        // records the selection (constitution IV — a route nobody verified is not a
                        // selection), so skipping it would leave the gate unsatisfied forever, which is
                        // exactly what this walk is bounded to catch.
                        SetupStep.LISTEN -> {
                            activity.onSelectInput(FIRST_ROUTE_ID)
                            activity.onStartVerify()
                            activity.onVerifyStateChanged(passedRouteCheck())
                            activity.onListenContinue()
                        }
                        SetupStep.READY -> done = true
                        else -> error("a first run reached $step, which is not one of the four screens")
                    }
                }
                if (done) return@use
            }
            error("the flow never reached READY — walked $walked")
        }

        assertEquals(
            "AC-198: no more than four screens on a first run with every model present",
            listOf(SetupStep.WELCOME, SetupStep.MODE, SetupStep.LISTEN, SetupStep.READY),
            walked,
        )
    }

    /** A route check that genuinely passed — the only state that may ever write
     * `SetupStore.inputVerified` (constitution IV). */
    private fun passedRouteCheck() = RouteCheckState.Passed(
        nativeRateHz = 48_000,
        resamplerDescription = "polyphase/v1 48000->16000",
        routedDeviceLabel = "Built-in microphone",
        levelBars = listOf(0.4f),
        noiseFloorDbfs = -58.0,
        audioSourceLabel = "unprocessed",
    )

    // --- AC-166 / AC-180 / AC-203: folded onto Welcome, not deleted (P39, D58) ---------------------

    /**
     * **AC-166 as amended by D58.** The jurisdiction notice is no longer a step; it is an acknowledged
     * line on Welcome with its full text one tap away. The criterion's two binding halves are unchanged
     * and are what this asserts: it is *recorded acknowledged* before capture, and it is recorded by the
     * one action that acknowledges it. A flow that simply stopped writing the flag would look identical
     * from the outside until the next launch re-asked, which is exactly why the flag is the assertion.
     */
    @Test
    fun `AC_166 Begin records the jurisdiction notice acknowledged and proceeds straight to Mode`() {
        deny(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

        ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(SetupStep.WELCOME, activity.currentStepForTest)
                activity.onBegin()
                assertEquals(SetupStep.MODE, activity.currentStepForTest)
            }
        }
        assertTrue(
            "the notice must be recorded acknowledged so it never silently re-asks on the next launch",
            setupPrefs().getBoolean(SharedPreferencesSetupStore.KEY_JURISDICTION_NOTICE_SEEN, false),
        )
    }

    /** **AC-180 as amended by D58**: the analytics disclosure is shown on Welcome, and the same one
     * action records it. Unchecked-by-default and declining-costs-nothing are unchanged — neither this
     * flag nor the two tiers ever gate anything (FR-ANL-10). */
    @Test
    fun `AC_180 Begin records the analytics disclosure seen, and it never gates the flow`() {
        deny(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

        ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.onBegin() }
            scenario.onActivity { activity ->
                assertEquals(SetupStep.MODE, activity.currentStepForTest)
            }
        }
        assertTrue(
            "the disclosure must be recorded seen, exactly as the dedicated step used to record it",
            setupPrefs().getBoolean(SharedPreferencesSetupStore.KEY_ANALYTICS_CONSENT_SEEN, false),
        )
    }

    // --- D33 (WPD): SetupStep.MODE is the very first content gate ------------------------------

    @Test
    fun `D33 welcome seen but no capture mode chosen lands on Mode, before any permission`() {
        ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)
            .edit()
            .putBoolean(SharedPreferencesSetupStore.KEY_WELCOME_SEEN, true)
            .putBoolean(SharedPreferencesSetupStore.KEY_JURISDICTION_NOTICE_SEEN, true)
            .apply()
        deny(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

        ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> assertEquals(SetupStep.MODE, activity.currentStepForTest) }
        }
    }

    /** AC-127 (one of three, local-microphone lane) — S00 presets both axes and setup completes
     * end to end with no further route/rig decision needed at all. */
    @Test
    fun `AC_127_local_microphone_mode_completes_onboarding_end_to_end`() {
        ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)
            .edit()
            .putBoolean(SharedPreferencesSetupStore.KEY_WELCOME_SEEN, true)
            .putBoolean(SharedPreferencesSetupStore.KEY_JURISDICTION_NOTICE_SEEN, true)
            .apply()
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

        ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.onChooseMode(CaptureMode.LOCAL_MICROPHONE) }
            scenario.onActivity { activity ->
                assertEquals(SetupStep.LISTEN, activity.currentStepForTest)
                val store = SharedPreferencesSetupStore(
                    activity.getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, 0),
                )
                assertEquals(CaptureMode.LOCAL_MICROPHONE, store.captureMode)
                // No rig transport at all for local-microphone mode -- FR-CAP-8's own table.
                assertEquals(null, store.rigTransport)
            }
        }
    }

    /** AC-127 (USB lane) — choosing USB-connected radio proceeds toward Input with the USB preset
     * applied to the rig-transport axis, no Bluetooth permission ever asked. */
    @Test
    fun `AC_127_usb_radio_mode_presets_usb_serial_and_never_asks_for_bluetooth`() {
        ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)
            .edit()
            .putBoolean(SharedPreferencesSetupStore.KEY_WELCOME_SEEN, true)
            .putBoolean(SharedPreferencesSetupStore.KEY_JURISDICTION_NOTICE_SEEN, true)
            .apply()
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

        ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.onChooseMode(CaptureMode.USB_RADIO) }
            scenario.onActivity { activity ->
                assertEquals(SetupStep.LISTEN, activity.currentStepForTest)
                val store = SharedPreferencesSetupStore(
                    activity.getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, 0),
                )
                assertEquals(
                    org.ort.core.capture.RigTransportKind.USB_SERIAL,
                    store.rigTransport,
                )
            }
        }
    }

    /** AC-127 (Bluetooth lane) — choosing Bluetooth-connected radio inserts S02c after the
     * microphone step and presets Bluetooth SPP for the rig, before Bluetooth permission is even
     * granted (FR-CAP-9: presetting is independent of the permission ask that follows it). */
    @Test
    fun `AC_127_bluetooth_radio_mode_inserts_the_nearby_devices_step_after_microphone`() {
        ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)
            .edit()
            .putBoolean(SharedPreferencesSetupStore.KEY_WELCOME_SEEN, true)
            .putBoolean(SharedPreferencesSetupStore.KEY_JURISDICTION_NOTICE_SEEN, true)
            .apply()
        grant(Manifest.permission.RECORD_AUDIO)
        deny(Manifest.permission.POST_NOTIFICATIONS)
        deny(Manifest.permission.BLUETOOTH_CONNECT)

        ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.onChooseMode(CaptureMode.BLUETOOTH_RADIO) }
            scenario.onActivity { activity ->
                assertEquals(SetupStep.BLUETOOTH_PERMISSION, activity.currentStepForTest)
                val store = SharedPreferencesSetupStore(
                    activity.getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, 0),
                )
                assertEquals(
                    org.ort.core.capture.RigTransportKind.BLUETOOTH_SPP,
                    store.rigTransport,
                )
            }
        }
    }

    /** D33/S02c — declining flips the mode to USB and proceeds, never a dead end. */
    @Test
    fun `D33 declining Bluetooth permission flips the mode to USB and proceeds to Notifications`() {
        ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)
            .edit()
            .putBoolean(SharedPreferencesSetupStore.KEY_WELCOME_SEEN, true)
            .putBoolean(SharedPreferencesSetupStore.KEY_JURISDICTION_NOTICE_SEEN, true)
            .putString(SharedPreferencesSetupStore.KEY_CAPTURE_MODE, CaptureMode.BLUETOOTH_RADIO.name)
            .apply()
        grant(Manifest.permission.RECORD_AUDIO)
        deny(Manifest.permission.POST_NOTIFICATIONS)
        deny(Manifest.permission.BLUETOOTH_CONNECT)

        ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(SetupStep.BLUETOOTH_PERMISSION, activity.currentStepForTest)
            }
            scenario.onActivity { activity -> activity.onDeclineBluetoothPermission() }
            scenario.onActivity { activity ->
                assertEquals(SetupStep.LISTEN, activity.currentStepForTest)
                val store = SharedPreferencesSetupStore(
                    activity.getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, 0),
                )
                assertEquals(CaptureMode.USB_RADIO, store.captureMode)
                assertTrue(store.bluetoothPermissionDeclined)
            }
        }
    }

    @Test
    fun `FR_ANL_2_reaching a setup step submits a setup-funnel reached event`() {
        AnalyticsAppWiring.configureOnce(ApplicationProvider.getApplicationContext())
        deny(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

        ActivityScenario.launch(SetupActivity::class.java).use { }

        // Tier 1 is on by default (AC-172): reaching NotConfigured (not NothingQueued) proves the
        // WELCOME "reached" event landed in the real queue -- no endpoint is set in a test build
        // (D48).
        kotlinx.coroutines.runBlocking {
            assertEquals(AnalyticsUploadRunOutcome.NotConfigured, AnalyticsAppWiring.runUploadOnce())
        }
    }

    @Test
    fun `FR_ANL_2_declining Bluetooth permission submits a setup-funnel skipped event`() {
        AnalyticsAppWiring.configureOnce(ApplicationProvider.getApplicationContext())
        ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)
            .edit()
            .putBoolean(SharedPreferencesSetupStore.KEY_WELCOME_SEEN, true)
            .putBoolean(SharedPreferencesSetupStore.KEY_JURISDICTION_NOTICE_SEEN, true)
            .putString(SharedPreferencesSetupStore.KEY_CAPTURE_MODE, CaptureMode.BLUETOOTH_RADIO.name)
            .apply()
        grant(Manifest.permission.RECORD_AUDIO)
        deny(Manifest.permission.POST_NOTIFICATIONS)
        deny(Manifest.permission.BLUETOOTH_CONNECT)

        ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.onDeclineBluetoothPermission() }
        }

        kotlinx.coroutines.runBlocking {
            assertEquals(AnalyticsUploadRunOutcome.NotConfigured, AnalyticsAppWiring.runUploadOnce())
        }
    }

    /**
     * **D58's two structural rules, at the activity, and this test is the exact inversion of what it
     * used to assert.** It was named *"with every gate already satisfied, SetupActivity hands back to
     * MainActivity immediately"* — and that hand-back is R-1161's second half. Setup treating "nothing
     * left to do" as an instruction to return control to the router that had just sent it here, while
     * the router could send it straight back, is a cycle by construction.
     *
     * Now: every gate satisfied resolves to [SetupStep.READY] — a real destination with a real
     * `Start capture` press — **including when `setupComplete` is already true**, which is the latch
     * the terminal screen used to sit behind. Inverted rather than deleted, and strictly stronger: it
     * asserts both that the screen is shown and that nothing was started on the way.
     */
    @Test
    fun `R_1161 with every gate satisfied and setup already complete, Setup shows Ready and hands back to nobody`() {
        val prefs = ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(SharedPreferencesSetupStore.KEY_WELCOME_SEEN, true)
            .putBoolean(SharedPreferencesSetupStore.KEY_JURISDICTION_NOTICE_SEEN, true)
            .putString(SharedPreferencesSetupStore.KEY_CAPTURE_MODE, CaptureMode.USB_RADIO.name)
            .putBoolean(SharedPreferencesSetupStore.KEY_INPUT_VERIFIED, true)
            .putString(SharedPreferencesSetupStore.KEY_SELECTED_INPUT_ID, "usb-1")
            .putBoolean(SharedPreferencesSetupStore.KEY_LEVEL_IN_BAND, true)
            .putBoolean(SharedPreferencesSetupStore.KEY_OVERNIGHT_SEEN, true)
            .putString(SharedPreferencesSetupStore.KEY_RADIO_CHOICE, RadioChoice.NONE.name)
            .putBoolean(SharedPreferencesSetupStore.KEY_SETUP_COMPLETE, true)
            // P22 (AC-189): not this test's own concern -- without this, reconcileOvernightSurvival()
            // would reset KEY_OVERNIGHT_SEEN back to false (real evidence is genuinely absent in a
            // fresh Robolectric :data instance) and this "hands back immediately" case would resume
            // on Overnight instead.
            .putBoolean(SharedPreferencesSetupStore.KEY_OVERNIGHT_SURVIVAL_PROVEN, true)
            // P28 (D42, FR-ANL-10, AC-180): not this test's own concern either -- see
            // storeEverySetupGateExceptComplete's own doc comment for the identical fix.
            .putBoolean(SharedPreferencesSetupStore.KEY_ANALYTICS_CONSENT_SEEN, true)
            .apply()
        // D43/FR-AST-12: this test does not go through storeEverySetupGateExceptComplete() (it
        // inlines KEY_SETUP_COMPLETE itself) — see that helper's own doc comment for why the READY
        // gate now also needs every model genuinely installed on both flavors.
        org.ort.app.debug.ScenarioFixtures.installEveryModelFixtureAtRealSize(
            ApplicationProvider.getApplicationContext(),
        )
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

        ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(
                    "no gates remain, so the terminal screen is the answer -- never a hand-back",
                    SetupStep.READY,
                    activity.currentStepForTest,
                )
                assertFalse("setup must not finish itself on 'nothing left to do'", activity.isFinishing)
            }
        }
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertNull(
            "handing control back to the router that sent us here is half of R-1161's cycle",
            shadowOf(app).nextStartedActivity,
        )
    }

    @Test
    fun `R_085 requesting the microphone once and then a permanent denial resumes on MicrophoneDenied`() {
        val prefs = ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(SharedPreferencesSetupStore.KEY_WELCOME_SEEN, true)
            .putBoolean(SharedPreferencesSetupStore.KEY_JURISDICTION_NOTICE_SEEN, true)
            .putString(SharedPreferencesSetupStore.KEY_CAPTURE_MODE, CaptureMode.USB_RADIO.name)
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
            .edit()
            .putBoolean(SharedPreferencesSetupStore.KEY_SETUP_COMPLETE, true)
            .putBoolean(SharedPreferencesSetupStore.KEY_OVERNIGHT_SURVIVAL_PROVEN, true)
            .apply()
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = Intent(context, SetupActivity::class.java)
            .putExtra(SetupActivity.EXTRA_STEP, SetupStep.LISTEN.name)
        ActivityScenario.launch<SetupActivity>(intent).use { scenario ->
            scenario.onActivity { activity -> assertEquals(SetupStep.LISTEN, activity.currentStepForTest) }
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

    /**
     * E2-E15 (`spec/e2e-capture-modes-plan.md` WPD, FR-CAP-12) — `MainActivity`'s `CF11` re-entry
     * (`EXTRA_STEP = "MODE"`) opens S00 even once setup has fully completed: [SetupStep.MODE]'s
     * ordinal sits at or before every gate [SetupStateMachine.stepFor] would otherwise resume at,
     * and once setup is complete that function returns `null` altogether — [tryOpenAtRequestedStep]'s
     * `naturalNext != null && ...` guard is then vacuously satisfied for any requested step at all,
     * which is exactly the mechanism this test pins.
     */
    @Test
    fun `E2_E15 EXTRA_STEP MODE opens S00 even once setup is fully complete`() {
        storeEverySetupGateExceptComplete()
        ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)
            .edit()
            .putBoolean(SharedPreferencesSetupStore.KEY_SETUP_COMPLETE, true)
            .putBoolean(SharedPreferencesSetupStore.KEY_OVERNIGHT_SURVIVAL_PROVEN, true)
            .apply()
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = Intent(context, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_STEP, SetupStep.MODE.name)
        ActivityScenario.launch<SetupActivity>(intent).use { scenario ->
            scenario.onActivity { activity -> assertEquals(SetupStep.MODE, activity.currentStepForTest) }
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

    /**
     * R-125 (validator finding, register R-120..R-125, halt): [RigStatus.State.Absent] discovered
     * *while already on S11* (this test's `RigStatus.absent()` call, standing in for the rig
     * dropping out entirely between choosing the USB transport and the next recomposition —
     * `onResume`'s own re-check here) must route back to S09 with a banner, never leave the
     * operator on the blank screen the validator screenshotted. D33/P19 extends the walk through
     * S09b (rig transport is now its own decision, FR-RIG-13) rather than landing on S11 directly
     * from S09 the way the pre-catalogue three-row screen did.
     */
    @Test
    fun `R_125 an Absent rig discovered on S11 routes back to S09 with a banner, never a blank screen`() {
        RigStatus.connected("Kenwood TH-D75A", emptyList())
        storeEverySetupGateExceptComplete()
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

        ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val entry = activity.radioCatalogueForTest.entries().first { it.displayName.contains("TH-D75A") }
                activity.onChooseRig(entry)
            }
            scenario.onActivity { activity -> assertEquals(SetupStep.RIG_TRANSPORT, activity.currentStepForTest) }
            scenario.onActivity { activity ->
                activity.onSelectRigTransport(RigTransportKind.USB_SERIAL)
                activity.onConnectRigTransport()
            }
            scenario.onActivity { activity -> assertEquals(SetupStep.RADIO_VERIFIED, activity.currentStepForTest) }

            RigStatus.absent()
            // Drives a real onPause -> onResume cycle (onResume is not accessible from here
            // directly) so SetupActivity re-checks RigStatus.state exactly as a real backgrounded-
            // then-foregrounded operator visit would.
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            shadowOf(Looper.getMainLooper()).idle()

            scenario.onActivity { activity -> assertEquals(SetupStep.RADIO, activity.currentStepForTest) }
        }
    }

    // --- D33/E2-E09 (WPI's setup-rig-transport-preset scenario): S09b pre-selects the mode's -----
    // --- preset transport when the chosen rig supports it, same rule R-902 applies to S04 ----------

    private fun kenwoodEntry(activity: SetupActivity) =
        activity.radioCatalogueForTest.entries().first { it.displayName.contains("TH-D75A") }

    @Test
    fun `E2_E09 choosing a rig pre-selects the mode preset transport when the rig supports it`() {
        ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)
            .edit()
            .putBoolean(SharedPreferencesSetupStore.KEY_WELCOME_SEEN, true)
            .putBoolean(SharedPreferencesSetupStore.KEY_JURISDICTION_NOTICE_SEEN, true)
            .apply()
        grant(Manifest.permission.RECORD_AUDIO)
        deny(Manifest.permission.POST_NOTIFICATIONS)
        deny(Manifest.permission.BLUETOOTH_CONNECT)

        ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.onChooseMode(CaptureMode.BLUETOOTH_RADIO) }
            scenario.onActivity { activity -> activity.onDeclineBluetoothPermission() }
            // onDeclineBluetoothPermission flips the mode to USB (S02c's own "Not now"); re-choose
            // Bluetooth explicitly so this test proves the Bluetooth preset, not USB's.
            scenario.onActivity { activity -> activity.onChooseMode(CaptureMode.BLUETOOTH_RADIO) }
            scenario.onActivity { activity -> activity.onChooseRig(kenwoodEntry(activity)) }
            scenario.onActivity { activity ->
                assertEquals(SetupStep.RIG_TRANSPORT, activity.currentStepForTest)
                assertEquals(
                    "the TH-D75A supports Bluetooth SPP -- the mode's own preset must be pre-selected",
                    RigTransportKind.BLUETOOTH_SPP,
                    activity.selectedRigTransportKindForTest,
                )
            }
        }
    }

    /** Reproduces the exact gap WPI's `setup-rig-transport-preset` scenario found: `SetupStore`
     * seeded directly (`rigId`/`rigTransport` unset, never through the interactive
     * [SetupActivity.onChooseRig] tap this file's other tests drive through) — a cold/resumed
     * launch straight onto [SetupStep.RIG_TRANSPORT] must pre-select exactly as the interactive
     * path does. */
    @Test
    fun `E2_E09 a cold-opened S09b also pre-selects the mode preset transport`() {
        // P39: this test is about the rig branch, which stepFor enters only when a rig module
        // with a real CAT implementation exists. See enableRigBranch's own doc comment.
        enableRigBranch()
        ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)
            .edit()
            .putBoolean(SharedPreferencesSetupStore.KEY_WELCOME_SEEN, true)
            .putBoolean(SharedPreferencesSetupStore.KEY_JURISDICTION_NOTICE_SEEN, true)
            .putString(SharedPreferencesSetupStore.KEY_CAPTURE_MODE, CaptureMode.USB_RADIO.name)
            .putBoolean(SharedPreferencesSetupStore.KEY_INPUT_VERIFIED, true)
            .putString(SharedPreferencesSetupStore.KEY_SELECTED_INPUT_ID, "usb-1")
            .putBoolean(SharedPreferencesSetupStore.KEY_LEVEL_IN_BAND, true)
            .putBoolean(SharedPreferencesSetupStore.KEY_OVERNIGHT_SEEN, true)
            .putString(SharedPreferencesSetupStore.KEY_RADIO_CHOICE, RadioChoice.TH_D75A.name)
            .putString(
                SharedPreferencesSetupStore.KEY_RIG_ID,
                org.ort.rig.descriptor.BundledDescriptors.kenwoodThD75a().id,
            )
            .apply()
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

        ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(SetupStep.RIG_TRANSPORT, activity.currentStepForTest)
                assertEquals(
                    "USB mode's own preset must pre-select even on a cold/resumed launch",
                    RigTransportKind.USB_SERIAL,
                    activity.selectedRigTransportKindForTest,
                )
            }
        }
    }

    @Test
    fun `E2_E09 choosing the non-preset transport and connecting records the override`() {
        storeEverySetupGateExceptComplete() // USB_RADIO mode -- preset is USB_SERIAL.
        ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)
            .edit().putString(SharedPreferencesSetupStore.KEY_RADIO_CHOICE, RadioChoice.TH_D75A.name).apply()
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

        ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.onChooseRig(kenwoodEntry(activity)) }
            scenario.onActivity { activity ->
                assertEquals(RigTransportKind.USB_SERIAL, activity.selectedRigTransportKindForTest)
                activity.onSelectRigTransport(RigTransportKind.BLUETOOTH_SPP)
                activity.onConnectRigTransport()
            }
            scenario.onActivity { activity ->
                val store = SharedPreferencesSetupStore(
                    activity.getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, 0),
                )
                assertTrue("choosing Bluetooth over the USB preset must record the override", store.modeOverriddenRig)
            }
        }
    }

    /** R-941 (register, reviewer A3 run 4a): S09b's title read the descriptor's full display name
     * verbatim ("How is the Kenwood TH-D75A linked?") — the same manufacturer-prefix strip R-845
     * (CF06) and R-903 (S11) already apply. */
    @Test
    fun `R_941 S09b drops the manufacturer prefix from the title`() {
        // P39: this test is about the rig branch, which stepFor enters only when a rig module
        // with a real CAT implementation exists. See enableRigBranch's own doc comment.
        enableRigBranch()
        ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)
            .edit()
            .putBoolean(SharedPreferencesSetupStore.KEY_WELCOME_SEEN, true)
            .putBoolean(SharedPreferencesSetupStore.KEY_JURISDICTION_NOTICE_SEEN, true)
            .putString(SharedPreferencesSetupStore.KEY_CAPTURE_MODE, CaptureMode.USB_RADIO.name)
            .putBoolean(SharedPreferencesSetupStore.KEY_INPUT_VERIFIED, true)
            .putString(SharedPreferencesSetupStore.KEY_SELECTED_INPUT_ID, "usb-1")
            .putBoolean(SharedPreferencesSetupStore.KEY_LEVEL_IN_BAND, true)
            .putBoolean(SharedPreferencesSetupStore.KEY_OVERNIGHT_SEEN, true)
            .putString(SharedPreferencesSetupStore.KEY_RADIO_CHOICE, RadioChoice.TH_D75A.name)
            .putString(
                SharedPreferencesSetupStore.KEY_RIG_ID,
                org.ort.rig.descriptor.BundledDescriptors.kenwoodThD75a().id,
            )
            .apply()
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

        val activityRule = ActivityScenarioRule(SetupActivity::class.java)
        val rule = AndroidComposeTestRule(activityRule) { r ->
            var activity: SetupActivity? = null
            r.scenario.onActivity { activity = it }
            checkNotNull(activity) { "SetupActivity did not reach RESUMED" }
        }
        val statement = object : Statement() {
            override fun evaluate() {
                rule.waitForIdle()
                rule.onNodeWithText("How is the TH-D75A linked?").assertIsDisplayed()
            }
        }
        val description = Description.createTestDescription(SetupActivityTest::class.java, "r941TitleDropsPrefix")
        rule.apply(statement, description).evaluate()
    }

    // --- R-344 (validator pass 4, halt): S09's third row routes through a real frequency entry ---

    /**
     * Reproduces the exact defect first (choosing the row alone must NOT already satisfy the
     * `RADIO` gate — S10 collects the value, not the row tap itself), then walks it through to S12
     * and confirms the frequency the operator actually typed is what shows there — the same "S09 →
     * third row → field → S12" walk the finding names, driven the way every other test in this file
     * drives `SetupActivity` (real `Activity`, real `SharedPreferences`, no fabricated shortcut).
     */
    @Test
    fun `R_344 choosing No radio routes to the frequency field, not straight past RADIO`() {
        // P39: this test is about the rig branch, which stepFor enters only when a rig module
        // with a real CAT implementation exists. See enableRigBranch's own doc comment.
        enableRigBranch()
        // Not storeEverySetupGateExceptComplete() -- its own RADIO_CHOICE=NONE would defeat this
        // test before it starts; every other gate is set by hand instead.
        ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)
            .edit()
            .putBoolean(SharedPreferencesSetupStore.KEY_WELCOME_SEEN, true)
            .putBoolean(SharedPreferencesSetupStore.KEY_JURISDICTION_NOTICE_SEEN, true)
            .putString(SharedPreferencesSetupStore.KEY_CAPTURE_MODE, CaptureMode.USB_RADIO.name)
            .putBoolean(SharedPreferencesSetupStore.KEY_INPUT_VERIFIED, true)
            .putString(SharedPreferencesSetupStore.KEY_SELECTED_INPUT_ID, "usb-1")
            .putBoolean(SharedPreferencesSetupStore.KEY_LEVEL_IN_BAND, true)
            .putBoolean(SharedPreferencesSetupStore.KEY_OVERNIGHT_SEEN, true)
            // P28 (D42, FR-ANL-10, AC-180): see storeEverySetupGateExceptComplete's own doc
            // comment for the identical fix -- this walk also finishes at SetupStep.READY.
            .putBoolean(SharedPreferencesSetupStore.KEY_ANALYTICS_CONSENT_SEEN, true)
            .apply()
        // D43/FR-AST-12: this walk finishes at SetupStep.READY -- see
        // storeEverySetupGateExceptComplete()'s own doc comment for why the READY gate now also
        // needs every model genuinely installed on both flavors.
        org.ort.app.debug.ScenarioFixtures.installEveryModelFixtureAtRealSize(
            ApplicationProvider.getApplicationContext(),
        )
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

        ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> assertEquals(SetupStep.RADIO, activity.currentStepForTest) }

            scenario.onActivity { activity ->
                val nullEntry = activity.radioCatalogueForTest.entries().first { it.id == NullRigModule.ID }
                activity.onChooseRig(nullEntry)
            }
            scenario.onActivity { activity ->
                assertEquals(
                    "the row tap alone must not already satisfy the RADIO gate -- S10 collects the value",
                    SetupStep.RADIO_USB,
                    activity.currentStepForTest,
                )
            }

            scenario.onActivity { activity -> activity.onEnterFrequency(145_230_000L) }
            scenario.onActivity { activity -> assertEquals(SetupStep.READY, activity.currentStepForTest) }
        }

        val store = SharedPreferencesSetupStore(
            ApplicationProvider.getApplicationContext<Application>()
                .getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE),
        )
        assertEquals(RadioChoice.NONE, store.radioChoice)
        // P39/R-1167: the value lands in `CaptureConfigurationStore` -- the store `RealCaptureService`
        // itself reads -- not in `SetupStore`, which no longer carries it at all.
        assertEquals(145_230_000L, inForceManualFrequencyHz())
    }

    /** [SetupActivity.onEnterFrequency]'s own null guard (constitution I) -- a blank/unparseable
     * entry must never be what [RadioUsbScreen]'s disabled button lets through in practice, but this
     * proves the activity-level function refuses it too, not only the UI. */
    @Test
    fun `R_344 onEnterFrequency with a null value never advances or writes a fabricated choice`() {
        storeEverySetupGateExceptComplete()
        ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)
            .edit().remove(SharedPreferencesSetupStore.KEY_RADIO_CHOICE).apply()
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

        ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val nullEntry = activity.radioCatalogueForTest.entries().first { it.id == NullRigModule.ID }
                activity.onChooseRig(nullEntry)
            }
            scenario.onActivity { activity -> activity.onEnterFrequency(null) }
            scenario.onActivity { activity -> assertEquals(SetupStep.RADIO_USB, activity.currentStepForTest) }
        }

        val store = SharedPreferencesSetupStore(
            ApplicationProvider.getApplicationContext<Application>()
                .getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE),
        )
        assertEquals(null, store.radioChoice)
    }

    // --- R-802 (register, tour run 2): refresh the paired list on every entry to RIG_BLUETOOTH ---

    /** Every gate up to and including `RIG_TRANSPORT` satisfied, with a real Bluetooth-SPP rig
     * already chosen — the natural resume point is [SetupStep.RIG_BLUETOOTH] itself, exactly the
     * "process died mid-S10b" case R-802 names. */
    private fun storeGatedAtRigBluetooth() {
        ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)
            .edit()
            .putBoolean(SharedPreferencesSetupStore.KEY_WELCOME_SEEN, true)
            .putBoolean(SharedPreferencesSetupStore.KEY_JURISDICTION_NOTICE_SEEN, true)
            .putString(SharedPreferencesSetupStore.KEY_CAPTURE_MODE, CaptureMode.BLUETOOTH_RADIO.name)
            .putBoolean(SharedPreferencesSetupStore.KEY_INPUT_VERIFIED, true)
            .putString(SharedPreferencesSetupStore.KEY_SELECTED_INPUT_ID, "wired-1")
            .putBoolean(SharedPreferencesSetupStore.KEY_LEVEL_IN_BAND, true)
            .putBoolean(SharedPreferencesSetupStore.KEY_OVERNIGHT_SEEN, true)
            .putString(SharedPreferencesSetupStore.KEY_RADIO_CHOICE, RadioChoice.TH_D75A.name)
            .putString(
                SharedPreferencesSetupStore.KEY_RIG_ID,
                org.ort.rig.descriptor.BundledDescriptors.kenwoodThD75a().id,
            )
            .putString(SharedPreferencesSetupStore.KEY_RIG_TRANSPORT, RigTransportKind.BLUETOOTH_SPP.name)
            // P28 (D42, FR-ANL-10, AC-180): harmless for the callers of this helper that stop at
            // RIG_BLUETOOTH itself, and required for the one that walks all the way to READY --
            // see storeEverySetupGateExceptComplete's own doc comment for the identical fix.
            .putBoolean(SharedPreferencesSetupStore.KEY_ANALYTICS_CONSENT_SEEN, true)
            .apply()
        grant(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    }

    @Test
    fun `R_802 cold-opening on RIG_BLUETOOTH lists paired devices without a Refresh tap`() {
        storeGatedAtRigBluetooth()
        DebugRigLinkPortOverride.isDebugBuild = { true }
        DebugRigLinkPortOverride.show(
            InMemoryRigLinkPort(
                devices = listOf(
                    PairedDevice("TH-D75A", "AA:BB:CC:11:22:33", sppCapable = true),
                    PairedDevice("Handheld BT", "11:22:33:AA:BB:CC", sppCapable = false),
                ),
            ),
        )
        try {
            ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertEquals(SetupStep.RIG_BLUETOOTH, activity.currentStepForTest)
                    assertEquals(2, activity.rigBluetoothDevicesForTest.size)
                    assertEquals("AA:BB:CC:11:22:33", activity.rigBluetoothDevicesForTest[0].address)
                }
            }
        } finally {
            DebugRigLinkPortOverride.clear()
            DebugRigLinkPortOverride.isDebugBuild = { org.ort.app.BuildConfig.DEBUG }
        }
    }

    @Test
    fun `R_802 cold-opening on RIG_BLUETOOTH with permission denied shows NoPermission immediately`() {
        storeGatedAtRigBluetooth()
        DebugRigLinkPortOverride.isDebugBuild = { true }
        DebugRigLinkPortOverride.show(InMemoryRigLinkPort().apply { denyPermission() })
        try {
            ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertEquals(SetupStep.RIG_BLUETOOTH, activity.currentStepForTest)
                    assertEquals(RigLinkState.NoPermission, activity.rigLinkStateForTest)
                }
            }
        } finally {
            DebugRigLinkPortOverride.clear()
            DebugRigLinkPortOverride.isDebugBuild = { org.ort.app.BuildConfig.DEBUG }
        }
    }

    // --- R-1005b (device field report): onResume re-checks the Bluetooth steps, not just Refresh --

    /** A tiny local [RigLinkPort] whose [pairedDevices] answers with whatever [result] is set to
     * *right now*, mutable from the test itself — the shape needed to prove [refreshPairedDevices]
     * genuinely runs *again* on resume, not merely that a cold [SetupActivity.onCreate] runs it once
     * (the existing R-802 tests already prove that half). [InMemoryRigLinkPort] cannot express this:
     * its own `devices` list is immutable for the lifetime of one instance, and `denyPermission()` is
     * one-directional (a real device would be granted once and stay granted for the rest of the
     * process) — exactly backwards from what this test needs to script (denied, then granted, as a
     * real Settings round-trip produces). A mutable field, changed by the test *between* reads, is
     * also immune to exactly how many times Android's own lifecycle happens to call [pairedDevices]
     * during an ordinary launch (`ActivityScenario.launch` itself drives a real `onResume`) — every
     * read before the test changes [result] sees the same value regardless of how many of them there
     * are, so [callCount] is asserted only as "it increased", never as an exact number. */
    private class ScriptedRigLinkPort(initial: PairedDevicesResult) : RigLinkPort {
        var result: PairedDevicesResult = initial
        var callCount = 0
            private set

        override fun pairedDevices(): PairedDevicesResult {
            callCount += 1
            return result
        }

        override fun connect(address: String, expectedRigId: String) = kotlinx.coroutines.flow.emptyFlow<RigLinkState>()
    }

    /**
     * R-1005b: reproduces the reported bug directly. Before this fix, `onResume` only ever
     * re-checked [SetupStep.MICROPHONE_DENIED]/`null` — an operator who granted "Nearby devices"
     * from system Settings and returned had to tap `Refresh` by hand. [ScriptedRigLinkPort.result]
     * is changed only *after* the initial launch has already settled (proving the initial,
     * already-covered-by-R-802 read is not what this test is about), so the granted state becoming
     * visible is possible only if [refreshPairedDevices] actually runs again on the resume this test
     * drives — proving both halves the brief warns about: the call happens, and its own
     * [rigBluetoothDevicesForTest]/[rigLinkStateForTest] writes are what make the screen visibly
     * change even though [SetupStep.RIG_BLUETOOTH] itself does not.
     */
    @Test
    fun `R_1005b onResume on RIG_BLUETOOTH re-checks paired devices without a Refresh tap`() {
        storeGatedAtRigBluetooth()
        DebugRigLinkPortOverride.isDebugBuild = { true }
        val grantedDevice = PairedDevice("TH-D75A", "AA:BB:CC:11:22:33", sppCapable = true)
        val port = ScriptedRigLinkPort(PairedDevicesResult(devices = emptyList(), permissionGranted = false))
        DebugRigLinkPortOverride.show(port)
        try {
            ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertEquals(SetupStep.RIG_BLUETOOTH, activity.currentStepForTest)
                    assertEquals(RigLinkState.NoPermission, activity.rigLinkStateForTest)
                    assertTrue(activity.rigBluetoothDevicesForTest.isEmpty())
                }
                val countAfterLaunch = port.callCount

                // The permission is granted "in Settings" between reads -- the real shape of the
                // bug report, never observable through InMemoryRigLinkPort's one-directional flag.
                port.result = PairedDevicesResult(devices = listOf(grantedDevice), permissionGranted = true)

                // Drives a real onPause -> onResume cycle, exactly as a real backgrounded-in-
                // Settings-then-foregrounded operator visit would (this file's own R-125 pattern).
                scenario.moveToState(Lifecycle.State.STARTED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                shadowOf(Looper.getMainLooper()).idle()

                scenario.onActivity { activity ->
                    assertTrue(
                        "expected pairedDevices() to be read again on resume, was still $countAfterLaunch",
                        port.callCount > countAfterLaunch,
                    )
                    assertEquals(null, activity.rigLinkStateForTest)
                    assertEquals(listOf(grantedDevice), activity.rigBluetoothDevicesForTest)
                }
            }
        } finally {
            DebugRigLinkPortOverride.clear()
            DebugRigLinkPortOverride.isDebugBuild = { org.ort.app.BuildConfig.DEBUG }
        }
    }

    /**
     * R-1005b: the other half of the same fix — [SetupStep.BLUETOOTH_PERMISSION] (S02c, the
     * capture-mode Bluetooth-audio permission, a different gate from S10b's rig-link one above) is
     * driven by real Android permission state, not a debug port, so this exercises the fix through
     * the ordinary `grant`/`deny` shadow helpers already in this file rather than a scripted double.
     * Granting `BLUETOOTH_CONNECT` from Settings and returning must advance past this step on its
     * own — before this fix, `onResume` never re-checked it and the operator stayed stuck until some
     * unrelated action happened to call `refreshStep()`.
     */
    @Test
    fun `R_1005b onResume on BLUETOOTH_PERMISSION re-evaluates a permission granted from Settings`() {
        ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)
            .edit()
            .putBoolean(SharedPreferencesSetupStore.KEY_WELCOME_SEEN, true)
            .putBoolean(SharedPreferencesSetupStore.KEY_JURISDICTION_NOTICE_SEEN, true)
            .putString(SharedPreferencesSetupStore.KEY_CAPTURE_MODE, CaptureMode.BLUETOOTH_RADIO.name)
            .apply()
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        deny(Manifest.permission.BLUETOOTH_CONNECT)

        ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(SetupStep.BLUETOOTH_PERMISSION, activity.currentStepForTest)
            }

            grant(Manifest.permission.BLUETOOTH_CONNECT)
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            shadowOf(Looper.getMainLooper()).idle()

            scenario.onActivity { activity ->
                // Notifications was already granted above -- the next unmet gate past
                // BLUETOOTH_PERMISSION is INPUT (no route chosen yet), not NOTIFICATIONS.
                assertEquals(SetupStep.LISTEN, activity.currentStepForTest)
            }
        }
    }

    // --- R-1005a (device field report): BLUETOOTH_SCAN is requested alongside BLUETOOTH_CONNECT ---

    @Test
    fun `R_1005a requestRigBluetoothPermissions asks for BLUETOOTH_SCAN as well as BLUETOOTH_CONNECT`() {
        storeGatedAtRigBluetooth()
        deny(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        DebugRigLinkPortOverride.isDebugBuild = { true }
        DebugRigLinkPortOverride.show(InMemoryRigLinkPort().apply { denyPermission() })
        try {
            ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.requestRigBluetoothPermissions() }
                scenario.onActivity { activity ->
                    val requested = shadowOf(activity).lastRequestedPermission.requestedPermissions.toList()
                    assertTrue(
                        "expected BLUETOOTH_CONNECT and BLUETOOTH_SCAN both requested, got $requested",
                        requested.containsAll(
                            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN),
                        ),
                    )
                }
            }
        } finally {
            DebugRigLinkPortOverride.clear()
            DebugRigLinkPortOverride.isDebugBuild = { org.ort.app.BuildConfig.DEBUG }
        }
    }

    // --- R-1005c (device field report — a trapped operator): continue without connecting ----------

    /**
     * R-1005c: the state the escape hatch leaves setup in, precisely — lands on
     * [SetupStep.RADIO_USB] (FR-RIG-2's existing manual-frequency screen, honestly rendered against
     * [RigStatus.State.Absent] since no live rig connection exists either way), clears
     * [SetupStore.rigTransport] so the very next [SetupStateMachine.stepFor] does not immediately
     * route back to [SetupStep.RIG_BLUETOOTH], and leaves [SetupStore.rigId] untouched so a later
     * `RIG_TRANSPORT` re-entry (Settings' own `onChangeRigLink`) still resolves a real catalogue
     * entry rather than rendering blank.
     */
    @Test
    fun `R_1005c continue without connecting lands on RADIO_USB with the rig transport cleared`() {
        storeGatedAtRigBluetooth()
        DebugRigLinkPortOverride.isDebugBuild = { true }
        DebugRigLinkPortOverride.show(InMemoryRigLinkPort())
        try {
            ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertEquals(SetupStep.RIG_BLUETOOTH, activity.currentStepForTest)
                    activity.onContinueWithoutRigLink()
                }
                scenario.onActivity { activity ->
                    assertEquals(SetupStep.RADIO_USB, activity.currentStepForTest)
                    val store = SharedPreferencesSetupStore(
                        activity.getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, 0),
                    )
                    assertEquals(null, store.rigTransport)
                    // The rig choice itself survives -- never erased just because the link was
                    // declined this session (this function's own doc comment: retry stays cheap).
                    assertEquals(
                        org.ort.rig.descriptor.BundledDescriptors.kenwoodThD75a().id,
                        store.rigId,
                    )
                }
            }
        } finally {
            DebugRigLinkPortOverride.clear()
            DebugRigLinkPortOverride.isDebugBuild = { org.ort.app.BuildConfig.DEBUG }
        }
    }

    /**
     * R-1005c: entering a real frequency on the [SetupStep.RADIO_USB] landing completes setup the
     * same, already-verified way a genuine "no radio" choice does — [SetupStore.radioChoice]
     * becomes [RadioChoice.NONE] (via [SetupActivity.onEnterFrequency], unmodified by this feature)
     * and the natural next step is [SetupStep.READY], never a loop back to [SetupStep.RIG_BLUETOOTH].
     * This is the regression [onContinueRigBluetooth]'s own defensive `rigTransport` restore exists
     * for: without clearing [SetupStore.rigTransport] in [SetupActivity.onContinueWithoutRigLink],
     * [SetupStateMachine.needsRigBluetoothLink] would still read `BLUETOOTH_SPP`/`unverified` here
     * and trap the operator right back on S10b.
     */
    @Test
    fun `R_1005c entering a frequency after declining the link reaches READY, never loops back`() {
        storeGatedAtRigBluetooth()
        // D43/FR-AST-12: this walk finishes at SetupStep.READY -- see
        // storeEverySetupGateExceptComplete()'s own doc comment for why the READY gate now also
        // needs every model genuinely installed on both flavors.
        org.ort.app.debug.ScenarioFixtures.installEveryModelFixtureAtRealSize(
            ApplicationProvider.getApplicationContext(),
        )
        DebugRigLinkPortOverride.isDebugBuild = { true }
        DebugRigLinkPortOverride.show(InMemoryRigLinkPort())
        try {
            ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.onContinueWithoutRigLink() }
                scenario.onActivity { activity -> activity.onEnterFrequency(145_230_000L) }
                scenario.onActivity { activity ->
                    assertEquals(SetupStep.READY, activity.currentStepForTest)
                    val store = SharedPreferencesSetupStore(
                        activity.getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, 0),
                    )
                    assertEquals(RadioChoice.NONE, store.radioChoice)
                    assertEquals(145_230_000L, inForceManualFrequencyHz())
                }
            }
        } finally {
            DebugRigLinkPortOverride.clear()
            DebugRigLinkPortOverride.isDebugBuild = { org.ort.app.BuildConfig.DEBUG }
        }
    }

    // --- Tour-builder follow-up: EXTRA_DEBUG_RIG_BLUETOOTH_ADDRESS pre-selects a device on S10b ---

    /**
     * The tour can only relaunch [SetupActivity] with intent extras, never tap inside it —
     * [SetupActivity.onSelectRigBluetoothDevice] is normally a tap on a paired-device row. This
     * extra reaches the same function from a cold launch, so the scripted [InMemoryRigLinkPort]
     * drives the whole open -> identify -> verify sequence with no tap at all — proven here by the
     * final [RigLinkState.Verified] a real `AndroidComposeTestRule.waitForIdle()` settles on
     * (`RigBluetoothScreenTest`'s own `E2_E10`/`E2_E11` tests already prove each individual
     * [RigLinkState] renders its own checklist line correctly; the two `R-802` tests above prove
     * the synchronous, no-delay entry states this same extra also reaches).
     */
    @Test
    fun `tour-builder EXTRA_DEBUG_RIG_BLUETOOTH_ADDRESS selects the device and drives the checklist to Verified`() {
        storeGatedAtRigBluetooth()
        DebugRigLinkPortOverride.isDebugBuild = { true }
        SetupActivity.isDebugBuild = { true }
        val address = "AA:BB:CC:11:22:33"
        DebugRigLinkPortOverride.show(
            InMemoryRigLinkPort(devices = listOf(PairedDevice("TH-D75A", address, sppCapable = true)))
                .apply { useDefaultBehaviour(address) },
        )
        try {
            val context = ApplicationProvider.getApplicationContext<Application>()
            val intent = Intent(context, SetupActivity::class.java)
                .putExtra(SetupActivity.EXTRA_DEBUG_RIG_BLUETOOTH_ADDRESS, address)
            val activityRule = ActivityScenarioRule<SetupActivity>(intent)
            val rule = AndroidComposeTestRule(activityRule) { r ->
                var activity: SetupActivity? = null
                r.scenario.onActivity { activity = it }
                checkNotNull(activity) { "SetupActivity did not reach RESUMED" }
            }
            var selectedAddress: String? = null
            var finalState: RigLinkState? = null
            val statement = object : Statement() {
                override fun evaluate() {
                    // Compose-for-Robolectric's own idling (the same mechanism this file's
                    // welcomeTitleHeightPx helper relies on) drains the shadow main looper's
                    // scheduled tasks as part of settling the composition -- by the time this
                    // returns, InMemoryRigLinkPort's Opening -> Open -> Identified -> Verified
                    // sequence has already run to completion (its own SCRIPT_STEP_DELAY_MILLIS
                    // steps are milliseconds, not real device Bluetooth latency), so there is no
                    // separately-observable "still Opening" moment to assert here — proven instead
                    // by the immediate, synchronous read at the very top of this file's own R-802
                    // tests, and by `RigBluetoothScreenTest`'s `E2_E10`/`E2_E11` tests, which prove
                    // each individual RigLinkState renders its own checklist line correctly. This
                    // proves the wiring from the debug extra actually reaches all the way to a real
                    // Verified state, end to end.
                    rule.waitForIdle()
                    rule.activityRule.scenario.onActivity { activity ->
                        selectedAddress = activity.rigBluetoothSelectedAddressForTest
                        finalState = activity.rigLinkStateForTest
                    }
                    rule.activityRule.scenario.moveToState(Lifecycle.State.DESTROYED)
                }
            }
            val description = Description.createTestDescription(
                SetupActivityTest::class.java,
                "tourBuilderDebugRigBluetoothAddressDrivesChecklist",
            )
            rule.apply(statement, description).evaluate()

            assertEquals(address, selectedAddress)
            assertEquals(RigLinkState.Verified(InMemoryRigLinkPort.DEFAULT_VERIFIED_COMMANDS), finalState)
        } finally {
            DebugRigLinkPortOverride.clear()
            DebugRigLinkPortOverride.isDebugBuild = { org.ort.app.BuildConfig.DEBUG }
            SetupActivity.isDebugBuild = { org.ort.app.BuildConfig.DEBUG }
        }
    }

    // --- R-1016 (device field report pass 2, R-941's own sibling defect on S10b) ------------------

    /** R-1016 (register): S10b's title read the descriptor's full display name verbatim ("Connect
     * the Kenwood TH-D75A") — the same manufacturer-prefix strip R-941 already applied to S09b's
     * title (`SettingsPolling.stripManufacturerPrefix`, R-845/CF06's and R-903/S11's own helper,
     * reused here rather than a second one written). */
    @Test
    fun `R_1016 S10b drops the manufacturer prefix from the title`() {
        storeGatedAtRigBluetooth()
        DebugRigLinkPortOverride.isDebugBuild = { true }
        DebugRigLinkPortOverride.show(InMemoryRigLinkPort())
        try {
            val activityRule = ActivityScenarioRule(SetupActivity::class.java)
            val rule = AndroidComposeTestRule(activityRule) { r ->
                var activity: SetupActivity? = null
                r.scenario.onActivity { activity = it }
                checkNotNull(activity) { "SetupActivity did not reach RESUMED" }
            }
            val statement = object : Statement() {
                override fun evaluate() {
                    rule.waitForIdle()
                    rule.onNodeWithText("Connect the TH-D75A").assertIsDisplayed()
                }
            }
            val description = Description.createTestDescription(SetupActivityTest::class.java, "r1016TitleDropsPrefix")
            rule.apply(statement, description).evaluate()
        } finally {
            DebugRigLinkPortOverride.clear()
            DebugRigLinkPortOverride.isDebugBuild = { org.ort.app.BuildConfig.DEBUG }
        }
    }

    // --- R-1013/R-1014: onContinueRigBluetooth's own product-decision gate -----------------------

    /**
     * R-1013: [SetupActivity.onContinueRigBluetooth] must stay a no-op on
     * [RigLinkState.IdentifyTimedOut] — nothing ever answered, so there is no link to proceed with
     * (the product decision named in this round's own prompt). Drives the real checklist to this
     * terminal state the same way the tour-builder test above does (`rule.waitForIdle()` — this
     * file's own established mechanism for letting [InMemoryRigLinkPort]'s millisecond-delay script
     * run to completion under Robolectric).
     */
    @Test
    fun `R_1013 onContinueRigBluetooth is a no-op on IdentifyTimedOut, setup stays at RIG_BLUETOOTH`() {
        storeGatedAtRigBluetooth()
        DebugRigLinkPortOverride.isDebugBuild = { true }
        SetupActivity.isDebugBuild = { true }
        val address = "AA:BB:CC:11:22:33"
        DebugRigLinkPortOverride.show(
            InMemoryRigLinkPort(devices = listOf(PairedDevice("TH-D75A", address, sppCapable = true)))
                .apply { identifyTimesOut(address, timeoutMillis = 6_000L) },
        )
        try {
            val context = ApplicationProvider.getApplicationContext<Application>()
            val intent = Intent(context, SetupActivity::class.java)
                .putExtra(SetupActivity.EXTRA_DEBUG_RIG_BLUETOOTH_ADDRESS, address)
            val activityRule = ActivityScenarioRule<SetupActivity>(intent)
            val rule = AndroidComposeTestRule(activityRule) { r ->
                var activity: SetupActivity? = null
                r.scenario.onActivity { activity = it }
                checkNotNull(activity) { "SetupActivity did not reach RESUMED" }
            }
            var finalState: RigLinkState? = null
            var stepAfterContinue: SetupStep? = null
            var storeVerifiedFlag: Boolean? = null
            val statement = object : Statement() {
                override fun evaluate() {
                    rule.waitForIdle()
                    rule.activityRule.scenario.onActivity { activity ->
                        finalState = activity.rigLinkStateForTest
                        activity.onContinueRigBluetooth()
                        stepAfterContinue = activity.currentStepForTest
                        storeVerifiedFlag = SharedPreferencesSetupStore(
                            activity.getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, 0),
                        ).rigBluetoothVerified
                    }
                    rule.activityRule.scenario.moveToState(Lifecycle.State.DESTROYED)
                }
            }
            val description = Description.createTestDescription(
                SetupActivityTest::class.java,
                "R1013OnContinueRigBluetoothNoOpOnIdentifyTimedOut",
            )
            rule.apply(statement, description).evaluate()

            assertEquals(RigLinkState.IdentifyTimedOut("kenwood-thd75a", 6_000L), finalState)
            assertEquals(
                "Continue must remain a no-op on IdentifyTimedOut -- nothing ever answered",
                SetupStep.RIG_BLUETOOTH,
                stepAfterContinue,
            )
            assertEquals(false, storeVerifiedFlag)
        } finally {
            DebugRigLinkPortOverride.clear()
            DebugRigLinkPortOverride.isDebugBuild = { org.ort.app.BuildConfig.DEBUG }
            SetupActivity.isDebugBuild = { org.ort.app.BuildConfig.DEBUG }
        }
    }

    /**
     * R-1014: [SetupActivity.onContinueRigBluetooth] must proceed on
     * [RigLinkState.VerifyTimedOut] exactly the same as a full [RigLinkState.Verified] — partial
     * success, not failure — and carry the real missing-capability list forward to S11 via
     * [SetupActivity.rigLinkMissingCapabilitiesForTest], never rendering it there as fully verified.
     */
    @Test
    fun `R_1014 onContinueRigBluetooth proceeds on VerifyTimedOut, carrying the missing capabilities to S11`() {
        storeGatedAtRigBluetooth()
        DebugRigLinkPortOverride.isDebugBuild = { true }
        SetupActivity.isDebugBuild = { true }
        val address = "AA:BB:CC:11:22:33"
        DebugRigLinkPortOverride.show(
            InMemoryRigLinkPort(devices = listOf(PairedDevice("TH-D75A", address, sppCapable = true)))
                .apply {
                    verifyTimesOut(
                        address,
                        seenCapabilities = listOf("frequency"),
                        missingCapabilities = listOf("squelch", "signal strength"),
                        timeoutMillis = 12_000L,
                    )
                },
        )
        try {
            val context = ApplicationProvider.getApplicationContext<Application>()
            val intent = Intent(context, SetupActivity::class.java)
                .putExtra(SetupActivity.EXTRA_DEBUG_RIG_BLUETOOTH_ADDRESS, address)
            val activityRule = ActivityScenarioRule<SetupActivity>(intent)
            val rule = AndroidComposeTestRule(activityRule) { r ->
                var activity: SetupActivity? = null
                r.scenario.onActivity { activity = it }
                checkNotNull(activity) { "SetupActivity did not reach RESUMED" }
            }
            var finalState: RigLinkState? = null
            var stepAfterContinue: SetupStep? = null
            var missingAfterContinue: List<String>? = null
            var storeVerifiedFlag: Boolean? = null
            val statement = object : Statement() {
                override fun evaluate() {
                    rule.waitForIdle()
                    rule.activityRule.scenario.onActivity { activity ->
                        finalState = activity.rigLinkStateForTest
                        activity.onContinueRigBluetooth()
                        stepAfterContinue = activity.currentStepForTest
                        missingAfterContinue = activity.rigLinkMissingCapabilitiesForTest
                        storeVerifiedFlag = SharedPreferencesSetupStore(
                            activity.getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, 0),
                        ).rigBluetoothVerified
                    }
                    rule.activityRule.scenario.moveToState(Lifecycle.State.DESTROYED)
                }
            }
            val description = Description.createTestDescription(
                SetupActivityTest::class.java,
                "R1014OnContinueRigBluetoothProceedsOnVerifyTimedOut",
            )
            rule.apply(statement, description).evaluate()

            assertEquals(
                RigLinkState.VerifyTimedOut(
                    rigId = "kenwood-thd75a",
                    seenCapabilities = listOf("frequency"),
                    missingCapabilities = listOf("squelch", "signal strength"),
                    timeoutMillis = 12_000L,
                ),
                finalState,
            )
            assertEquals(SetupStep.RADIO_VERIFIED, stepAfterContinue)
            assertEquals(listOf("squelch", "signal strength"), missingAfterContinue)
            assertEquals(true, storeVerifiedFlag)
        } finally {
            DebugRigLinkPortOverride.clear()
            DebugRigLinkPortOverride.isDebugBuild = { org.ort.app.BuildConfig.DEBUG }
            SetupActivity.isDebugBuild = { org.ort.app.BuildConfig.DEBUG }
        }
    }

    @Test
    fun `tour-builder EXTRA_DEBUG_RIG_BLUETOOTH_ADDRESS is ignored in a release build`() {
        storeGatedAtRigBluetooth()
        DebugRigLinkPortOverride.isDebugBuild = { true }
        SetupActivity.isDebugBuild = { false }
        val address = "AA:BB:CC:11:22:33"
        DebugRigLinkPortOverride.show(
            InMemoryRigLinkPort(devices = listOf(PairedDevice("TH-D75A", address, sppCapable = true)))
                .apply { useDefaultBehaviour(address) },
        )
        try {
            val context = ApplicationProvider.getApplicationContext<Application>()
            val intent = Intent(context, SetupActivity::class.java)
                .putExtra(SetupActivity.EXTRA_DEBUG_RIG_BLUETOOTH_ADDRESS, address)
            ActivityScenario.launch<SetupActivity>(intent).use { scenario ->
                scenario.onActivity { activity ->
                    assertEquals(SetupStep.RIG_BLUETOOTH, activity.currentStepForTest)
                    assertEquals(null, activity.rigBluetoothSelectedAddressForTest)
                }
            }
        } finally {
            DebugRigLinkPortOverride.clear()
            DebugRigLinkPortOverride.isDebugBuild = { org.ort.app.BuildConfig.DEBUG }
            SetupActivity.isDebugBuild = { org.ort.app.BuildConfig.DEBUG }
        }
    }

    // --- WP12 screenshot-tour seam: EXTRA_FONT_SCALE renders Setup at a chosen scale in-process ---

    /**
     * The tour (package `org.ort.app.debug.tour`) reaches font scale 2.0 this way
     * rather than the *system* `font_scale` setting, which needs a relaunch and starves the one
     * emulator both the tour and a validator's own manual walk depend on — [SetupActivity]'s own
     * class doc has the full account. A fresh install (no `SharedPreferences` written) naturally
     * resumes on S01 ([WelcomeScreen]) with no setup needed, so this drives the real `Activity`
     * straight, no fabricated shortcut, comparing the *same* title node's real rendered height with
     * and without the extra.
     *
     * [GraphicsMode.Mode.NATIVE] is scoped to this one test method, not the module (a
     * `robolectric.properties` blanket override was tried first and reverted -- it broke text
     * measurement in several other suites' own tests elsewhere in `:app`, e.g. `CaptureStatusScreenTest`
     * and `FailureScreensTest`, presumably by changing how those screens' own layouts settle under a
     * real graphics shadow rather than the legacy one they were written against). Robolectric's
     * *default* graphics shadow does not vary `Text` measurement with [LocalDensity]'s `fontScale` at
     * all (confirmed by an isolated probe: identical measured height at 1.0 and 2.0); `NATIVE` is the
     * one Robolectric mode that does.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `R_TOUR_setup_font_scale a 2_0 extra renders the S01 title at twice the height of the 1_0 case`() {
        val heightAt1x = welcomeTitleHeightPx(fontScale = null)
        val heightAt2x = welcomeTitleHeightPx(fontScale = 2.0f)

        // A wrapped, multi-line Text's total block height need not double *exactly* (line-wrap
        // points can shift, and this real title's own line count is not a clean multiple at every
        // scale -- confirmed directly: 96px -> 172px, a genuine ~1.79x, not a rounding artefact),
        // so this allows a real tolerance rather than bit-exact equality -- still tight enough that
        // a scale left un-applied (ratio ~=1.0) or halved (a sign bug) fails immediately.
        val ratio = heightAt2x.toDouble() / heightAt1x.toDouble()
        assertTrue(
            "expected the S01 title to render at roughly twice the height at fontScale 2.0 " +
                "(1x=$heightAt1x px, 2x=$heightAt2x px, ratio=$ratio)",
            ratio in 1.6..2.3,
        )
    }

    /** Real rendered height (px) of S01's own title node — `null` launches with no
     * [SetupActivity.EXTRA_FONT_SCALE] extra at all (platform default), a real value sets it. Builds
     * the same [AndroidComposeTestRule] `createAndroidComposeRule<SetupActivity>()` itself builds
     * internally, given an [ActivityScenarioRule] constructed from a caller-chosen `Intent` instead
     * of a bare activity class -- `ReaderActivityDestinationSmokeTest.runReaderActivity`'s own
     * established pattern for exactly this seam (a fixed default launch intent has no way to carry
     * a per-test extra), applied and evaluated manually since the intent must be built before this
     * rule can exist, not before JUnit has picked a `@Test` method to run. */
    private fun welcomeTitleHeightPx(fontScale: Float?): Int {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent = Intent(context, SetupActivity::class.java).apply {
            if (fontScale != null) putExtra(SetupActivity.EXTRA_FONT_SCALE, fontScale)
        }
        val activityRule = ActivityScenarioRule<SetupActivity>(intent)
        val rule = AndroidComposeTestRule(activityRule) { r ->
            var activity: SetupActivity? = null
            r.scenario.onActivity { activity = it }
            checkNotNull(activity) { "SetupActivity did not reach RESUMED" }
        }
        var heightPx = -1
        val statement = object : Statement() {
            override fun evaluate() {
                rule.waitForIdle()
                heightPx = rule.onNodeWithText(WELCOME_TITLE_TEXT).fetchSemanticsNode().size.height
                rule.activityRule.scenario.moveToState(Lifecycle.State.DESTROYED)
            }
        }
        val description = Description.createTestDescription(
            SetupActivityTest::class.java,
            "welcomeTitleHeightPx[fontScale=$fontScale]",
        )
        rule.apply(statement, description).evaluate()
        check(heightPx >= 0) { "welcomeTitleHeightPx never measured a real height" }
        return heightPx
    }

    // --- P22: AC-189 -- the battery-exemption step recurs until the heartbeat proves survival ----

    /**
     * **P39 (D58): makes the rig branch reachable at all.** `SetupStateMachine.stepFor` enters
     * `RADIO`/`RIG_TRANSPORT`/`RIG_BLUETOOTH` only when a rig module with a real CAT implementation
     * exists, and none does on any shipped build — `SetupActivity.rigModuleAvailable()` reads exactly
     * the seam this installs. Every test below that is *about* the rig branch says so by calling this;
     * a test that does not call it is asserting the shipped state, in which the branch does not appear.
     *
     * Both flags are reset by `setUp`'s own safety net, which is why this needs no `finally` of its
     * own — the same belt-and-braces the surrounding rig tests already rely on.
     */
    private fun enableRigBranch(port: RigLinkPort = InMemoryRigLinkPort()) {
        DebugRigLinkPortOverride.isDebugBuild = { true }
        DebugRigLinkPortOverride.show(port)
    }

    /** Every gate [storeEverySetupGateExceptComplete] sets, plus [SharedPreferencesSetupStore
     * .KEY_SETUP_COMPLETE] itself — the device has already finished setup once before. */
    private fun storeSetupAlreadyComplete() {
        storeEverySetupGateExceptComplete()
        ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)
            .edit()
            .putBoolean(SharedPreferencesSetupStore.KEY_SETUP_COMPLETE, true)
            .apply()
    }

    /** The state of a device whose operator has never answered the overnight prompt. **P39: nothing
     * in the app clears this flag any more** — the router used to, and the composition of that reset
     * with `SetupActivity`'s own was R-1161. It is set by hand here to describe a device, not to
     * reproduce a code path. */
    /**
     * The frequency as the capture path would actually read it — `CaptureConfigurationStore`, not
     * `SetupStore`, which P39 stopped carrying it in at all (R-1167). The pending configuration is
     * preferred for the same reason `SetupActivity.inForceManualFrequencyHz` prefers it: `update`
     * writes there while capture is running (FR-CAP-12, AC-131).
     */
    private fun inForceManualFrequencyHz(): Long? {
        val store = org.ort.pipeline.rig.SharedPreferencesCaptureConfigurationStore(
            ApplicationProvider.getApplicationContext<Application>().getSharedPreferences(
                org.ort.pipeline.rig.SharedPreferencesCaptureConfigurationStore.PREFS_NAME,
                Application.MODE_PRIVATE,
            ),
        )
        return (store.pendingConfiguration() ?: store.current()).manualFrequencyHz
    }

    /**
     * **AC-202 / R-1167: the round trip that would have destroyed the operator's frequency.**
     *
     * P39 moves the frequency prompt out of onboarding and into the log's own header, which means
     * `SetupStore` stops being the thing that knows the answer. `CaptureConfigurationStore.update`
     * replaces the whole configuration, and `SetupActivity` calls it after every mutation to the
     * mode/route/rig axes — so before this change, re-entering setup from `Settings › Input` and
     * touching *anything* pushed a configuration whose `manualFrequencyHz` came from a `SetupStore`
     * field nothing writes any more. **`null` over a real frequency, silently.** No error, and the only
     * symptom is every subsequent over logged without a frequency.
     *
     * Driven as the operator's own sequence: a frequency is already recorded (as the log header records
     * it — straight into the configuration store), setup is then entered and a mode is chosen, which is
     * the most ordinary mutation there is. The frequency must still be there afterwards.
     */
    @Test
    @Requirement("FR-RIG-1", "AC-202")
    fun `AC_202 re-entering setup after a log-header frequency was set leaves that value intact`() {
        storeSetupAlreadyComplete()
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        setManualFrequencyAsTheLogHeaderWould(145_230_000L)

        ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
            // The most ordinary mutation in the flow, and the one Settings' own re-entry lands on.
            scenario.onActivity { activity -> activity.onChooseMode(CaptureMode.USB_RADIO) }
        }

        assertEquals(
            "re-entering setup must never overwrite the frequency the operator typed into the log header",
            145_230_000L,
            inForceManualFrequencyHz(),
        )
    }

    /** Exactly what the log header's own editor does: write the value to the configuration store, the
     * one `RealCaptureService` reads at session start. Nothing is written to `SetupStore` — that is the
     * whole point of the test above. */
    private fun setManualFrequencyAsTheLogHeaderWould(hz: Long) {
        val store = org.ort.pipeline.rig.SharedPreferencesCaptureConfigurationStore(
            ApplicationProvider.getApplicationContext<Application>().getSharedPreferences(
                org.ort.pipeline.rig.SharedPreferencesCaptureConfigurationStore.PREFS_NAME,
                Application.MODE_PRIVATE,
            ),
        )
        store.update(store.current().copy(manualFrequencyHz = hz))
    }

    // R-1188: `overnightIntent()` stood here, opening `SetupStep.OVERNIGHT` by `EXTRA_STEP` because
    // nothing in production could reach it. That was the whole finding — a screen kept alive by the
    // two tests below this helper, which are deleted with it. The battery ask has one surface now,
    // F24's banner, covered by `FailureHostTest`.

    private fun clearOvernightStepSeen() {
        ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)
            .edit()
            .putBoolean(SharedPreferencesSetupStore.KEY_OVERNIGHT_SEEN, false)
            .apply()
    }

    /**
     * **AC-199 / AC-189 as amended (P39, D58).** An operator who has never answered the overnight
     * prompt, on a device that has never proven survival, is **not** shown the overnight step — the
     * flow lands on its terminal screen. Overnight survival is evidence only a completed capture can
     * produce, so a pre-capture gate on it is unsatisfiable by construction, which is what deadlocked
     * every first-run operator.
     *
     * **Inverted, not deleted**: this test used to assert the opposite — *"Setup resumes on Overnight"*
     * — and the inversion is the finding. What it also asserted and still does: this run must not
     * fabricate the survival fact on the way (constitution IV: only real session evidence).
     */
    @Test
    fun `AC_199 an unanswered overnight prompt is no longer a gate, and Setup lands on Ready`() {
        storeSetupAlreadyComplete()
        clearOvernightStepSeen()
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        DebugOvernightSurvivalOverride.show(FakeOvernightSurvivalChecker(proven = false))

        ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(
                    "nothing may gate capture on evidence only capture can produce (AC-199)",
                    SetupStep.READY,
                    activity.currentStepForTest,
                )
            }
        }
        val survivalProven = ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)
            .getBoolean(SharedPreferencesSetupStore.KEY_OVERNIGHT_SURVIVAL_PROVEN, false)
        assertFalse("still unproven -- this run must not fabricate the fact", survivalProven)
    }

    /**
     * **R-1161**, the `SetupActivity` half, discriminating on its own: with the step already seen
     * and survival still unproven, `reconcileOvernightSurvival()` must leave
     * [SharedPreferencesSetupStore.KEY_OVERNIGHT_SEEN] alone and let this already-complete flow hand
     * straight back. Before the fix it set that flag back to `false` on **every** entry, so the two
     * Overnight buttons could never stay answered across the `MainActivity` -> here ->
     * `MainActivity` hop, and the operator's *any button I press just loops back* was literally
     * true. Reverting that one `else` branch turns this assertion red.
     */
    @Test
    fun `AC_189 R-1161 an already-seen overnight step is never reset back to unseen while unproven`() {
        storeSetupAlreadyComplete()
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        DebugOvernightSurvivalOverride.show(FakeOvernightSurvivalChecker(proven = false))

        ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> assertEquals(SetupStep.READY, activity.currentStepForTest) }
        }
        val stepSeen = ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)
            .getBoolean(SharedPreferencesSetupStore.KEY_OVERNIGHT_SEEN, false)
        assertTrue("re-arming the step on entry is what made the cycle total (R-1161)", stepSeen)
    }

    @Test
    fun `AC_189 proven survival latches true, and this already-complete flow lands on Ready`() {
        storeSetupAlreadyComplete()
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        DebugOvernightSurvivalOverride.show(FakeOvernightSurvivalChecker(proven = true))

        ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> assertEquals(SetupStep.READY, activity.currentStepForTest) }
        }
        val survivalProven = ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)
            .getBoolean(SharedPreferencesSetupStore.KEY_OVERNIGHT_SURVIVAL_PROVEN, false)
        assertTrue("real evidence must latch the fact so it is never re-checked once proven", survivalProven)
    }

    // R-1188: two tests stood here — `AC_189 skipping Overnight records the answer and never
    // re-shows it in the same launch` and `R_1162 returning to the app from Overnight hands back
    // without recording an answer`. Both opened `SetupStep.OVERNIGHT` deliberately by `EXTRA_STEP`,
    // and between them they were the only thing keeping that screen alive: no production path
    // reached it. Deleted with the screen rather than retargeted, because what they establish is
    // gone with it. **R-1162 is resolved by the same deletion** — its finding was that the screen
    // offered no exit, and F24 is a dismissable banner over a working app, never a takeover
    // (AC-199, asserted by `FailureHostTest`'s own `AC_199 the keep-running prompt never withholds
    // the destination beneath it`). The three tests above this comment keep what still applies: the
    // router no longer gates on overnight survival, and `reconcileOvernightSurvival` latches the
    // proven half without re-arming anything.

    // --- R-1168 / R-1169 / R-1170: the gain control, the recorded source, the never-disabled button

    /**
     * R-1168, and directly against R-1171 (three Settings switches persisted, rendered and read by
     * nobody): moving S07's slider must do **both** things — take effect now, on the live audio
     * path, and survive the process. Either alone would be the defect that register row exists to
     * stop repeating.
     */
    @Test
    @Requirement("FR-CAP-6", "R-1168")
    fun `FR_CAP_6 moving the gain slider takes effect immediately and is persisted`() {
        grant(Manifest.permission.RECORD_AUDIO)
        try {
            ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.onGainChanged(6)

                    assertEquals("the live audio path must see it now", 6, CaptureGain.decibels)
                    assertTrue("6 dB is a real multiplier, not unity", CaptureGain.linear > 1.5f)
                    assertEquals("and the slider must move with it", 6, activity.gainDbForTest)
                }
            }
            assertEquals(
                "it must survive the process that set it",
                6,
                setupPrefs().getInt(SharedPreferencesSetupStore.KEY_CAPTURE_GAIN_DB, -1),
            )
        } finally {
            CaptureGain.reset()
        }
    }

    @Test
    @Requirement("FR-CAP-6", "R-1168")
    fun `FR_CAP_6 a gain stored by an earlier run is live again before any meter opens a device`() {
        setupPrefs().edit().putInt(SharedPreferencesSetupStore.KEY_CAPTURE_GAIN_DB, 9).commit()
        CaptureGain.reset()
        grant(Manifest.permission.RECORD_AUDIO)
        try {
            ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertEquals(9, CaptureGain.decibels)
                    assertEquals(9, activity.gainDbForTest)
                }
            }
        } finally {
            CaptureGain.reset()
        }
    }

    /**
     * R-1170: `Continue` is no longer disabled for validation, so the unresolved state has to be
     * written down instead of blocked on — otherwise "keep the button lit" would simply lose the
     * finding. S12's Level row reads `levelInBand` together with the measured peak (`levelRow`'s
     * own doc comment), and this is what makes it light its amber `Fix`.
     */
    @Test
    @Requirement("FR-CAP-6", "R-1170")
    fun `FR_CAP_6 continuing with an out of band level records the unresolved state for S12`() {
        grant(Manifest.permission.RECORD_AUDIO)
        ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.onLevelStateChanged(
                    LevelCheckState.Reading(
                        LevelReading(
                            bars = listOf(0.1f),
                            peakDbfs = -40.0,
                            noiseFloorDbfs = -70.0,
                            band = LevelBand.TOO_QUIET,
                        ),
                    ),
                )
                activity.onListenContinue()
            }
        }

        assertFalse(
            "the operator proceeds, and the unresolved level is written down rather than lost",
            setupPrefs().getBoolean(SharedPreferencesSetupStore.KEY_LEVEL_IN_BAND, true),
        )
    }

    @Test
    @Requirement("FR-CAP-6", "R-1170")
    fun `FR_CAP_6 continuing with no level reading at all never leaves a stale in-band flag standing`() {
        setupPrefs().edit().putBoolean(SharedPreferencesSetupStore.KEY_LEVEL_IN_BAND, true).commit()
        grant(Manifest.permission.RECORD_AUDIO)

        ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.onListenContinue() }
        }

        assertFalse(
            "a step that never went green must not carry a green marker forward from an earlier route",
            setupPrefs().getBoolean(SharedPreferencesSetupStore.KEY_LEVEL_IN_BAND, true),
        )
    }

    /** R-1169: the third fact about a verified open, recorded beside the rate and the resampler. */
    @Test
    @Requirement("FR-CAP-1", "R-1169")
    fun `FR_CAP_1 a passed verification records which audio source the open obtained`() {
        grant(Manifest.permission.RECORD_AUDIO)
        ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.onVerifyStateChanged(
                    RouteCheckState.Passed(
                        nativeRateHz = 48_000,
                        resamplerDescription = "48000 Hz -> 16000 Hz, resampled",
                        audioSourceLabel = "unprocessed",
                    ),
                )
            }
        }

        assertEquals(
            "unprocessed",
            setupPrefs().getString(SharedPreferencesSetupStore.KEY_VERIFIED_AUDIO_SOURCE, null),
        )
    }

    private fun setupPrefs() = ApplicationProvider.getApplicationContext<Application>()
        .getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)

    private companion object {
        /** [WelcomeScreen]'s own title text, `WelcomeHeader`'s own literal — kept as one constant
         * here rather than re-typed at each call site. */
        // P39: the ", on this phone only" clause is gone from the title — the FR-ANL-14 sentence
        // directly beneath it says it correctly, once (AC-203), and the title no longer competes.
        const val WELCOME_TITLE_TEXT = "Everything your radio heard, written down."

        /** Generous enough for the real ladder, small enough that a cycle fails fast rather than
         * hanging the suite — a walk needing more than this has a gate it can never satisfy. */
        const val FIRST_RUN_WALK_BOUND = 12

        /** Any id at all: the walk's point is the *sequence of screens*, not which route was picked,
         * and `onSelectInput` records whatever it is given. */
        const val FIRST_ROUTE_ID = "mic-0"
    }
}
