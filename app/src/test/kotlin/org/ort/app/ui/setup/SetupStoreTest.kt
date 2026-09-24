package org.ort.app.ui.setup

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.capture.CaptureMode
import org.ort.core.capture.RigTransportKind
import org.robolectric.RobolectricTestRunner

/**
 * R-080 (ui-conformance-plan WP9): [SharedPreferencesSetupStore] round-trips every field through
 * real `SharedPreferences` (Robolectric — a plain JVM fake would not prove the same serialization
 * a real device uses), and [SetupStore.snapshot]/[SetupStore.clearInputVerification] are exercised
 * against both the real store and [InMemorySetupStore] (constitution II's behavioural fake).
 */
@RunWith(RobolectricTestRunner::class)
class SetupStoreTest {

    private fun realStore(): SetupStore {
        val prefs = ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)
        prefs.edit().clear().commit()
        return SharedPreferencesSetupStore(prefs)
    }

    @Test
    fun `R_080 every field defaults honestly to not-yet-configured`() {
        val store = realStore()
        assert(!store.welcomeSeen)
        assert(!store.micRequested)
        assert(!store.notificationsSkipped)
        assert(store.selectedInputId == null)
        assert(!store.inputVerified)
        assert(store.verifiedNativeRateHz == null)
        assert(!store.levelInBand)
        assert(store.levelPeakDbfs == null)
        assert(!store.overnightStepSeen)
        assert(store.radioChoice == null)
        assert(!store.setupComplete)
        assert(store.captureMode == null)
        assert(!store.bluetoothPermissionDeclined)
        assert(store.rigId == null)
        assert(store.rigTransport == null)
        assert(store.rigBluetoothAddress == null)
        assert(!store.rigBluetoothVerified)
        assert(!store.modeOverriddenAudio)
        assert(!store.modeOverriddenRig)
    }

    @Test
    fun `R_080 every field round-trips through real SharedPreferences`() {
        val store = realStore()
        store.welcomeSeen = true
        store.micRequested = true
        store.notificationsSkipped = true
        store.selectedInputId = "usb-1"
        store.selectedInputLabel = "USB Audio Device"
        store.inputVerified = true
        store.verifiedNativeRateHz = 48_000
        store.verifiedResamplerIdentity = "48000 Hz -> 16000 Hz, resampled"
        store.verifiedAudioSource = "unprocessed"
        store.levelInBand = true
        store.levelPeakDbfs = -14.2
        store.captureGainDb = 6
        store.overnightStepSeen = true
        store.radioChoice = RadioChoice.TH_D75A
        store.setupComplete = true
        store.captureMode = CaptureMode.BLUETOOTH_RADIO
        store.bluetoothPermissionDeclined = true
        store.rigId = "kenwood-thd75a"
        store.rigTransport = RigTransportKind.BLUETOOTH_SPP
        store.rigBluetoothAddress = "AA:BB:CC:DD:EE:FF"
        store.rigBluetoothVerified = true
        store.modeOverriddenAudio = true
        store.modeOverriddenRig = true

        val reread = SharedPreferencesSetupStore(
            ApplicationProvider.getApplicationContext<Application>()
                .getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE),
        )

        assert(reread.welcomeSeen)
        assert(reread.micRequested)
        assert(reread.notificationsSkipped)
        assert(reread.selectedInputId == "usb-1")
        assert(reread.selectedInputLabel == "USB Audio Device")
        assert(reread.inputVerified)
        assert(reread.verifiedNativeRateHz == 48_000)
        assert(reread.verifiedResamplerIdentity == "48000 Hz -> 16000 Hz, resampled")
        assert(reread.verifiedAudioSource == "unprocessed")
        assert(reread.levelInBand)
        assert(reread.levelPeakDbfs == -14.2)
        assert(reread.captureGainDb == 6)
        assert(reread.overnightStepSeen)
        assert(reread.radioChoice == RadioChoice.TH_D75A)
        assert(reread.setupComplete)
        assert(reread.captureMode == CaptureMode.BLUETOOTH_RADIO)
        assert(reread.bluetoothPermissionDeclined)
        assert(reread.rigId == "kenwood-thd75a")
        assert(reread.rigTransport == RigTransportKind.BLUETOOTH_SPP)
        assert(reread.rigBluetoothAddress == "AA:BB:CC:DD:EE:FF")
        assert(reread.rigBluetoothVerified)
        assert(reread.modeOverriddenAudio)
        assert(reread.modeOverriddenRig)
    }

    @Test
    fun `R_081 clearInputVerification resets exactly the input and level facts`() {
        val store = InMemorySetupStore(
            selectedInputId = "usb-1",
            inputVerified = true,
            verifiedNativeRateHz = 48_000,
            verifiedResamplerIdentity = "x",
            verifiedAudioSource = "unprocessed",
            levelInBand = true,
            levelPeakDbfs = -14.0,
            captureGainDb = 6,
            radioChoice = RadioChoice.NONE,
            setupComplete = true,
        )

        store.clearInputVerification()

        assert(!store.inputVerified)
        assert(store.verifiedNativeRateHz == null)
        assert(store.verifiedResamplerIdentity == null)
        // R-1169: a fact about the route just abandoned, cleared with the rest of the verification.
        assert(store.verifiedAudioSource == null)
        assert(!store.levelInBand)
        assert(store.levelPeakDbfs == null)
        // Unrelated facts survive. R-1168: the gain is an operator preference about how quiet their
        // adapter is, not a measurement of a particular verified route -- changing the input must
        // not silently undo it.
        assert(store.captureGainDb == 6)
        assert(store.selectedInputId == "usb-1")
        assert(store.radioChoice == RadioChoice.NONE)
        assert(store.setupComplete)
    }

    @Test
    fun `R_080 snapshot mirrors exactly what SetupStateMachine needs`() {
        val store = InMemorySetupStore(
            welcomeSeen = true,
            selectedInputId = "usb-1",
            inputVerified = true,
            levelInBand = true,
            levelAcknowledged = true,
            radioChoice = RadioChoice.NONE,
            setupComplete = false,
        )

        val snapshot = store.snapshot()

        assert(snapshot.welcomeSeen)
        assert(snapshot.selectedInputId == "usb-1")
        assert(snapshot.inputVerified)
        assert(snapshot.levelInBand)
        assert(snapshot.levelAcknowledged)
        assert(snapshot.radioChoice == RadioChoice.NONE)
        assert(!snapshot.setupComplete)
    }

    /**
     * P39 (D58): the two facts this store cannot answer default to *the answer that adds no step* —
     * `requiredModelsInstalled = true`, `rigModuleAvailable = false` — and [SetupActivity]'s own
     * `currentSnapshot()` overrides both with the freshly read state. A fixture or a test that has not
     * thought about them is therefore wrong in the direction that shows the shortest flow, never in the
     * direction that invents a step nobody can satisfy.
     */
    @Test
    fun `AC_198 the two facts the store cannot know default to adding no step`() {
        val snapshot = InMemorySetupStore(welcomeSeen = true).snapshot()

        assert(snapshot.requiredModelsInstalled)
        assert(!snapshot.rigModuleAvailable)
    }

    /**
     * R-1170/P39: an acknowledgement is about one route's measured level, so changing the route clears
     * it along with the verification. Carrying it across would let an unresolved level on a device that
     * never produced a reading read as answered.
     */
    @Test
    fun `AC_201 clearInputVerification clears the level acknowledgement too`() {
        val store = InMemorySetupStore(inputVerified = true, levelInBand = true, levelAcknowledged = true)

        store.clearInputVerification()

        assert(!store.inputVerified)
        assert(!store.levelInBand)
        assert(!store.levelAcknowledged)
    }

    @Test
    fun `D33 snapshot mirrors the capture-mode and rig-transport axes too`() {
        val store = InMemorySetupStore(
            welcomeSeen = true,
            captureMode = CaptureMode.BLUETOOTH_RADIO,
            bluetoothPermissionDeclined = true,
            radioChoice = RadioChoice.TH_D75A,
            rigTransport = RigTransportKind.BLUETOOTH_SPP,
            rigBluetoothVerified = true,
        )

        val snapshot = store.snapshot()

        assert(snapshot.captureMode == CaptureMode.BLUETOOTH_RADIO)
        assert(snapshot.bluetoothPermissionDeclined)
        assert(snapshot.rigTransport == RigTransportKind.BLUETOOTH_SPP)
        assert(snapshot.rigBluetoothVerified)
    }
}
