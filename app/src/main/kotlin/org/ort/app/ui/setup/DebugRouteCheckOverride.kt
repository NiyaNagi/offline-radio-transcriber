package org.ort.app.ui.setup

import org.ort.app.BuildConfig

/**
 * R-943 (register, reviewer A4 run 5, halt): the screenshot tour opens S05 (`Setup-Verify.dc.html`)
 * by a cold [SetupActivity.EXTRA_STEP] launch straight at [SetupStep.VERIFY] — no S04 selection
 * ever ran, so [SetupActivity.selectedDescriptor] is `null` and [SetupActivity.RenderVerify] never
 * even starts [RealRouteCheck.run]'s listen loop. Every fact this round's S05 elements need
 * (the routed-device line, the native-rate line, the elapsed counter, the Input waveform card,
 * the noise-floor text) comes only from a live [RouteCheckState] the real check would have to
 * genuinely run and finish to produce — hardware the tour's AVD does not have and 30s the tour
 * cannot spend per step. This is the same seam [DebugRigLinkPortOverride] already establishes for
 * S10b's own hardware-shaped gap, and [org.ort.app.ui.failures.DebugFailureOverride] for its own
 * package: a debug scenario (`app/src/debug/kotlin/org/ort/app/debug/Scenarios.kt`, outside this
 * package's ownership) calls [show] with whichever [RouteCheckState] snapshot it wants S05 to
 * render (typically a [RouteCheckState.InProgress] "still listening" snapshot carrying a real
 * device label, native rate, elapsed seconds, level bars and noise floor — [RouteCheckState]'s own
 * doc comment names exactly these fields); [SetupActivity.RenderVerify] reads [activeOverride]
 * ahead of ever launching [RealRouteCheck], the same "read the override first" order
 * [SetupActivity.onCreate] already uses for [DebugRigLinkPortOverride].
 *
 * **Read gated on `BuildConfig.DEBUG`**, identically to [DebugRigLinkPortOverride]: a release build
 * must never consult this object even in the (already impossible, per [show]'s own doc) case that
 * something in it had a value — [activeOverride] reads `null` outright whenever [isDebugBuild] is
 * false, never touching [current]. [isDebugBuild] is a settable function reference, not the bare
 * constant, for the identical reason [DebugRigLinkPortOverride.isDebugBuild] is one: Robolectric
 * only ever compiles this module's **debug** variant, so `BuildConfig.DEBUG` is `true` in every
 * unit test regardless of what this class does — [DebugRouteCheckOverrideTest] restores the real
 * default in every case, including on failure.
 */
public object DebugRouteCheckOverride {

    @Volatile
    public var current: RouteCheckState? = null
        private set

    /** Test seam (see class kdoc) — production code never assigns this. */
    @Volatile
    internal var isDebugBuild: () -> Boolean = { BuildConfig.DEBUG }

    /** The scenario simulator's own entry point (debug-sourceset-only caller — see class kdoc). */
    public fun show(state: RouteCheckState) {
        current = state
    }

    public fun clear() {
        current = null
    }

    /** [SetupActivity.RenderVerify]'s own read — the gated one. `null` in any non-debug build, no
     * matter what [current] holds. */
    public val activeOverride: RouteCheckState?
        get() = if (isDebugBuild()) current else null
}
