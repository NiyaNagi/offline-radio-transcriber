package org.ort.app.ui.setup

import android.Manifest
import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.ort.app.MainActivity
import org.ort.app.ui.ReaderActivity
import org.ort.app.ui.navigation.ReaderDestination
import org.ort.core.capture.CaptureMode
import org.ort.pipeline.capture.InputStatus
import org.ort.pipeline.capture.LevelStatus
import org.ort.pipeline.capture.RigStatus
import org.ort.rig.NullRigModule
import org.ort.rig.RigTransportKind
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
            .putString(SharedPreferencesSetupStore.KEY_CAPTURE_MODE, CaptureMode.USB_RADIO.name)
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
            .edit()
            .putBoolean(SharedPreferencesSetupStore.KEY_WELCOME_SEEN, true)
            .putString(SharedPreferencesSetupStore.KEY_CAPTURE_MODE, CaptureMode.USB_RADIO.name)
            .apply()
        deny(Manifest.permission.RECORD_AUDIO)

        ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> assertEquals(SetupStep.MICROPHONE, activity.currentStepForTest) }
        }
    }

    // --- D33 (WPD): SetupStep.MODE is the very first content gate ------------------------------

    @Test
    fun `D33 welcome seen but no capture mode chosen lands on Mode, before any permission`() {
        ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)
            .edit().putBoolean(SharedPreferencesSetupStore.KEY_WELCOME_SEEN, true).apply()
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
            .edit().putBoolean(SharedPreferencesSetupStore.KEY_WELCOME_SEEN, true).apply()
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

        ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.onChooseMode(CaptureMode.LOCAL_MICROPHONE) }
            scenario.onActivity { activity ->
                assertEquals(SetupStep.INPUT, activity.currentStepForTest)
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
            .edit().putBoolean(SharedPreferencesSetupStore.KEY_WELCOME_SEEN, true).apply()
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

        ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.onChooseMode(CaptureMode.USB_RADIO) }
            scenario.onActivity { activity ->
                assertEquals(SetupStep.INPUT, activity.currentStepForTest)
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
            .edit().putBoolean(SharedPreferencesSetupStore.KEY_WELCOME_SEEN, true).apply()
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
                assertEquals(SetupStep.NOTIFICATIONS, activity.currentStepForTest)
                val store = SharedPreferencesSetupStore(
                    activity.getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, 0),
                )
                assertEquals(CaptureMode.USB_RADIO, store.captureMode)
                assertTrue(store.bluetoothPermissionDeclined)
            }
        }
    }

    @Test
    fun `R_080 with every gate already satisfied, SetupActivity hands back to MainActivity immediately`() {
        val prefs = ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(SharedPreferencesSetupStore.KEY_WELCOME_SEEN, true)
            .putString(SharedPreferencesSetupStore.KEY_CAPTURE_MODE, CaptureMode.USB_RADIO.name)
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
            .edit().putBoolean(SharedPreferencesSetupStore.KEY_SETUP_COMPLETE, true).apply()
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
        // Not storeEverySetupGateExceptComplete() -- its own RADIO_CHOICE=NONE would defeat this
        // test before it starts; every other gate is set by hand instead.
        ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)
            .edit()
            .putBoolean(SharedPreferencesSetupStore.KEY_WELCOME_SEEN, true)
            .putString(SharedPreferencesSetupStore.KEY_CAPTURE_MODE, CaptureMode.USB_RADIO.name)
            .putBoolean(SharedPreferencesSetupStore.KEY_INPUT_VERIFIED, true)
            .putString(SharedPreferencesSetupStore.KEY_SELECTED_INPUT_ID, "usb-1")
            .putBoolean(SharedPreferencesSetupStore.KEY_LEVEL_IN_BAND, true)
            .putBoolean(SharedPreferencesSetupStore.KEY_OVERNIGHT_SEEN, true)
            .apply()
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
        assertEquals(145_230_000L, store.manualFrequencyHz)
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

    private companion object {
        /** [WelcomeScreen]'s own title text, `WelcomeHeader`'s own literal — kept as one constant
         * here rather than re-typed at each call site. */
        const val WELCOME_TITLE_TEXT = "Everything your radio heard, written down, on this phone only."
    }
}
