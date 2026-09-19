package org.ort.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * P23 (FR-AST-13, D43, build-plan Wave G "Tests first" bullet): the `play` flavor's generated
 * catalog must carry a download URL and sha256 per required-tier model while the `full` flavor's
 * does not need one — both still carry every build-time bundling field unchanged.
 */
class BundledAssetCatalogRendererTest {

    private fun entry(
        id: String = "ASR_ENCODER",
        mirrorUrl: String = "https://github.com/o/r/releases/download/models-v1/encoder.onnx",
    ) = BundledAssetManifest.Entry(
        id = id,
        url = "https://huggingface.co/o/r/resolve/main/encoder.onnx",
        sha256 = "a".repeat(64),
        sizeBytes = 12_937_772L,
        destination = "models/whisper-tiny-en-int8/encoder.onnx",
        tiers = listOf("T0", "T1"),
        licence = "MIT",
        gated = false,
        mirrorUrl = mirrorUrl,
    )

    @Test
    fun `AC discrimination — the full flavor's catalog marks every entry bundled with a blank download URL`() {
        val rendered = BundledAssetCatalogRenderer.render(listOf(entry()), bundled = true)

        assertTrue(rendered.contains("bundled = true,"), rendered)
        assertTrue(rendered.contains("downloadUrl = \"\","), "full must not need a download URL, got:\n$rendered")
        assertFalse(rendered.contains(entry().mirrorUrl), "full must not embed the mirror URL at all")
    }

    @Test
    fun `AC discrimination — the play flavor's catalog marks every entry not-bundled with its mirror URL`() {
        val e = entry()
        val rendered = BundledAssetCatalogRenderer.render(listOf(e), bundled = false)

        assertTrue(rendered.contains("bundled = false,"), rendered)
        assertTrue(rendered.contains(e.mirrorUrl), "play must carry the real download URL, got:\n$rendered")
        assertFalse(rendered.contains("downloadUrl = \"\","), "play must never leave the download URL blank")
    }

    @Test
    fun `both flavors still carry every build-time bundling field unchanged`() {
        val e = entry()
        for (bundled in listOf(true, false)) {
            val rendered = BundledAssetCatalogRenderer.render(listOf(e), bundled = bundled)
            assertTrue(rendered.contains("id = \"ASR_ENCODER\","), rendered)
            assertTrue(rendered.contains(e.url), rendered)
            assertTrue(rendered.contains("sha256 = \"${e.sha256}\","), rendered)
            assertTrue(rendered.contains("sizeBytes = 12937772L,"), rendered)
            assertTrue(rendered.contains(e.destination), rendered)
            assertTrue(rendered.contains("tiers = listOf(\"T0\", \"T1\"),"), rendered)
            assertTrue(rendered.contains("licence = \"MIT\","), rendered)
            assertTrue(rendered.contains("gated = false,"), rendered)
        }
    }

    @Test
    fun `kotlinFieldLine splits a too-long value at the nearest slash before the midpoint, both halves fitting`() {
        // Chosen so the single-line form exceeds 120 columns but a `/`-bounded split at the exact
        // midpoint leaves both resulting lines comfortably under it (this file's own real
        // manifest URLs are far shorter — this exercises the split path deliberately).
        val value = "a".repeat(60) + "/" + "b".repeat(60)
        val line = BundledAssetCatalogRenderer.kotlinFieldLine(indent = "            ", name = "downloadUrl", value = value)

        val lines = line.split("\n")
        assertEquals(2, lines.size, "a too-long value must split into exactly two lines, got:\n$line")
        assertTrue(lines[0].length <= 120, "first line too long (${lines[0].length}): ${lines[0]}")
        assertTrue(lines[1].length <= 120, "second line too long (${lines[1].length}): ${lines[1]}")
        assertTrue(lines[0].trimEnd().endsWith("+"), "the first line must end with +, got: ${lines[0]}")

        val firstPart = lines[0].substringAfter("\"").substringBeforeLast("\"")
        val secondPart = lines[1].substringAfter("\"").substringBeforeLast("\"")
        assertEquals(value, firstPart + secondPart, "the split literals must reconstruct the original value exactly")
    }

    @Test
    fun `a short value is never split even when render is asked to`() {
        val line = BundledAssetCatalogRenderer.kotlinFieldLine(indent = "    ", name = "url", value = "short")
        assertEquals("    url = \"short\",", line)
        assertFalse(line.contains("\n"))
    }

    @Test
    fun `rendering the same entries twice produces byte-identical output`() {
        val e = entry()
        assertEquals(
            BundledAssetCatalogRenderer.render(listOf(e), bundled = true),
            BundledAssetCatalogRenderer.render(listOf(e), bundled = true),
        )
    }
}
