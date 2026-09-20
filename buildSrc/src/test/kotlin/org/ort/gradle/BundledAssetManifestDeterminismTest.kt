package org.ort.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * P23: constitution's "generated files MUST be byte-identical wherever they are generated" applies
 * to a hand-authored manifest too, once more than one builder can extend it — `bundled-assets.json`
 * stays byte-sorted by `id` so a future addition has one obviously-correct place to insert it and a
 * diff never depends on which order two concurrent edits happened to land in.
 */
class BundledAssetManifestDeterminismTest {

    private fun committedManifestText(): String {
        val root = generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { File(it, "bundled-assets.json").isFile }
        return File(root, "bundled-assets.json").readText()
    }

    @Test
    fun `the committed manifest's assets array is sorted by id`() {
        val entries = BundledAssetManifest.parse(committedManifestText())
        val ids = entries.map { it.id }
        assertEquals(ids.sorted(), ids, "bundled-assets.json's \"assets\" array must list entries in id-sorted order")
    }

    @Test
    fun `re-rendering a resolved manifest twice from the same entries is byte-identical`() {
        val entries = BundledAssetManifest.parse(committedManifestText())
        val resolved = entries.map { BundledAssetManifest.ResolvedEntry(it, sha256 = it.sha256, missing = false) }

        val first = BundledAssetManifest.renderResolved(resolved)
        val second = BundledAssetManifest.renderResolved(resolved)

        assertEquals(first, second, "rendering the identical resolved entries twice must produce byte-identical text")
    }
}
