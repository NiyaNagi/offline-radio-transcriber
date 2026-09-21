package org.ort.app.ui.setup

import org.ort.app.BuildConfig

/**
 * R-1127/R-1107/R-085 (register): `Setup-Mic-Denied` (S02b) was doubly blocked from ever being
 * captured. `tools/ui-audit/install.ps1` grants `RECORD_AUDIO` unconditionally before every
 * scenario (so the real gate — `!recordAudioGranted && micPermanentlyDenied()` in
 * [SetupStateMachine.stepFor] — could never fire honestly), and even a debug-forced
 * `SetupActivity.EXTRA_STEP=MICROPHONE_DENIED` cold launch was stomped on the very next
 * `onResume`: [SetupActivity.shouldRefreshStepOnResume] unconditionally re-derives the live step
 * while `step == MICROPHONE_DENIED`, and that honest re-derivation used the *real* (granted)
 * permission state, computed a different `next`, and overwrote the forced step before
 * `org.ort.app.debug.tour.ScreenshotTourActivity`'s own settle-poll ever observed it.
 *
 * Fixed at the root rather than by special-casing the resume path, matching this package's own
 * established shape ([DebugRouteCheckOverride], [DebugRigLinkPortOverride],
 * [DebugModelsSetupOverride]): [SetupActivity.currentPermissionsState] and
 * [SetupActivity.micPermanentlyDenied] both consult [active] first, so a debug scenario can make
 * the *real* permission-state function report a genuine, permanent denial regardless of whatever
 * `install.ps1` actually granted at the OS level. Once that is true, [SetupStateMachine.stepFor]'s
 * own honest re-derivation *agrees* with `MICROPHONE_DENIED` on every call — `tryOpenAtRequestedStep`,
 * the ordinary `refreshStep`, and `onResume`'s own re-check all compute the identical step, so
 * nothing stomps anything and no bypass logic was added to `shouldRefreshStepOnResume` at all.
 * `install.ps1`'s unconditional grant needed no change either — this seam makes it irrelevant for
 * this one scenario, the same way [DebugRouteCheckOverride] makes a live `RealRouteCheck` run
 * irrelevant for S05/S06 on hardware the tour's AVD does not have.
 *
 * **Read gated on `BuildConfig.DEBUG`**, identically to the overrides above: a release build must
 * never consult this object even in the (already impossible, per [show]'s own doc) case that
 * something in it had a value.
 */
public object DebugMicPermissionOverride {

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
    public val active: Boolean
        get() = isDebugBuild() && current
}
