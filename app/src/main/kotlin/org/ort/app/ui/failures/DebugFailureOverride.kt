package org.ort.app.ui.failures

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
 * wants rendered, and [FailureMapper.map] returns it outright — see that function's own kdoc.
 *
 * **Why this is safe in a shipped build even though it lives in `ui/failures/` (the main source
 * set, not `app/src/debug/`) rather than being compiled out of release entirely:** [current]
 * starts `null` and stays `null` unless something calls [show] — and the only caller anywhere in
 * this codebase is `Scenarios.kt`, which is itself only ever compiled into a debug build (AGP's
 * `app/src/debug` source set is never merged into a release variant). A release build therefore
 * carries this object but nothing in it ever calls [show], so [current] is always `null` and
 * [FailureMapper.map] always falls through to the real signals. A stronger guarantee — gating the
 * *read* itself on `BuildConfig.DEBUG` — needs `buildFeatures.buildConfig = true` added to
 * `app/build.gradle.kts` (it is not declared today, so `BuildConfig.DEBUG` does not compile);
 * that file is not in this package's row (spec/ui-conformance-plan.md §D), so this package does
 * not add it — flagged in this package's report rather than done silently.
 */
public object DebugFailureOverride {

    @Volatile
    public var current: FailurePresentation? = null
        private set

    /** The scenario simulator's own entry point (debug-sourceset-only caller — see class kdoc). */
    public fun show(presentation: FailurePresentation) {
        current = presentation
    }

    public fun clear() {
        current = null
    }
}
