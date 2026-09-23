package org.ort.app.ui.setup

import android.content.Context
import org.ort.capture.android.CaptureGain

/**
 * The one place the operator's stored input gain (R-1168) becomes the live, process-wide value
 * `org.ort.capture.android.AndroidAudioIo.read` actually applies.
 *
 * It exists because the preference and its consumer are in different modules on purpose:
 * `:capture-android` must never depend on `:app`, so the value has to be *pushed* in. And it runs
 * at process start rather than only when Setup is open, because the path that matters most never
 * opens Setup at all — `RealCaptureService` builds its own `AndroidAudioIo` when the operator taps
 * record, and on a cold start after a reboot nothing else would ever have told
 * [CaptureGain] what the operator chose. Register R-1171 is the standing warning this answers
 * directly: three switches on the Settings capture screen are persisted, rendered and read by
 * nobody, and a gain slider with no consumer would have been the fourth.
 *
 * Deliberately trivial and synchronous: a preference read and an assignment to a `@Volatile` field,
 * nothing capture could ever wait on (constitution IV).
 */
public object CaptureGainWiring {

    /** Pushes [SetupStore.captureGainDb] into [CaptureGain]; 0 dB (unaltered audio) when unset. */
    public fun applyStoredGain(context: Context) {
        val prefs = context.getSharedPreferences(
            SharedPreferencesSetupStore.PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        applyStoredGain(SharedPreferencesSetupStore(prefs))
    }

    /** The same, against any [SetupStore] — the seam the behavioural fake is driven through. */
    public fun applyStoredGain(store: SetupStore) {
        CaptureGain.setGainDb(store.captureGainDb ?: CaptureGain.MIN_GAIN_DB)
    }
}
