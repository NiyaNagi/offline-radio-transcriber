package org.ort.app.ui.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ort.app.assets.GeneratedBundledAssetEntry
import org.ort.testing.Requirement
import java.io.File

/**
 * Audit F-008 follow-up (FR-AST-1, constitution V/VII: integrity verified before activation).
 * These tests pin down the real, sourced state: every entry is either a genuine 64-hex sha256
 * (read from published metadata or pinned by a real first fetch, never invented) or an explicit
 * [ChecksumState.UnknownSideloadOnly], never a placeholder string.
 *
 * **WPG follow-up (D35/D36, FR-AST-3):** [ModelCatalog.entries] is now generated from the root
 * `bundled-assets.json` manifest at build time (see `ModelsViewData.kt`'s top KDoc and
 * `buildSrc/FetchBundledAssetsTask.kt`'s `BundledAssetManifest`), and `ASR_TOKENS`'s checksum was
 * pinned for real by that task's first fetch — its state is now [ChecksumState.Known], not
 * [ChecksumState.UnknownSideloadOnly] (a real, upgraded guarantee, not a relaxation — see
 * `ModelsViewData.kt`'s KDoc for why). The tests below that used to pin `ASR_TOKENS` as the one
 * unknown-checksum entry are rewritten accordingly: [ModelCatalog.checksumStateFor] (the pure
 * sentinel-mapping function) is tested directly for the `trust-on-first-fetch` case, since no
 * entry in the *committed* manifest is in that state today to exercise it through the public API.
 */
class ModelCatalogTest {

    private val sha256Pattern = Regex("^[0-9a-f]{64}$")

    @Test
    @Requirement("FR-AST-1")
    fun `FR_AST_1 no catalog entry carries a placeholder or otherwise non-hex checksum string`() {
        ModelCatalog.entries.forEach { entry ->
            when (val state = entry.checksumState) {
                is ChecksumState.Known -> {
                    assertTrue(
                        "${entry.id} has a Known checksum that is not 64-hex sha256: '${state.checksum.value}'",
                        sha256Pattern.matches(state.checksum.value),
                    )
                    assertEquals("sha256", state.checksum.algo)
                }
                is ChecksumState.UnknownSideloadOnly -> {
                    assertTrue(
                        "${entry.id}'s UnknownSideloadOnly reason must not be blank",
                        state.reason.isNotBlank(),
                    )
                }
            }
        }
    }

    @Test
    @Requirement("FR-AST-1")
    fun `FR_AST_1 every generated entry carries a real Known checksum, including the tokens file`() {
        // WPG: every entry in the committed manifest has a pinned digest today — ASR_TOKENS's own
        // via buildSrc's trust-on-first-fetch pinning (see this class's own top KDoc).
        ModelId.entries.forEach { id ->
            val state = ModelCatalog.entry(id).checksumState
            assertTrue("$id should have a Known checksum, was $state", state is ChecksumState.Known)
        }
    }

    @Test
    @Requirement("FR-AST-1")
    fun `FR_AST_1 checksumStateFor maps the trust-on-first-fetch sentinel to UnknownSideloadOnly, never invented`() {
        val state = ModelCatalog.checksumStateFor("trust-on-first-fetch")
        assertTrue("expected UnknownSideloadOnly, was $state", state is ChecksumState.UnknownSideloadOnly)
    }

    @Test
    @Requirement("FR-AST-1")
    fun `FR_AST_1 checksumStateFor maps a real 64-hex digest to Known`() {
        val hex = "a".repeat(64)
        val state = ModelCatalog.checksumStateFor(hex)
        assertTrue("expected Known, was $state", state is ChecksumState.Known)
        assertEquals(hex, (state as ChecksumState.Known).checksum.value)
    }

    @Test
    @Requirement("FR-AST-1")
    fun `FR_AST_1 specFor returns a real spec for every entry in the generated catalog`() {
        val filesDir = File(".")
        ModelId.entries.forEach { id ->
            val spec = ModelCatalog.specFor(id, filesDir)
            assertNotNull("expected a spec for $id, every catalog entry is Known today", spec)
        }
    }

    @Test
    @Requirement("FR-AST-1")
    fun `FR_AST_1 known checksums match the exact values read from published metadata`() {
        // Read 2026-09-07 from the HuggingFace Git-LFS pointer text at
        // https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny.en/raw/main/<file> — the
        // small, non-binary pointer an LFS-tracked file's `raw` endpoint returns, containing
        // `oid sha256:<hex>` and `size <bytes>`. Not computed from a download this change made.
        val encoder = ModelCatalog.entry(ModelId.ASR_ENCODER).checksumState as ChecksumState.Known
        assertEquals("0ce578b827c94a961aacb8fa14b02f096504b337e5c94be37c36238cbe3e8bc6", encoder.checksum.value)

        val decoder = ModelCatalog.entry(ModelId.ASR_DECODER).checksumState as ChecksumState.Known
        assertEquals("06c0e6ff6348d427e51839219d1c886c18cfdf411e629e33f5e1679bff9c1527", decoder.checksum.value)

        // Read 2026-09-07 from the `checksum.txt` manifest asset published in the same GitHub
        // release (k2-fsa/sherpa-onnx, tag `asr-models`) as the model itself.
        val vad = ModelCatalog.entry(ModelId.VAD).checksumState as ChecksumState.Known
        assertEquals("9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6", vad.checksum.value)

        // WPG: pinned 2026-09-10 by a real fetch of this exact URL — see bundled-assets.json's own
        // "_sha256Note" for ASR_TOKENS.
        val tokens = ModelCatalog.entry(ModelId.ASR_TOKENS).checksumState as ChecksumState.Known
        assertEquals("306cd27f03c1a714eca7108e03d66b7dc042abe8c258b44c199a7ed9838dd930", tokens.checksum.value)

        // D36: Gemma 3 1B int4, gated, tier-3-only.
        val gemma = ModelCatalog.entry(ModelId.LLM_GEMMA3_1B).checksumState as ChecksumState.Known
        assertEquals("e3d981c01aeaaac69a84ffa0d4be13281b3176731063f1bea1c9fe6887bd9dee", gemma.checksum.value)
    }

    @Test
    @Requirement("R-267")
    fun `R_267 the trust-on-first-fetch reason is a short operator fact, not the maintainer's research trail`() {
        val reason = (ModelCatalog.checksumStateFor("trust-on-first-fetch") as ChecksumState.UnknownSideloadOnly).reason
        assertFalse(reason.contains("UNPINNED"))
        assertFalse("reason must not wrap onto a second line: $reason", reason.contains('\n'))
        assertTrue("expected a short operator fact, got ${reason.length} chars: $reason", reason.length <= 60)
        assertFalse(reason.contains("HuggingFace"))
        assertFalse(reason.contains("checked 2026"))
    }

    @Test
    @Requirement("D35", "D36", "FR-AST-3")
    fun `WPG every manifest entry round-trips with the same destination the old hardcoded entries had`() {
        // Pins the OLD, pre-WPG hardcoded destinations (audit F-008's original ModelCatalog) so a
        // future manifest edit cannot silently move a file the real locators
        // (AsrModelLocator/SileroVadLocator) still expect at these exact paths.
        val filesDir = File("/fake-files-dir")
        fun destinationOf(id: ModelId) = ModelCatalog.entry(id).destination(filesDir).path.replace('\\', '/')

        assertEquals(
            "/fake-files-dir/models/whisper-tiny-en-int8/tiny.en-encoder.int8.onnx",
            destinationOf(ModelId.ASR_ENCODER),
        )
        assertEquals(
            "/fake-files-dir/models/whisper-tiny-en-int8/tiny.en-decoder.int8.onnx",
            destinationOf(ModelId.ASR_DECODER),
        )
        assertEquals(
            "/fake-files-dir/models/whisper-tiny-en-int8/tiny.en-tokens.txt",
            destinationOf(ModelId.ASR_TOKENS),
        )
        assertEquals("/fake-files-dir/models/silero-vad/silero_vad.onnx", destinationOf(ModelId.VAD))
        assertEquals("/fake-files-dir/models/llm/gemma3-1b-it-int4.task", destinationOf(ModelId.LLM_GEMMA3_1B))
    }

    /**
     * P28b (D43, FR-AST-13): this used to assert every entry is bundled, full stop — true only
     * because [ModelCatalog.entries] hardcoded `bundled = true` regardless of flavor at the time.
     * Running `testPlayDebugUnitTest` against the real fix immediately caught the stale assumption
     * (`play`'s own compiled manifest genuinely reports `bundled = false`): rewritten to the
     * structural invariant that actually holds on *both* flavors — `bundled` and a blank
     * `downloadUrl` always agree, by construction of `BundledAssetCatalogRenderer` — rather than a
     * hardcoded `true` this file cannot own the truth of once two flavors exist.
     */
    @Test
    @Requirement("D43", "FR-AST-13", "FR-AST-14")
    fun `D43 every catalog entry's bundled flag agrees with whether its download URL is blank`() {
        ModelCatalog.entries.forEach { entry ->
            assertEquals(
                "${entry.id}: bundled=${entry.bundled} but downloadUrl='${entry.downloadUrl}' disagrees " +
                    "-- a blank download URL must mean bundled, and vice versa",
                entry.bundled,
                entry.downloadUrl.isBlank(),
            )
        }
    }

    @Test
    @Requirement("D35", "FR-AST-3")
    fun `WPG the Gemma model is gated and tier-3-only, and VAD ships for every tier, whatever this flavor bundles`() {
        val gemma = ModelCatalog.entry(ModelId.LLM_GEMMA3_1B)
        assertTrue(gemma.gated)
        assertEquals(setOf("T3"), gemma.tiers)
        assertTrue(gemma.sizeBytes > 0)

        val vad = ModelCatalog.entry(ModelId.VAD)
        assertFalse(vad.gated)
        assertEquals(setOf("T0", "T1", "T2", "T3"), vad.tiers)
    }

    /**
     * P28b (D43, D44, FR-AST-10..14, AC-184..190): before this change, [ModelCatalog.entries]
     * ignored [GeneratedBundledAssetEntry.bundled]/[GeneratedBundledAssetEntry.downloadUrl]
     * entirely and hardcoded [ModelCatalogEntry.bundled] to its own default, `true` — so a real
     * `play` build (whose generated manifest carries `bundled = false` and a real mirror URL per
     * entry) still reported every model as bundled, and [ModelsController.download] refused
     * unconditionally. These tests drive [ModelCatalog.mapEntries] directly with a fixture
     * [GeneratedBundledAssetEntry] list for both shapes, so they do not depend on which flavor's
     * `GeneratedBundledAssetManifest` this test module happens to be compiled against (this class's
     * other tests, above, exercise the real, compiled-in manifest — always `full` under
     * `testFullDebugUnitTest`, hence always `bundled = true` there).
     */
    private fun generatedFixtureEntry(
        id: String = "ASR_ENCODER",
        bundled: Boolean,
        downloadUrl: String = "",
        url: String = "https://huggingface.co/o/r/resolve/main/encoder.onnx",
    ) = GeneratedBundledAssetEntry(
        id = id,
        url = url,
        sha256 = "a".repeat(64),
        sizeBytes = 12_345L,
        destination = "models/fixture/encoder.onnx",
        tiers = listOf("T0"),
        licence = "MIT",
        gated = false,
        bundled = bundled,
        downloadUrl = downloadUrl,
    )

    @Test
    @Requirement("D43", "FR-AST-13", "AC-190")
    fun `D43 mapEntries reports a bundled fixture entry as bundled, with a blank download URL`() {
        val mapped = ModelCatalog.mapEntries(listOf(generatedFixtureEntry(bundled = true)))

        val entry = mapped.single()
        assertTrue(entry.bundled)
        assertEquals("", entry.downloadUrl)
    }

    @Test
    @Requirement("D43", "D44", "FR-AST-13", "FR-AST-14")
    fun `D43 mapEntries reports an unbundled fixture entry as not bundled, with its download URL`() {
        val mirror = "https://github.com/o/r/releases/download/models-v1/encoder.onnx"

        val mapped = ModelCatalog.mapEntries(listOf(generatedFixtureEntry(bundled = false, downloadUrl = mirror)))

        val entry = mapped.single()
        assertFalse("an unbundled entry must not report bundled", entry.bundled)
        assertEquals(mirror, entry.downloadUrl)
    }

    @Test
    @Requirement("D44", "FR-AST-14")
    fun `FR_AST_14 fetchUrl uses the entry's own url when bundled, never the blank download URL`() {
        val entry = ModelCatalog.mapEntries(listOf(generatedFixtureEntry(bundled = true))).single()

        assertEquals(entry.url, entry.fetchUrl())
    }

    @Test
    @Requirement("D44", "FR-AST-14")
    fun `FR_AST_14 fetchUrl uses the pinned models-v1 mirror once the entry is not bundled`() {
        val mirror = "https://github.com/o/r/releases/download/models-v1/encoder.onnx"
        val entry = ModelCatalog.mapEntries(listOf(generatedFixtureEntry(bundled = false, downloadUrl = mirror)))
            .single()

        assertEquals(mirror, entry.fetchUrl())
        assertFalse("must never fall back to the original provenance url once unbundled", entry.fetchUrl() == entry.url)
    }

    @Test
    @Requirement("D43", "D44", "FR-AST-14")
    fun `D43 mapEntries carries every other field through unchanged regardless of bundled`() {
        for (bundled in listOf(true, false)) {
            val downloadUrl = if (bundled) "" else "https://github.com/o/r/releases/download/models-v1/encoder.onnx"
            val fixture = generatedFixtureEntry(bundled = bundled, downloadUrl = downloadUrl)
            val entry = ModelCatalog.mapEntries(listOf(fixture)).single()

            assertEquals(ModelId.ASR_ENCODER, entry.id)
            assertEquals(12_345L, entry.sizeBytes)
            assertEquals(setOf("T0"), entry.tiers)
            assertEquals("MIT", entry.licence)
            assertFalse(entry.gated)
            assertTrue(entry.checksumState is ChecksumState.Known)
        }
    }
}
