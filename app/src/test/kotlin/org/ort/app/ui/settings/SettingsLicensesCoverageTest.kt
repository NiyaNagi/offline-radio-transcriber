package org.ort.app.ui.settings

import org.junit.Test
import org.ort.app.assets.GeneratedBundledAssetManifest
import org.ort.testing.Requirement

/**
 * P27 (NFR-6d): "a new model can't ship without one" — walks the real, generated
 * [GeneratedBundledAssetManifest] (built at compile time from the repo root's `bundled-assets.json`,
 * `ort.android-app.gradle.kts`'s `generateBundledAssetCatalog` task) and fails the moment a bundled
 * asset id has no matching [BundledLicenceNotices.BUNDLED_ASSET_NOTICES] entry — a future model
 * added to `bundled-assets.json` with no corresponding notice fails this test, not a review.
 */
class SettingsLicensesCoverageTest {

    @Test
    @Requirement("NFR-6d")
    fun `NFR_6d every bundled asset in the manifest has a licence notice entry`() {
        val manifestIds = GeneratedBundledAssetManifest.entries.map { it.id }
        assert(manifestIds.isNotEmpty()) { "expected the generated bundled-asset manifest to be non-empty" }

        val missing = manifestIds.filterNot { it in BundledLicenceNotices.BUNDLED_ASSET_NOTICES }
        assert(missing.isEmpty()) {
            "expected every bundled-assets.json id to have a licence notice entry in " +
                "BundledLicenceNotices.BUNDLED_ASSET_NOTICES — missing: $missing"
        }
    }

    @Test
    @Requirement("NFR-6d")
    fun `NFR_6d every notice a manifest entry maps to is one the screen actually shows`() {
        val shown = BundledLicenceNotices.ALL.toSet()
        val mapped = BundledLicenceNotices.BUNDLED_ASSET_NOTICES.values.toSet()
        assert(shown.containsAll(mapped)) {
            "expected every mapped notice to be one BundledLicenceNotices.ALL renders — " +
                "mapped but not shown: ${mapped - shown}"
        }
    }
}
