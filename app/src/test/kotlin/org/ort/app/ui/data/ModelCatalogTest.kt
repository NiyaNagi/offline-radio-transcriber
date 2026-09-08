package org.ort.app.ui.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ort.testing.Requirement
import java.io.File

/**
 * Audit F-008 follow-up (FR-AST-1, constitution V/VII: integrity verified before activation).
 * Before this change, every [ModelCatalog] entry carried the old `UNPINNED_CHECKSUM` placeholder
 * — a human-readable, deliberately non-hex string — so a real fetch or side-load against any of
 * these specs would correctly fail closed, but the catalog gave no way to *distinguish* "we
 * checked and no digest is published" from "we haven't checked yet". These tests pin down the
 * real, sourced state: every entry is either a genuine 64-hex sha256 (read from published
 * metadata, never invented) or an explicit [ChecksumState.UnknownSideloadOnly], never a
 * placeholder string.
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
    fun `FR_AST_1 the ASR encoder and decoder and the VAD model carry a real published sha256`() {
        listOf(ModelId.ASR_ENCODER, ModelId.ASR_DECODER, ModelId.VAD).forEach { id ->
            val state = ModelCatalog.entry(id).checksumState
            assertTrue("$id should have a Known checksum, was $state", state is ChecksumState.Known)
        }
    }

    @Test
    @Requirement("FR-AST-1")
    fun `FR_AST_1 the tokens file has no published sha256 and is explicitly unknown, not invented`() {
        val state = ModelCatalog.entry(ModelId.ASR_TOKENS).checksumState
        assertTrue("ASR_TOKENS should be UnknownSideloadOnly, was $state", state is ChecksumState.UnknownSideloadOnly)
    }

    @Test
    @Requirement("FR-AST-1")
    fun `FR_AST_1 specFor returns null for an unknown-checksum entry and a real spec for a known one`() {
        val filesDir = File(".")
        assertNull(ModelCatalog.specFor(ModelId.ASR_TOKENS, filesDir))
        assertNotNull(ModelCatalog.specFor(ModelId.VAD, filesDir))
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
        // release (k2-fsa/sherpa-onnx, tag `asr-models`) as the model itself — a tab-separated
        // `<asset filename>\t<sha256>` line for `silero_vad.onnx` specifically (not the unrelated
        // `silero_vad_v5.onnx` asset, whose GitHub API `digest` field is null anyway).
        val vad = ModelCatalog.entry(ModelId.VAD).checksumState as ChecksumState.Known
        assertEquals("9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6", vad.checksum.value)
    }

    @Test
    @Requirement("FR-AST-1")
    fun `FR_AST_1 an unknown-checksum entry's reason cites what was actually checked`() {
        val reason = (ModelCatalog.entry(ModelId.ASR_TOKENS).checksumState as ChecksumState.UnknownSideloadOnly).reason
        // Must say enough that a future session doesn't have to re-derive why this one is
        // unpinned: it is not LFS-tracked (so its HF API "oid" is a git blob sha1, not a sha256).
        assertFalse(reason.contains("UNPINNED"))
        assertTrue(reason.contains("sha1", ignoreCase = true) || reason.contains("blob", ignoreCase = true))
    }
}
