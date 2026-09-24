package org.ort.app.ui.setup

import org.ort.app.BuildConfig

/**
 * **R-1179 (register): the Bluetooth-permission step has never once captured the Bluetooth-permission
 * screen.**
 *
 * Every committed `setup-bt-permission/S02c` PNG, at every font scale, is the Input screen — and two
 * register rows were closed on that evidence. The cause is a composition of two correct changes, the
 * same shape as R-1161: `tools/ui-audit/install.ps1` grants `BLUETOOTH_CONNECT` before every scenario,
 * which the four rig-Bluetooth rows genuinely need, and
 * [SetupStateMachine] gates `BLUETOOTH_PERMISSION` on `!permissions.bluetoothConnectGranted` — so with
 * the grant in place that gate can never fire, and the step the tour asks for by `EXTRA_STEP` is
 * replaced by the natural next one on the first `onResume`.
 *
 * **Fixed at the root rather than by taking the grant away**, which would trade this screen for the
 * four rows it was added for. This is the identical seam [DebugMicPermissionOverride] already provides
 * for S02b's own instance of exactly this problem: [SetupActivity.currentPermissionsState] consults
 * [denied] first, so the *real* permission-state function reports an honest absence regardless of what
 * `install.ps1` granted at the OS level. Once that is true, [SetupStateMachine.stepFor] agrees with
 * `BLUETOOTH_PERMISSION` on every call — `tryOpenAtRequestedStep`, `refreshStep` and `onResume` all
 * compute the same step — so nothing stomps anything and no bypass logic exists anywhere.
 *
 * The general guard that would have *caught* this on the first run is separate and also landed with
 * P39: `ScreenshotTourActivity.assertStillOnRequestedStep`, which re-checks the step immediately
 * before `drawToBitmap` rather than only at the settle.
 *
 * **Read gated on `BuildConfig.DEBUG`**, identically to the overrides beside it: a release build never
 * consults this object even in the (already impossible) case that something in it had a value.
 */
public object DebugBluetoothPermissionOverride {

    @Volatile
    public var current: Boolean = false
        private set

    /** Test seam (see class kdoc) — production code never assigns this. */
    @Volatile
    internal var isDebugBuild: () -> Boolean = { BuildConfig.DEBUG }

    /** The scenario simulator's own entry point (debug-sourceset-only caller — see class kdoc). */
    public fun show() {
        current = true
    }

    public fun clear() {
        current = false
    }

    /** [SetupActivity]'s own read — the gated one. `false` in any non-debug build, no matter what
     * [current] holds. */
    public val denied: Boolean
        get() = isDebugBuild() && current
}
