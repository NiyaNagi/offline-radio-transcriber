package org.ort.app.ui.data

import android.content.Context
import org.ort.core.capture.CaptureMode
import org.ort.pipeline.capture.CaptureState
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
    /**
     * The mode in force right now — `null` when the operator has never chosen one at all (register
     * R-821, halt): [CaptureConfigurationStore.current] itself cannot say this — its own contract
     * (confirmed by reading `CaptureConfigurationStore.kt`'s `SharedPreferencesCaptureConfigurationStore
     * .current`) collapses "never written" and "written, LOCAL_MICROPHONE" to the identical
     * [org.ort.pipeline.rig.CaptureConfiguration.DEFAULT] before this interface ever sees it — CF02/
     * CF11 rendering that collapsed default as a real fact ("Built-in microphone") on a fresh
     * install, before setup has ever run, was exactly the fabricated fact R-821 named: nothing was
     * chosen, so nothing is reported as chosen (constitution I). [RealCaptureModeFacts] restores the
     * distinction the store's own contract does not carry, by reading underneath it.
     */
    public fun currentMode(): CaptureMode?

    /** Non-null exactly when a mode change was recorded while a session was live and has not yet
     * applied — [CaptureConfigurationStore.pendingConfiguration]'s own mode. Drives CF11's amber
     * "applies when it ends" banner. A pending write always follows a real [update], never this
     * seam's own "never configured" ambiguity — no equivalent nullability question here. */
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

/**
 * Register R-821 (halt): the raw key [SharedPreferencesCaptureConfigurationStore.current] itself
 * writes/reads a mode under (`"current" + "." + "mode"`, that class's own private `PREFIX_CURRENT`/
 * `KEY_MODE` — not importable here, `:pipeline`'s own private constants; mirrored as a literal,
 * confirmed against that file's source before writing this, rather than asking WPC2 to expose a
 * new public accessor this round). Present on the exact same `SharedPreferences` file
 * [realCaptureConfigurationStore] itself opens — this reads the raw entry underneath the store's
 * own `current()`, which cannot itself distinguish "never written" from "written, happens to be
 * LOCAL_MICROPHONE" (both collapse to [org.ort.pipeline.rig.CaptureConfiguration.DEFAULT]).
 *
 * **Coupling risk, reported**: this literal breaks silently (reads as "always not set") if WPC2
 * ever renames its own private prefix/key. The robust long-term fix is a real
 * `CaptureConfigurationStore.hasBeenConfigured(): Boolean` — flagged in this round's own report/
 * CHANGELOG for the lead, not implemented here (`:pipeline` is outside this package's ownership).
 */
private const val RAW_KEY_CURRENT_MODE = "current.mode"

/** The real [CaptureModeFacts], backed by [store] ([realCaptureConfigurationStore] by default).
 * [hasBeenConfigured] defaults to a real raw-`SharedPreferences` read over the same file [store]
 * itself is expected to be opened over when the [Context] constructor is used — see
 * [RAW_KEY_CURRENT_MODE]'s own doc comment for why this exists at all (R-821). */
public class RealCaptureModeFacts(
    private val store: CaptureConfigurationStore,
    private val hasBeenConfigured: () -> Boolean = { false },
) : CaptureModeFacts {
    public constructor(context: Context) : this(
        store = realCaptureConfigurationStore(context),
        hasBeenConfigured = {
            context.applicationContext
                .getSharedPreferences(SharedPreferencesCaptureConfigurationStore.PREFS_NAME, Context.MODE_PRIVATE)
                .contains(RAW_KEY_CURRENT_MODE)
        },
    )

    override fun currentMode(): CaptureMode? = if (hasBeenConfigured()) store.current().mode else null
    override fun pendingMode(): CaptureMode? = store.pendingConfiguration()?.mode
    override fun isSessionLive(): Boolean = CaptureState.isCapturing
}

/** The behavioural fake (constitution II) — a plain, settable [CaptureModeFacts] for tests.
 * [mode] defaults `null` (R-821: "not set" is the honest default, the same reasoning
 * [RealCaptureModeFacts] now rests on) — a test that needs a specific current mode calls
 * [setMode] explicitly, the same way it already must for [setPending]/[setSessionLive]. */
public class FakeCaptureModeFacts(
    private var mode: CaptureMode? = null,
    private var pending: CaptureMode? = null,
    private var sessionLive: Boolean = false,
) : CaptureModeFacts {
    override fun currentMode(): CaptureMode? = mode
    override fun pendingMode(): CaptureMode? = pending
    override fun isSessionLive(): Boolean = sessionLive

    public fun setMode(value: CaptureMode?) {
        mode = value
    }

    public fun setPending(value: CaptureMode?) {
        pending = value
    }

    public fun setSessionLive(value: Boolean) {
        sessionLive = value
    }
}
