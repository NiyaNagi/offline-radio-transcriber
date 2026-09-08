package org.ort.app.ui.setup

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
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
        assert(store.manualFrequencyHz == null)
        assert(!store.setupComplete)
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
        store.levelInBand = true
        store.levelPeakDbfs = -14.2
        store.overnightStepSeen = true
        store.radioChoice = RadioChoice.TH_D75A
        store.manualFrequencyHz = 145_230_000L
        store.setupComplete = true

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
        assert(reread.levelInBand)
        assert(reread.levelPeakDbfs == -14.2)
        assert(reread.overnightStepSeen)
        assert(reread.radioChoice == RadioChoice.TH_D75A)
        assert(reread.manualFrequencyHz == 145_230_000L)
        assert(reread.setupComplete)
    }

    @Test
    fun `R_081 clearInputVerification resets exactly the input and level facts`() {
        val store = InMemorySetupStore(
            selectedInputId = "usb-1",
            inputVerified = true,
            verifiedNativeRateHz = 48_000,
            verifiedResamplerIdentity = "x",
            levelInBand = true,
            levelPeakDbfs = -14.0,
            radioChoice = RadioChoice.NONE,
            setupComplete = true,
        )

        store.clearInputVerification()

        assert(!store.inputVerified)
        assert(store.verifiedNativeRateHz == null)
        assert(store.verifiedResamplerIdentity == null)
        assert(!store.levelInBand)
        assert(store.levelPeakDbfs == null)
        // Unrelated facts survive.
        assert(store.selectedInputId == "usb-1")
        assert(store.radioChoice == RadioChoice.NONE)
        assert(store.setupComplete)
    }

    @Test
    fun `R_080 snapshot mirrors exactly what SetupStateMachine needs`() {
        val store = InMemorySetupStore(
            welcomeSeen = true,
            notificationsSkipped = true,
            selectedInputId = "usb-1",
            inputVerified = true,
            levelInBand = true,
            overnightStepSeen = true,
            radioChoice = RadioChoice.NONE,
            setupComplete = false,
        )

        val snapshot = store.snapshot()

        assert(snapshot.welcomeSeen)
        assert(snapshot.notificationsSkipped)
        assert(snapshot.selectedInputId == "usb-1")
        assert(snapshot.inputVerified)
        assert(snapshot.levelInBand)
        assert(snapshot.overnightStepSeen)
        assert(snapshot.radioChoice == RadioChoice.NONE)
        assert(!snapshot.setupComplete)
    }
}
