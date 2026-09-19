package org.ort.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * P23 (FR-AST-14, D44): the committed `bundled-assets.json` is the one place the `models-v1`
 * mirror URL every entry needs is pinned — [ModelMirrorPublisher] and the `play` variant's
 * generated catalog ([BundledAssetCatalogRenderer]) both read it from here, never invent it.
 * AC-191's "a manifest entry names its URL, sha256 and size" half is a fact about this committed
 * file, checked directly rather than only through a hand-built fixture.
 */
class BundledAssetManifestMirrorFieldsTest {

    private val committedEntries: List<BundledAssetManifest.Entry> by lazy {
        val root = generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { File(it, "bundled-assets.json").isFile }
        BundledAssetManifest.parse(File(root, "bundled-assets.json").readText())
    }

    @Test
    fun `AC_191 every committed entry pins a non-blank mirrorUrl distinct from its build-time url`() {
        assertTrue(committedEntries.isNotEmpty(), "the committed manifest must not be empty")
        committedEntries.forEach { entry ->
            assertTrue(entry.mirrorUrl.isNotBlank(), "${entry.id} has no mirrorUrl")
            assertTrue(
                entry.mirrorUrl.startsWith("https://github.com/"),
                "${entry.id}'s mirrorUrl must be a GitHub Release asset URL, was ${entry.mirrorUrl}",
            )
            assertTrue(
                entry.mirrorUrl.contains("/releases/download/models-v1/"),
                "${entry.id}'s mirrorUrl must be pinned to the models-v1 tag (D44), was ${entry.mirrorUrl}",
            )
            assertTrue(entry.mirrorUrl != entry.url, "${entry.id}'s mirrorUrl must differ from its build-time url")
        }
    }

    @Test
    fun `AC_191 every committed entry's mirror asset name matches the cached file the fetch task would produce`() {
        // ModelMirrorPublisher looks up the cached file FetchBundledAssetsTask already downloaded
        // by the build-time url's own basename; the mirror asset must be published under that same
        // basename or PublishModelMirrorTask can never find what to upload.
        committedEntries.forEach { entry ->
            val urlBasename = entry.url.substringAfterLast('/').substringBefore('?')
            assertEquals(
                urlBasename,
                ModelMirrorPublisher.mirrorAssetName(entry),
                "${entry.id}: mirrorUrl's basename must match the build-time url's basename",
            )
        }
    }

    @Test
    fun `AC_191 every committed entry still carries a real sha256 and a positive size`() {
        committedEntries.forEach { entry ->
            assertTrue(entry.sizeBytes > 0, "${entry.id} has a non-positive sizeBytes")
            val isTrustOnFirstFetch = entry.sha256 == BundledAssetManifest.TRUST_ON_FIRST_FETCH
            assertTrue(
                isTrustOnFirstFetch || entry.sha256.matches(Regex("[0-9a-f]{64}")),
                "${entry.id}'s sha256 is neither the trust-on-first-fetch sentinel nor a plausible hex digest",
            )
        }
    }
}
