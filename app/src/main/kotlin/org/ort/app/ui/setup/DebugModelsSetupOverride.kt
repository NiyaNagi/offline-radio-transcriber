package org.ort.app.ui.setup

import org.ort.app.BuildConfig

/**
 * P28a found the real production gap this object stood in for: the setup `MODELS` step
 * ([SetupActivity.RenderModels]) reads its rows from
 * [org.ort.app.ui.data.ModelsController.currentState]`.rowsForSetupModelsStep()`, which only ever
 * returns a row when its catalog entry is genuinely `!bundled` — but
 * [org.ort.app.ui.data.ModelCatalog.entries] hardcoded every entry's
 * [org.ort.app.ui.data.ModelCatalogEntry.bundled] to its default, `true`, rather than reading
 * [org.ort.app.assets.GeneratedBundledAssetManifest]'s own per-flavor `bundled` field (`false` for
 * `play`, per `BundledAssetCatalogRenderer`). **P28b (D43/D44, FR-AST-10..14) fixed that gap** in
 * `ModelsViewData.kt` (`ModelCatalog.mapEntries` now reads `bundled`/`downloadUrl` from the
 * generated manifest) — the real production path now shows a row here on a genuine `play` build.
 *
 * This object stays, deliberately, rather than being removed: the debug scenario tooling
 * (`app/src/debug/kotlin/org/ort/app/debug/Scenarios.kt`) and the canonical screenshot tour both
 * run against the `full` variant, where every catalog entry is genuinely `bundled = true` and
 * `rowsForSetupModelsStep()` is therefore always empty — there is still no way to drive the real
 * production path to a non-empty, `PENDING`/`FAILED`/`DOWNLOADING` MODELS-step capture without a
 * real `play` build and a real network mirror. This remains the same kind of seam
 * [DebugRouteCheckOverride]/[DebugRigLinkPortOverride] already establish for exactly that reason:
 * a debug scenario calls [show] with the exact rows it wants the MODELS step to render — a real,
 * honest shape, just not reachable through `full`'s own bundled-everything catalog — so the screen
 * and its artboard stay reachable by a capture (constitution VIII).
 * [SetupActivity.initialModelsSetupRows]/[SetupActivity.refreshedModelsSetupRows] read
 * [activeOverride] ahead of ever calling [org.ort.app.ui.data.ModelsController.currentState], the
 * same "read the override first" order [SetupActivity.onCreate] already uses for
 * [DebugRouteCheckOverride]/[DebugRigLinkPortOverride].
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
