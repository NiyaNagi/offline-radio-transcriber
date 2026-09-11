package org.ort.app.ui.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.core.capture.CaptureMode
import org.ort.pipeline.rig.CaptureConfiguration
import org.ort.pipeline.rig.SharedPreferencesCaptureConfigurationStore
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * Register R-821 (halt, `results/ui-audit/register.md`): an unset [org.ort.pipeline.rig
 * .CaptureConfigurationStore] must read "not set" on CF02/CF11, never silently default to
 * [CaptureMode.LOCAL_MICROPHONE] — that store's own [org.ort.pipeline.rig.CaptureConfiguration
 * .DEFAULT] is a fabricated fact when nothing was ever chosen (constitution I). [RealCaptureModeFacts]
 * is the seam that restores the distinction; this class proves it directly, against the real
 * `SharedPreferences`-backed store, not a fake standing in for it. [RealCaptureModeFacts] rests on
 * [org.ort.pipeline.rig.CaptureConfigurationStore.hasBeenConfigured] (E2-A07 follow-up) rather than
 * a raw `SharedPreferences` key read underneath the store, so this suite exercises that store
 * method directly (via a real [store] left unwritten, or written to) rather than poking its own
 * key layout.
 */
@RunWith(RobolectricTestRunner::class)
class CaptureModeFactsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun freshPrefs(name: String) =
        context.getSharedPreferences(name, Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    @Test
    @Requirement("FR-CAP-12")
    fun `R_821 currentMode is null before anything has ever been written`() {
        val prefsName = "r821-unset-${System.nanoTime()}"
        freshPrefs(prefsName)
        val store = SharedPreferencesCaptureConfigurationStore(context.getSharedPreferences(prefsName, 0))
        val facts = RealCaptureModeFacts(store)

        // The store's own `current()` already, silently, returns the fabricated default — the exact
        // fact this seam exists to not repeat to the operator.
        assert(store.current().mode == CaptureMode.LOCAL_MICROPHONE) {
            "test assumption broken: CaptureConfigurationStore.current() no longer defaults silently"
        }
        assert(facts.currentMode() == null) {
            "expected currentMode() to read null (not set) before any write, got ${facts.currentMode()}"
        }
    }

    @Test
    @Requirement("FR-CAP-12")
    fun `R_821 currentMode is real once the operator has actually chosen one, even LOCAL_MICROPHONE itself`() {
        val prefsName = "r821-set-${System.nanoTime()}"
        freshPrefs(prefsName)
        val store = SharedPreferencesCaptureConfigurationStore(
            context.getSharedPreferences(prefsName, 0),
            isCapturing = { false },
        )
        // A real, explicit choice of the same mode the fabricated default would have claimed —
        // proving this is about honesty of *provenance*, not merely "never show LOCAL_MICROPHONE".
        store.update(CaptureConfiguration.DEFAULT.copy(mode = CaptureMode.LOCAL_MICROPHONE))
        val facts = RealCaptureModeFacts(store)

        assert(facts.currentMode() == CaptureMode.LOCAL_MICROPHONE) {
            "expected the real, explicitly-written mode, got ${facts.currentMode()}"
        }
    }

    @Test
    @Requirement("FR-CAP-12")
    fun `R_821 the real Context constructor reads not-set on a fresh install`() {
        // A brand-new `RealCaptureModeFacts(context)` over this test's own fresh Robolectric
        // application — nothing in this suite has written `realCaptureConfigurationStore`'s prefs
        // file for this Context yet, the same state a fresh install is in.
        context.getSharedPreferences(SharedPreferencesCaptureConfigurationStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()

        val facts = RealCaptureModeFacts(context)

        assert(facts.currentMode() == null) {
            "expected a fresh install's currentMode() to be null (not set), got ${facts.currentMode()}"
        }
    }
}
