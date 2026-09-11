package org.ort.app.ui.setup

import org.ort.app.BuildConfig

/**
 * The tour cannot reach S10b's paired-device states over [BridgeRigLinkPort]: the real
 * [org.ort.pipeline.rig.DefaultRigLinkBridge] talks to actual Bluetooth hardware, which the AVDs
 * the tour and this app's own screenshot-tour run against do not have (`results/ui-audit/README.md`'s
 * own "the emulator has no Bluetooth" note, `spec/e2e-capture-modes-plan.md`'s "Concurrency and
 * safety" section). This object is the same seam [org.ort.app.ui.failures.DebugFailureOverride]
 * and `ui/data`'s `DebugLexiconImportOverride` already establish for their own packages: a debug
 * scenario (`app/src/debug/kotlin/org/ort/app/debug/Scenarios.kt`, outside this package's
 * ownership — the tour package wires its own scripted [RigLinkPort] to [show]) sets [current] to a
 * fake that can present whichever paired-device list and connect sequence the scenario wants
 * reachable; [SetupActivity.onCreate] reads [activeOverride] once, ahead of constructing the real
 * [BridgeRigLinkPort].
 *
 * **Read gated on `BuildConfig.DEBUG`**, identically to [org.ort.app.ui.failures.DebugFailureOverride]:
 * a release build must never consult this object even in the (already impossible, per [show]'s own
 * doc) case that something in it had a value — [activeOverride] reads `null` outright whenever
 * [isDebugBuild] is false, never touching [current]. [isDebugBuild] is a settable function
 * reference, not the bare constant, because Robolectric only ever compiles this module's **debug**
 * variant (`ort.android-app.gradle.kts`'s `testBuildType = "debug"`) — `BuildConfig.DEBUG` is
 * `true` in every unit test regardless of what this class does, so proving "ignored when not
 * debug" needs a seam a test can flip; [DebugRigLinkPortOverrideTest] restores the real default in
 * every case, including on failure.
 */
public object DebugRigLinkPortOverride {

    @Volatile
    public var current: RigLinkPort? = null
        private set

    /** Test seam (see class kdoc) — production code never assigns this. */
    @Volatile
    internal var isDebugBuild: () -> Boolean = { BuildConfig.DEBUG }

    /** The scenario simulator's own entry point (debug-sourceset-only caller — see class kdoc). */
    public fun show(port: RigLinkPort) {
        current = port
    }

    public fun clear() {
        current = null
    }

    /** [SetupActivity.onCreate]'s own read — the gated one. `null` in any non-debug build, no
     * matter what [current] holds. */
    public val activeOverride: RigLinkPort?
        get() = if (isDebugBuild()) current else null
}
