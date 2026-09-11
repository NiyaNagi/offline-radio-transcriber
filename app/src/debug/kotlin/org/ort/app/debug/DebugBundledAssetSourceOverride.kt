package org.ort.app.debug

import org.ort.app.assets.BundledAssetSource

/**
 * R-807 (register, coordinator round): a settable override for the real
 * [org.ort.app.assets.AndroidBundledAssetSource] [Scenarios.installRealBundledAssets] otherwise
 * always builds itself — the one seam `ScenariosTest`'s own R_110 stress-repeat test (loads every
 * scenario five times back to back) needs so it can substitute a fixture-sized source (a handful
 * of bytes per entry, not the real ~700MB bundle a genuine `HF_TOKEN` build now carries) for its
 * own loop, without touching any other test's real-asset-installation behavior or this package's
 * own production install path.
 *
 * `null` (the default) always means "use the real source" — every scenario load outside that one
 * test, and every real device/emulator run, is completely unaffected.
 *
 * **Deliberately NOT cleared by [Scenarios.resetProcessWideFacets]**, unlike every other
 * `Debug*Override` object in this codebase — this one must survive across a whole *sequence* of
 * [Scenarios.load] calls within one test (the stress-repeat loop it exists for), not be scoped to
 * a single scenario's own render. `resetProcessWideFacets` runs at the top of every single [Scenarios.load]
 * call, before `installRealBundledAssets` ever reads this override — clearing it there would
 * silently defeat it on the very first iteration of the loop it was set for (reproduced directly
 * before this comment was written). The one test that sets [override] is responsible for its own
 * `try`/`finally` clear, backstopped by that test class's own `@After`.
 */
internal object DebugBundledAssetSourceOverride {
    internal var override: BundledAssetSource? = null

    internal fun clear() {
        override = null
    }
}
