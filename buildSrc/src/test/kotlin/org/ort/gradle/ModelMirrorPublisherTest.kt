package org.ort.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * D44 (FR-AST-14): [ModelMirrorPublisher] is the pure decision logic behind `publishModelMirror`
 * — see that task's own KDoc for why the real `gh` invocation is kept out of this suite entirely
 * (untestable without a real GitHub Release; every *decision* it makes is testable and tested
 * here).
 */
class ModelMirrorPublisherTest {

    private fun entry(
        id: String = "A",
        sha256: String = "a".repeat(64),
        sizeBytes: Long = 100L,
        mirrorUrl: String = "https://github.com/NiyaNagi/offline-radio-transcriber/releases/download/models-v1/a.bin",
    ) = BundledAssetManifest.Entry(
        id = id,
        url = "https://example.invalid/a.bin",
        sha256 = sha256,
        sizeBytes = sizeBytes,
        destination = "models/a.bin",
        tiers = listOf("T0"),
        licence = "MIT",
        gated = false,
        mirrorUrl = mirrorUrl,
    )

    private fun mirrorUrlFor(name: String) =
        "https://github.com/NiyaNagi/offline-radio-transcriber/releases/download/models-v1/$name"

    private fun seedCache(cacheRoot: File, entry: BundledAssetManifest.Entry, bytes: ByteArray): File {
        val cacheKey = entry.sha256.takeIf { it != BundledAssetManifest.TRUST_ON_FIRST_FETCH } ?: entry.id
        val dir = File(cacheRoot, cacheKey).apply { mkdirs() }
        val name = ModelMirrorPublisher.mirrorAssetName(entry)
        return File(dir, name).apply { writeBytes(bytes) }
    }

    // ---- mirrorAssetName -------------------------------------------------------------------

    @Test
    fun `mirrorAssetName is the basename of mirrorUrl, stripped of any query string`() {
        val e = entry(mirrorUrl = "https://github.com/o/r/releases/download/models-v1/tiny.en-tokens.txt?x=1")
        assertEquals("tiny.en-tokens.txt", ModelMirrorPublisher.mirrorAssetName(e))
    }

    // ---- plan: skip / upload / missing -------------------------------------------------------

    @Test
    fun `AC discrimination — an entry already present at the mirror with a matching size is skipped, never uploaded`(
        @TempDir dir: File,
    ) {
        val e = entry(id = "GOOD")
        seedCache(dir, e, ByteArray(100))

        val plan = ModelMirrorPublisher.plan(
            entries = listOf(e),
            cacheRoot = dir,
            existingAssets = mapOf(ModelMirrorPublisher.mirrorAssetName(e) to 100L),
        )

        assertEquals(listOf(e), plan.skipped)
        assertTrue(plan.toUpload.isEmpty(), "an entry with a matching remote size must never be re-uploaded")
        assertTrue(plan.missingLocally.isEmpty())
    }

    @Test
    fun `AC discrimination — a size mismatch against the remote asset is uploaded again, not skipped`(
        @TempDir dir: File,
    ) {
        val e = entry(id = "STALE", sizeBytes = 200L)
        val cached = seedCache(dir, e, ByteArray(200))

        val plan = ModelMirrorPublisher.plan(
            entries = listOf(e),
            cacheRoot = dir,
            // The remote asset exists under the same name but a different (stale) size.
            existingAssets = mapOf(ModelMirrorPublisher.mirrorAssetName(e) to 199L),
        )

        assertTrue(plan.skipped.isEmpty(), "a size mismatch must never be treated as already present")
        assertEquals(1, plan.toUpload.size)
        assertEquals(cached, plan.toUpload.single().file)
    }

    @Test
    fun `AC discrimination — an entry absent from the remote release is uploaded from its cached file`(
        @TempDir dir: File,
    ) {
        val e = entry(id = "NEW")
        val cached = seedCache(dir, e, ByteArray(100))

        val plan = ModelMirrorPublisher.plan(entries = listOf(e), cacheRoot = dir, existingAssets = emptyMap())

        assertEquals(1, plan.toUpload.size)
        assertEquals(e, plan.toUpload.single().entry)
        assertEquals(cached, plan.toUpload.single().file)
    }

    @Test
    fun `AC discrimination — an entry with no cached file at all is reported missing, never silently skipped`(
        @TempDir dir: File,
    ) {
        val e = entry(id = "UNFETCHED")
        // Deliberately no seedCache call.

        val plan = ModelMirrorPublisher.plan(entries = listOf(e), cacheRoot = dir, existingAssets = emptyMap())

        assertEquals(listOf(e), plan.missingLocally)
        assertTrue(plan.toUpload.isEmpty())
        assertTrue(plan.skipped.isEmpty())
    }

    @Test
    fun `a mixed batch sorts each entry into exactly the right bucket`(@TempDir dir: File) {
        val present = entry(id = "PRESENT", sha256 = "1".repeat(64), sizeBytes = 10L, mirrorUrl = mirrorUrlFor("present.bin"))
        val stale = entry(id = "STALE", sha256 = "2".repeat(64), sizeBytes = 20L, mirrorUrl = mirrorUrlFor("stale.bin"))
        val new = entry(id = "NEW", sha256 = "3".repeat(64), sizeBytes = 30L, mirrorUrl = mirrorUrlFor("new.bin"))
        val missing = entry(id = "MISSING", sha256 = "4".repeat(64), sizeBytes = 40L, mirrorUrl = mirrorUrlFor("missing.bin"))

        seedCache(dir, present, ByteArray(10))
        seedCache(dir, stale, ByteArray(20))
        seedCache(dir, new, ByteArray(30))

        val plan = ModelMirrorPublisher.plan(
            entries = listOf(present, stale, new, missing),
            cacheRoot = dir,
            existingAssets = mapOf(
                ModelMirrorPublisher.mirrorAssetName(present) to 10L,
                ModelMirrorPublisher.mirrorAssetName(stale) to 999L,
            ),
        )

        assertEquals(listOf(present), plan.skipped)
        assertEquals(setOf("STALE", "NEW"), plan.toUpload.map { it.entry.id }.toSet())
        assertEquals(listOf(missing), plan.missingLocally)
    }

    @Test
    fun `a trust-on-first-fetch entry is looked up in the cache by its own id, not a sentinel folder`(
        @TempDir dir: File,
    ) {
        val e = entry(id = "TOFF", sha256 = BundledAssetManifest.TRUST_ON_FIRST_FETCH)
        val cacheDir = File(dir, "TOFF").apply { mkdirs() }
        val cached = File(cacheDir, ModelMirrorPublisher.mirrorAssetName(e)).apply { writeBytes(ByteArray(100)) }

        val plan = ModelMirrorPublisher.plan(entries = listOf(e), cacheRoot = dir, existingAssets = emptyMap())

        assertEquals(cached, plan.toUpload.single().file)
    }

    // ---- parseExistingAssets ------------------------------------------------------------------

    @Test
    fun `parseExistingAssets reads name and size from a real gh release view shape`() {
        val json = """
            {"assets": [
              {"url": "https://api.github.com/x", "name": "a.bin", "size": 12345, "state": "uploaded"},
              {"name": "b.bin", "size": 67890}
            ]}
        """.trimIndent()

        val result = ModelMirrorPublisher.parseExistingAssets(json)

        assertEquals(mapOf("a.bin" to 12345L, "b.bin" to 67890L), result)
    }

    @Test
    fun `parseExistingAssets returns an empty map for a release with no assets`() {
        assertEquals(emptyMap<String, Long>(), ModelMirrorPublisher.parseExistingAssets("""{"assets": []}"""))
    }

    @Test
    fun `parseExistingAssets returns an empty map rather than throwing on unexpected input`() {
        assertEquals(emptyMap<String, Long>(), ModelMirrorPublisher.parseExistingAssets("not json at all"))
        assertEquals(emptyMap<String, Long>(), ModelMirrorPublisher.parseExistingAssets("""{"other": true}"""))
    }
}
