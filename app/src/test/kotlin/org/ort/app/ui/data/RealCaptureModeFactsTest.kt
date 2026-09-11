package org.ort.app.ui.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.capture.CaptureMode
import org.ort.pipeline.rig.CaptureConfiguration
import org.ort.pipeline.rig.InMemoryCaptureConfigurationStore
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * R-821 (E2-A07 follow-up): [RealCaptureModeFacts.hasBeenConfigured] is a direct pass-through to
 * [org.ort.pipeline.rig.CaptureConfigurationStore.hasBeenConfigured] — see that method's own kdoc
 * for why it must exist at all (a fresh install and an operator who explicitly chose
 * [CaptureMode.LOCAL_MICROPHONE] are otherwise indistinguishable via [CaptureModeFacts.currentMode]
 * alone). Exercised here over [InMemoryCaptureConfigurationStore] rather than the real
 * `SharedPreferences`-backed one — the wiring under test is the pass-through itself, already proven
 * against the real store by `SharedPreferencesCaptureConfigurationStoreTest` in `:pipeline`.
 */
@RunWith(RobolectricTestRunner::class)
public class RealCaptureModeFactsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    @Requirement("R-821")
    public fun `R_821 a fresh store reports not configured`() {
        val facts = RealCaptureModeFacts(InMemoryCaptureConfigurationStore(isCapturing = { false }))
        assertFalse(facts.hasBeenConfigured())
    }

    @Test
    @Requirement("R-821")
    public fun `R_821 a store an operator has written to reports configured`() {
        val store = InMemoryCaptureConfigurationStore(isCapturing = { false })
        store.update(CaptureConfiguration(mode = CaptureMode.LOCAL_MICROPHONE, selectedInputId = null))
        val facts = RealCaptureModeFacts(store)
        assertTrue(facts.hasBeenConfigured())
    }

    @Test
    @Requirement("R-821")
    public fun `R_821 the context constructor reaches the real store honestly on a fresh install`() {
        // A fresh Robolectric app has never written org.ort.pipeline.capture_configuration --
        // proves the real SharedPreferences-backed path, not just the in-memory fake above.
        assertFalse(RealCaptureModeFacts(context).hasBeenConfigured())
    }
}
