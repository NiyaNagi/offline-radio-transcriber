package org.ort.app.ui.setup

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.capture.android.CaptureGain
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * R-1168, and directly against R-1171: **a gain slider with no consumer would have been the fourth
 * switch on the Settings capture screen that does nothing.** These are the end-to-end links that
 * make it provably consumed — the stored preference becoming the live, process-wide value
 * `AndroidAudioIo.read` applies (`AndroidAudioIoGainTest` proves the far end of that same chain).
 *
 * The `OrtApplication` case is the one that matters for the capture path specifically: on a cold
 * start after a reboot, the operator taps record from `MainActivity`, `RealCaptureService` builds
 * its own `AndroidAudioIo`, and Setup is never opened at all. If nothing applied the preference at
 * process start, the slider would work in onboarding and silently do nothing thereafter.
 */
@RunWith(RobolectricTestRunner::class)
class CaptureGainWiringTest {

    @After
    fun resetProcessWideGain() {
        CaptureGain.reset()
        prefs().edit().clear().commit()
    }

    private fun prefs() = ApplicationProvider.getApplicationContext<Application>()
        .getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, Application.MODE_PRIVATE)

    @Test
    @Requirement("FR-CAP-1", "R-1168")
    fun `FR_CAP_1 a stored gain becomes the live process wide gain`() {
        CaptureGainWiring.applyStoredGain(InMemorySetupStore(captureGainDb = 6))

        assertEquals(6, CaptureGain.decibels)
        assertTrue("6 dB must be a real multiplier, not left at unity", CaptureGain.linear > 1.5f)
    }

    @Test
    @Requirement("FR-CAP-1", "R-1168")
    fun `FR_CAP_1 no stored gain leaves the audio unaltered rather than guessing a default`() {
        CaptureGain.setGainDb(12)

        CaptureGainWiring.applyStoredGain(InMemorySetupStore(captureGainDb = null))

        assertEquals(CaptureGain.MIN_GAIN_DB, CaptureGain.decibels)
        assertEquals(CaptureGain.UNITY, CaptureGain.linear)
    }

    @Test
    @Requirement("FR-CAP-1", "R-1168")
    fun `FR_CAP_1 the gain stored on disk is what a real context applies`() {
        prefs().edit().putInt(SharedPreferencesSetupStore.KEY_CAPTURE_GAIN_DB, 9).commit()

        CaptureGainWiring.applyStoredGain(ApplicationProvider.getApplicationContext<Application>())

        assertEquals(9, CaptureGain.decibels)
    }

    @Test
    @Requirement("FR-CAP-1", "R-1168")
    fun `FR_CAP_1 app process startup applies the stored gain, for the path that never opens setup`() {
        prefs().edit().putInt(SharedPreferencesSetupStore.KEY_CAPTURE_GAIN_DB, 12).commit()
        CaptureGain.reset()

        // The real Application this suite already instantiates for every test — `onCreate` is what
        // runs on a cold start before MainActivity, RealCaptureService or anything else exists.
        ApplicationProvider.getApplicationContext<Application>().onCreate()

        assertEquals(
            "a cold start that never reaches Setup must still carry the operator's gain",
            12,
            CaptureGain.decibels,
        )
    }
}
