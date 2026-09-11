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

    // ---- R-934 (register): the rejection is persisted, readable by a fresh controller, and clears
    // on a later successful verify — the "owed installer fact" WPE's own round-4 investigation
    // named, closing the gap where a corrupted-and-removed part read exactly like a never-attempted
    // one. -----------------------------------------------------------------------------------------

    @Test
    @Requirement("R-934", "AC-137")
    fun `R_934 a checksum-mismatch rejection is persisted and read back by a fresh call, not an in-memory field`() {
        val filesDir = Files.createTempDirectory("bundled-installer-rejection-persist").toFile()
        val correctBytes = byteArrayOf(4, 5, 6, 7)
        val correctSha = sha256(correctBytes)
        val corruptBytes = byteArrayOf(9, 9, 9)
        val corruptSha = sha256(corruptBytes)
        val relativeDestination = "models/silero-vad/silero_vad.onnx"
        val source = FakeBundledAssetSource(
            mapOf(
                "bundled/manifest.json" to manifestJson(
                    listOf(Triple("VAD", relativeDestination, correctSha)),
                ).toByteArray(),
                "bundled/$relativeDestination" to corruptBytes,
            ),
        )

        val failed = BundledAssetInstaller.installAll(filesDir, source).single()
        assertTrue("expected Failed, got $failed", failed is BundledAssetState.Failed)

        // A brand-new read against the real filesystem — nothing about BundledAssetInstaller is a
        // process-lifetime cache, so this is exactly what "a fresh controller" (a new process, a
        // new ModelsController.currentState call, anything) would also see.
        val destination = java.io.File(filesDir, relativeDestination)
        val rejection = BundledAssetInstaller.lastRejectionFor(destination)

        assertTrue("expected a persisted rejection, got null", rejection != null)
        checkNotNull(rejection)
        assertEquals("VAD", rejection.id)
        assertEquals(relativeDestination, rejection.part)
        assertEquals(correctSha.take(8), rejection.expectedPrefix)
        assertEquals(corruptSha.take(8), rejection.actualPrefix)
        assertTrue("expected a real wall-clock time, got ${rejection.wallTimeMillis}", rejection.wallTimeMillis > 0)
        assertTrue(rejection.reason.contains("AC-137"))
    }

    @Test
    @Requirement("R-934", "AC-137")
    fun `R_934 a later successful verify clears the persisted rejection`() {
        val filesDir = Files.createTempDirectory("bundled-installer-rejection-clear").toFile()
        val correctBytes = byteArrayOf(4, 5, 6, 7)
        val correctSha = sha256(correctBytes)
        val relativeDestination = "models/silero-vad/silero_vad.onnx"
        val manifest = manifestJson(listOf(Triple("VAD", relativeDestination, correctSha)))
        val destination = java.io.File(filesDir, relativeDestination)

        val corruptSource = FakeBundledAssetSource(
            mapOf(
                "bundled/manifest.json" to manifest.toByteArray(),
                "bundled/$relativeDestination" to byteArrayOf(0, 0, 0),
            ),
        )
        BundledAssetInstaller.installAll(filesDir, corruptSource)
        assertTrue(
            "the rejection must exist before the fix, or this test proves nothing",
            BundledAssetInstaller.lastRejectionFor(destination) != null,
        )

        val fixedSource = FakeBundledAssetSource(
            mapOf(
                "bundled/manifest.json" to manifest.toByteArray(),
                "bundled/$relativeDestination" to correctBytes,
            ),
        )
        val recovered = BundledAssetInstaller.reinstall("VAD", filesDir, fixedSource)

        assertTrue("expected Installed, got $recovered", recovered is BundledAssetState.Installed)
        assertTrue(
            "a part that now verifies must not still read as rejected",
            BundledAssetInstaller.lastRejectionFor(destination) == null,
        )
    }

    @Test
    @Requirement("R-934", "AC-137")
    fun `R_934 an already-verified idempotent install also clears a stale rejection left from an earlier run`() {
        // Covers installOne's OTHER success branch (the idempotency shortcut), not just a fresh
        // copy+verify — a stale .rejected file from a prior, unrelated failure must not survive a
        // launch where the destination is already correctly installed.
        val filesDir = Files.createTempDirectory("bundled-installer-rejection-clear-idempotent").toFile()
        val bytes = byteArrayOf(1, 2, 3)
        val sha = sha256(bytes)
        val relativeDestination = "models/silero-vad/silero_vad.onnx"
        val destination = java.io.File(filesDir, relativeDestination)
        destination.parentFile?.mkdirs()
        destination.writeBytes(bytes)
        java.io.File(destination.parentFile, destination.name + ".sha256").writeText(sha)
        java.io.File(destination.parentFile, destination.name + ".rejected").writeText(
            "id=VAD\npart=$relativeDestination\nexpectedPrefix=aaaaaaaa\nactualPrefix=bbbbbbbb\n" +
                "wallTimeMillis=1\nreason=stale",
        )

        val source = FakeBundledAssetSource(
            mapOf(
                "bundled/manifest.json" to manifestJson(listOf(Triple("VAD", relativeDestination, sha))).toByteArray(),
            ),
        )

        val result = BundledAssetInstaller.installAll(filesDir, source).single()

        assertTrue(result is BundledAssetState.Installed)
        assertTrue(
            "the stale rejection must not survive an already-verified launch",
            BundledAssetInstaller.lastRejectionFor(destination) == null,
        )
    }

    @Test
    @Requirement("R-934", "AC-137")
    fun `R_934 the CorruptingBundledAssetSource scenario path persists a rejection for only the corrupted part`() {
        // Mirrors app/src/debug/kotlin/org/ort/app/debug/Scenarios.kt's own asset-corrupt fixture
        // exactly: every entry reads through untouched except one, which is served genuinely
        // corrupted bytes (one byte flipped) rather than a fabricated digest — that private class
        // cannot be imported from this module's own test source set, so this reproduces its one
        // real mechanism rather than a hand-picked mismatch, proving the real scenario's own shape
        // (several good parts, one genuinely corrupted) exercises the persisted rejection.
        val filesDir = Files.createTempDirectory("bundled-installer-scenario-corrupt").toFile()
        val encoderBytes = byteArrayOf(1, 2, 3, 4, 5)
        val vadBytes = byteArrayOf(9, 8, 7)
        val entries = listOf(
            Triple("ASR_ENCODER", "models/whisper-tiny-en-int8/tiny.en-encoder.int8.onnx", sha256(encoderBytes)),
            Triple("VAD", "models/silero-vad/silero_vad.onnx", sha256(vadBytes)),
        )
        val corruptAssetPath = "bundled/models/whisper-tiny-en-int8/tiny.en-encoder.int8.onnx"
        val realSource = FakeBundledAssetSource(
            mapOf(
                "bundled/manifest.json" to manifestJson(entries).toByteArray(),
                corruptAssetPath to encoderBytes,
                "bundled/models/silero-vad/silero_vad.onnx" to vadBytes,
            ),
        )
        val corruptingSource = object : BundledAssetSource {
            override fun open(assetPath: String): java.io.InputStream {
                val stream = realSource.open(assetPath)
                if (assetPath != corruptAssetPath) return stream
                val original = stream.use { it.readBytes() }
                val corrupted = original.copyOf()
                corrupted[corrupted.size - 1] = corrupted[corrupted.size - 1].inc()
                return corrupted.inputStream()
            }
        }

        val results = BundledAssetInstaller.installAll(filesDir, corruptingSource)

        val encoderResult = results.first { it.id == "ASR_ENCODER" }
        val vadResult = results.first { it.id == "VAD" }
        assertTrue("expected the encoder to fail, got $encoderResult", encoderResult is BundledAssetState.Failed)
        assertTrue("expected VAD to install for real, got $vadResult", vadResult is BundledAssetState.Installed)

        val encoderDestination = java.io.File(filesDir, "models/whisper-tiny-en-int8/tiny.en-encoder.int8.onnx")
        val vadDestination = java.io.File(filesDir, "models/silero-vad/silero_vad.onnx")
        val encoderRejection = BundledAssetInstaller.lastRejectionFor(encoderDestination)
        assertTrue("expected a persisted rejection for the corrupted encoder", encoderRejection != null)
        assertEquals("ASR_ENCODER", encoderRejection!!.id)
        assertTrue(
            "the genuinely-installed VAD part must carry no rejection at all",
            BundledAssetInstaller.lastRejectionFor(vadDestination) == null,
        )
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
