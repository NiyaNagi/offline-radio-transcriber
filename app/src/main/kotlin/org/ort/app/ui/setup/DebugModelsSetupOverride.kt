package org.ort.app.ui.setup

import org.ort.app.BuildConfig

/**
 * This round's own file-ownership finding, reported rather than worked around: the setup
 * `MODELS` step ([SetupActivity.RenderModels]) reads its rows from
 * [org.ort.app.ui.data.ModelsController.currentState]`.rowsForSetupModelsStep()`, which only ever
 * returns a row when its catalog entry is genuinely `!bundled` — but
 * [org.ort.app.ui.data.ModelCatalog.entries] hardcodes every entry's
 * [org.ort.app.ui.data.ModelCatalogEntry.bundled] to its default, `true` ("every entry
 * [org.ort.app.ui.data.ModelCatalog] generates today is bundled", that file's own kdoc, verbatim),
 * and never reads [org.ort.app.assets.GeneratedBundledAssetManifest]'s own per-flavor `bundled`
 * field (`false` for `play`, per `BundledAssetCatalogRenderer`) to override it. So on *any* build
 * variant available to this session — including a real `play` debug build — the real production
 * path can never show a row here today: `rowsForSetupModelsStep()` is unconditionally empty.
 *
 * `app/src/main/kotlin/org/ort/app/ui/data/ModelsViewData.kt` is a different unit's file (WPG's),
 * outside what this round's prompt names as ownable here, so the real fix — plumbing
 * [org.ort.app.assets.GeneratedBundledAssetManifest.Entry.bundled] into
 * [org.ort.app.ui.data.ModelCatalogEntry.bundled] — is not made in this change. This object is the
 * same kind of seam [DebugRouteCheckOverride]/[DebugRigLinkPortOverride] already establish for a
 * screen this package cannot otherwise drive to a state worth capturing: a debug scenario
 * (`app/src/debug/kotlin/org/ort/app/debug/Scenarios.kt`, outside this package's ownership) calls
 * [show] with the exact rows it wants the MODELS step to render — a real, honest shape
 * (`ModelDownloadRowStatus.PENDING`/`FAILED`/`DOWNLOADING`), just not sourced through the
 * currently-broken `bundled` plumbing — so the screen and its artboard are reachable by a capture
 * (constitution VIII) despite that gap. [SetupActivity.initialModelsSetupRows]/
 * [SetupActivity.refreshedModelsSetupRows] read [activeOverride] ahead of ever calling
 * [org.ort.app.ui.data.ModelsController.currentState], the same "read the override first" order
 * [SetupActivity.onCreate] already uses for [DebugRouteCheckOverride]/[DebugRigLinkPortOverride].
 *
 * **Read gated on `BuildConfig.DEBUG`**, identically to [DebugRouteCheckOverride]: a release build
 * must never consult this object even in the (already impossible, per [show]'s own doc) case that
 * something in it had a value. [isDebugBuild] is a settable function reference, not the bare
 * constant, for the identical reason [DebugRouteCheckOverride.isDebugBuild] is one: Robolectric
 * only ever compiles this module's **debug** variant, so `BuildConfig.DEBUG` is `true` in every
 * unit test regardless of what this class does.
 */
public object DebugModelsSetupOverride {

    @Volatile
    public var current: List<ModelDownloadRowViewState>? = null
        private set

    /** Test seam (see class kdoc) — production code never assigns this. */
    @Volatile
    internal var isDebugBuild: () -> Boolean = { BuildConfig.DEBUG }

    /** The scenario simulator's own entry point (debug-sourceset-only caller — see class kdoc). */
    public fun show(rows: List<ModelDownloadRowViewState>) {
        current = rows
    }

    public fun clear() {
        current = null
    }

    /** [SetupActivity.RenderModels]'s own read — the gated one. `null` in any non-debug build, no
     * matter what [current] holds. */
    public val activeOverride: List<ModelDownloadRowViewState>?
        get() = if (isDebugBuild()) current else null
}
