package org.ort.app.ui.failures

import org.ort.app.BuildConfig

/**
 * WP11b's own instruction: six of this package's seventeen ids have no real signal to trigger
 * them from anywhere in the built app today —
 *
 * - **F14 clock** — no session-detail screen exists yet that could host `Fail-Clock.dc.html`'s
 *   DST card (WP5/WP8 territory), and nothing computes "the clock went back during this session"
 *   at all; that arithmetic belongs beside wherever a session's start/end is first rendered.
 * - **F16 USB permission** — the rig module (FR-RIG) is unbuilt (register R-084); there is no
 *   `UsbManager`/`PendingIntent` permission-request call anywhere in `:capture-android` to key
 *   off, and [org.ort.pipeline.capture.RigStatus] has no "permission needed" state (only
 *   `Absent`/`Connected`/`Stale` — none of which distinguish "cable reattached, Android asked
 *   again" from a plain stale rig).
 * - **F17 interrupted pass** — `RealCaptureService` re-queues in-flight overs on relaunch (see its
 *   own kdoc), but nothing republishes "N overs were mid-transcription when the app stopped" to a
 *   holder; it would need a small counter beside the existing re-queue path.
 * - **F19 reconcile** — no code walks `:data`'s records against the audio directory looking for
 *   orphans on either side; this needs a new pass, not a new holder.
 * - **F20 migration** — no migration has ever failed a step in this codebase (there is exactly one
 *   schema version); the board describes a *response* to a migration failure that has not
 *   happened yet.
 * - **F21/F22 asset swap and calibration** — the asset lifecycle (install/verify/activate/roll
 *   back/remove, guide §"Assets") and a calibration-refit pipeline are both unbuilt.
 *
 * None of the above is a `:capture-*`/`:pipeline`/`:data` file this package owns (see this
 * package's report for exactly which follow-up each needs), so [FailureHost] cannot show any of
 * these six from a real signal. This object is how a debug scenario (`app/src/debug/kotlin/org/
 * ort/app/debug/Scenarios.kt`, this package's own addition) still makes every one of them
 * reachable for a validator: it sets [current] to the exact [FailurePresentation] the scenario
 * wants rendered, and [activeOverride] — [FailureMapper.map]'s own read, via
 * [FailureSignalsPolling] — returns it outright when this is a debug build; see that function's
 * own kdoc.
 *
 * **Read gated on `BuildConfig.DEBUG`** (WP11b follow-up — `app/build.gradle.kts` now declares
 * `buildFeatures.buildConfig = true` so this compiles): a release build must never consult this
 * object even in the (already impossible, per [show]'s own doc) case that something in it had a
 * value — [activeOverride] reads `null` outright whenever [isDebugBuild] is false, never touching
 * [current]. [isDebugBuild] is a settable function reference, not the bare constant, because
 * Robolectric only ever compiles this module's **debug** variant (`ort.android-app.gradle.kts`'s
 * `testBuildType = "debug"`) — `BuildConfig.DEBUG` is `true` in every unit test regardless of what
 * this class does, so proving "ignored when not debug" needs a seam a test can flip;
 * `DebugFailureOverrideTest` restores the real default in every case, including on failure.
 */
public object DebugFailureOverride {

    @Volatile
    public var current: FailurePresentation? = null
        private set

    /** Test seam (see class kdoc) — production code never assigns this. */
    @Volatile
    internal var isDebugBuild: () -> Boolean = { BuildConfig.DEBUG }

    /** The scenario simulator's own entry point (debug-sourceset-only caller — see class kdoc). */
    public fun show(presentation: FailurePresentation) {
        current = presentation
    }

    public fun clear() {
        current = null
    }

    /** [FailureSignalsPolling]'s own read — the gated one. `null` in any non-debug build, no
     * matter what [current] holds. */
    public val activeOverride: FailurePresentation?
        get() = if (isDebugBuild()) current else null
}
