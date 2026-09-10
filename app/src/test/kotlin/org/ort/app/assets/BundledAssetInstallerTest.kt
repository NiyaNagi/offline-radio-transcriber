package org.ort.app.assets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ort.testing.Requirement
import java.nio.file.Files
import java.security.MessageDigest

/**
 * WPG (`spec/e2e-capture-modes-plan.md`, FR-AST-3/3a/3b, AC-136, AC-137). Every test uses
 * [FakeBundledAssetSource] — a plain in-memory map — never a real `Context`/`AssetManager` and
 * never any `:net` type, so [org.ort.app.assets.BundledAssetInstaller.installAll]'s own
 * "no network object of any kind" claim is something these tests actually exercise, not merely
 * assert.
 */
class BundledAssetInstallerTest {

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun manifestJson(entries: List<Triple<String, String, String>>, missingIds: Set<String> = emptySet()) =
        buildString {
            append("{\"assets\": [")
            entries.forEachIndexed { index, (id, destination, sha) ->
                append(
                    """{"id": "$id", "destination": "$destination", "sha256": "$sha", "sizeBytes": 1, """ +
                        """"tiers": ["T0"], "missing": ${id in missingIds}}""",
                )
                if (index != entries.lastIndex) append(",")
            }
            append("]}")
        }

    @Test
    @Requirement("AC-136")
    fun `AC_136 a fresh install has every bundled asset installed and verified with no network object involved`() {
        val filesDir = Files.createTempDirectory("bundled-installer-fresh").toFile()
        val encoderBytes = byteArrayOf(1, 2, 3, 4, 5)
        val vadBytes = byteArrayOf(9, 8, 7)
        val entries = listOf(
            Triple("ASR_ENCODER", "models/whisper-tiny-en-int8/tiny.en-encoder.int8.onnx", sha256(encoderBytes)),
            Triple("VAD", "models/silero-vad/silero_vad.onnx", sha256(vadBytes)),
        )
        val source = FakeBundledAssetSource(
            mapOf(
                "bundled/manifest.json" to manifestJson(entries).toByteArray(),
                "bundled/models/whisper-tiny-en-int8/tiny.en-encoder.int8.onnx" to encoderBytes,
                "bundled/models/silero-vad/silero_vad.onnx" to vadBytes,
            ),
        )

        val results = BundledAssetInstaller.installAll(filesDir, source)

        assertEquals(2, results.size)
        results.forEach { assertTrue("expected Installed, got $it", it is BundledAssetState.Installed) }
        val encoderDest = java.io.File(filesDir, "models/whisper-tiny-en-int8/tiny.en-encoder.int8.onnx")
        assertTrue(encoderDest.isFile)
        assertEquals(encoderBytes.toList(), encoderDest.readBytes().toList())
        val marker = java.io.File(encoderDest.parentFile, encoderDest.name + ".sha256")
        assertEquals(sha256(encoderBytes), marker.readText())
    }

    @Test
    @Requirement("AC-137")
    fun `AC_137 a corrupted bundled asset is refused, leaves no marker, and never touches a previous file`() {
        val filesDir = Files.createTempDirectory("bundled-installer-corrupt").toFile()
        val goodOldBytes = byteArrayOf(1, 1, 1)
        val correctSha = sha256(byteArrayOf(2, 2, 2, 2)) // what the manifest expects
        val destination = java.io.File(filesDir, "models/silero-vad/silero_vad.onnx")
        destination.parentFile?.mkdirs()
        destination.writeBytes(goodOldBytes)
        val marker = java.io.File(destination.parentFile, destination.name + ".sha256")
        marker.writeText(sha256(goodOldBytes)) // marks the OLD content as verified — does not match correctSha

        val corruptBytes = byteArrayOf(0, 0, 0) // does NOT hash to correctSha
        val source = FakeBundledAssetSource(
            mapOf(
                "bundled/manifest.json" to manifestJson(
                    listOf(Triple("VAD", "models/silero-vad/silero_vad.onnx", correctSha)),
                ).toByteArray(),
                "bundled/models/silero-vad/silero_vad.onnx" to corruptBytes,
            ),
        )

        val results = BundledAssetInstaller.installAll(filesDir, source)

        val result = results.single()
        assertTrue("expected Failed, got $result", result is BundledAssetState.Failed)

        // Constitution I: a stated, recoverable failure — never activated (AC-137).
        assertEquals(
            "the previous file must be left exactly as it was",
            goodOldBytes.toList(),
            destination.readBytes().toList(),
        )
        assertEquals(
            "the previous marker must be left exactly as it was — no marker for the corrupt attempt",
            sha256(goodOldBytes),
            marker.readText(),
        )
        assertFalse(
            "no leftover partial file",
            java.io.File(destination.parentFile, destination.name + ".part").exists(),
        )
    }

    @Test
    @Requirement("AC-137")
    fun `reinstall recovers a previously failed asset once the source has a valid copy`() {
        val filesDir = Files.createTempDirectory("bundled-installer-reinstall").toFile()
        val correctBytes = byteArrayOf(4, 5, 6, 7)
        val correctSha = sha256(correctBytes)
        val manifest = manifestJson(listOf(Triple("VAD", "models/silero-vad/silero_vad.onnx", correctSha)))

        val corruptSource = FakeBundledAssetSource(
            mapOf(
                "bundled/manifest.json" to manifest.toByteArray(),
                "bundled/models/silero-vad/silero_vad.onnx" to byteArrayOf(0, 0, 0),
            ),
        )
        val failed = BundledAssetInstaller.installAll(filesDir, corruptSource).single()
        assertTrue(failed is BundledAssetState.Failed)

        val fixedSource = FakeBundledAssetSource(
            mapOf(
                "bundled/manifest.json" to manifest.toByteArray(),
                "bundled/models/silero-vad/silero_vad.onnx" to correctBytes,
            ),
        )
        val recovered = BundledAssetInstaller.reinstall("VAD", filesDir, fixedSource)

        assertTrue("expected Installed after reinstall, got $recovered", recovered is BundledAssetState.Installed)
        val destination = java.io.File(filesDir, "models/silero-vad/silero_vad.onnx")
        assertEquals(correctBytes.toList(), destination.readBytes().toList())
        val marker = java.io.File(destination.parentFile, destination.name + ".sha256")
        assertEquals(correctSha, marker.readText())
    }

    @Test
    fun `an asset marked missing in the manifest reports NotBundledInThisBuild honestly, never Failed`() {
        val filesDir = Files.createTempDirectory("bundled-installer-missing").toFile()
        val manifest = manifestJson(
            listOf(Triple("LLM_GEMMA3_1B", "models/llm/gemma3-1b-it-int4.task", "a".repeat(64))),
            missingIds = setOf("LLM_GEMMA3_1B"),
        )
        val source = FakeBundledAssetSource(mapOf("bundled/manifest.json" to manifest.toByteArray()))

        val result = BundledAssetInstaller.installAll(filesDir, source).single()

        assertTrue("expected NotBundledInThisBuild, got $result", result is BundledAssetState.NotBundledInThisBuild)
    }

    @Test
    fun `installAll is idempotent — an already-verified asset is reported Installed without being re-copied`() {
        val filesDir = Files.createTempDirectory("bundled-installer-idempotent").toFile()
        val bytes = byteArrayOf(1, 2, 3)
        val destination = java.io.File(filesDir, "models/silero-vad/silero_vad.onnx")
        destination.parentFile?.mkdirs()
        destination.writeBytes(bytes)
        java.io.File(destination.parentFile, destination.name + ".sha256").writeText(sha256(bytes))

        // A source with NO asset bytes at all — if the installer tried to re-copy, this would throw.
        val source = FakeBundledAssetSource(
            mapOf(
                "bundled/manifest.json" to manifestJson(
                    listOf(Triple("VAD", "models/silero-vad/silero_vad.onnx", sha256(bytes))),
                ).toByteArray(),
            ),
        )

        val result = BundledAssetInstaller.installAll(filesDir, source).single()

        assertTrue(result is BundledAssetState.Installed)
    }
}
