package org.ort.app.ui.data

import android.content.Context
import org.ort.core.capture.CaptureMode
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.rig.CaptureConfiguration
import org.ort.pipeline.rig.CaptureConfigurationStore
import org.ort.pipeline.rig.SharedPreferencesCaptureConfigurationStore

/**
 * WPE (`spec/e2e-capture-modes-plan.md`, FR-CAP-12, FR-CAP-13, AC-131): the one seam CF02/CF11 read
 * the capture mode through. Backed for real by WPC2's [CaptureConfigurationStore] (merged
 * `e464820`) — every caller in this package depends on this interface, never on
 * [CaptureConfigurationStore] directly, so a future change to that store's own shape is a one-file
 * adaptation here, not a rewrite across every screen.
 */
public interface CaptureModeFacts {
    /** The mode in force right now — [CaptureConfigurationStore.current]'s own mode. Never
     * `null`: that store always has a real value, [CaptureConfiguration.DEFAULT] on a fresh
     * install (constitution I — a stated default, not an absent fact). */
    public fun currentMode(): CaptureMode

    /** Non-null exactly when a mode change was recorded while a session was live and has not yet
     * applied — [CaptureConfigurationStore.pendingConfiguration]'s own mode. Drives CF11's amber
     * "applies when it ends" banner. */
    public fun pendingMode(): CaptureMode?

    /** True exactly while a session is live — [org.ort.pipeline.capture.CaptureState.isCapturing]. */
    public fun isSessionLive(): Boolean
}

/** [realCaptureConfigurationStore]'s own `SharedPreferences` file, shared with `:pipeline`'s
 * `RealCaptureService` (WPC2) — `:app` opens its own instance over the same
 * [SharedPreferencesCaptureConfigurationStore.PREFS_NAME] file rather than routing through
 * `:pipeline` for a plain settings read/write, matching how `SettingsStore`/`SetupStore` are each
 * opened independently over their own preferences files. */
public fun realCaptureConfigurationStore(context: Context): CaptureConfigurationStore =
    SharedPreferencesCaptureConfigurationStore(
        context.applicationContext.getSharedPreferences(
            SharedPreferencesCaptureConfigurationStore.PREFS_NAME,
            Context.MODE_PRIVATE,
        ),
    )

/** The real [CaptureModeFacts], backed by [store] ([realCaptureConfigurationStore] by default). */
public class RealCaptureModeFacts(private val store: CaptureConfigurationStore) : CaptureModeFacts {
    public constructor(context: Context) : this(realCaptureConfigurationStore(context))

    override fun currentMode(): CaptureMode = store.current().mode
    override fun pendingMode(): CaptureMode? = store.pendingConfiguration()?.mode
    override fun isSessionLive(): Boolean = CaptureState.isCapturing
}

/** The behavioural fake (constitution II) — a plain, settable [CaptureModeFacts] for tests. */
public class FakeCaptureModeFacts(
    private var mode: CaptureMode = CaptureMode.LOCAL_MICROPHONE,
    private var pending: CaptureMode? = null,
    private var sessionLive: Boolean = false,
) : CaptureModeFacts {
    override fun currentMode(): CaptureMode = mode
    override fun pendingMode(): CaptureMode? = pending
    override fun isSessionLive(): Boolean = sessionLive

    public fun setMode(value: CaptureMode) {
        mode = value
    }

    public fun setPending(value: CaptureMode?) {
        pending = value
    }

    public fun setSessionLive(value: Boolean) {
        sessionLive = value
    }
}
