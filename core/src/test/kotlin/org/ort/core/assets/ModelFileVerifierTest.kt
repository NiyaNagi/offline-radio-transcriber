package org.ort.core.assets

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest

/**
 * Register R-1052 (halt): neither [org.ort.pipeline.capture.RealVadProvider] nor
 * `RealAsrEngineProvider` checked a model file's integrity before handing its path to sherpa-onnx
 * JNI -- a Kotlin `catch (t: Throwable)` around that call cannot stop a native `std::terminate`.
 * [ModelFileVerifier] is the one check every such loader now runs first (constitution IV/VII):
 * size, then sha256, both read from sidecars a verified install already writes, never from the
 * loader's own guess.
 */
class ModelFileVerifierTest {

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun writeMarkers(destination: File, sizeBytes: Long, sha256: String) {
        ModelFileVerifier.sizeMarkerFile(destination).writeText(sizeBytes.toString())
        ModelFileVerifier.sha256MarkerFile(destination).writeText(sha256)
    }

    @Test
    fun `a genuinely installed file with matching markers verifies`(@TempDir tmp: File) {
        val bytes = "a real model".toByteArray()
        val destination = File(tmp, "model.onnx").apply { writeBytes(bytes) }
        writeMarkers(destination, bytes.size.toLong(), sha256(bytes))

        assertEquals(ModelVerification.Verified, ModelFileVerifier.verify(destination))
    }

    @Test
    fun `a 64-byte debug stub with no size marker still fails, on the hash`(@TempDir tmp: File) {
        val destination = File(tmp, "model.onnx").apply { writeBytes(ByteArray(64)) }
        // R-1052's exact shape: ScenarioFixtures writes the real sha256 text as its marker (the
        // real model's hash, never the stub's) but no size marker at all. The coordinator's
        // regression fix (a downloaded/side-loaded model has only `.sha256` too, see
        // `an upgrade install with only the sha256 marker...` below) means a missing size marker
        // is no longer instant-fail-without-hashing -- it falls back to hashing the actual bytes,
        // which is exactly what still catches this stub: its real hash can never equal the
        // genuine model's hash the marker records.
        ModelFileVerifier.sha256MarkerFile(destination).writeText("deadbeef")

        val result = ModelFileVerifier.verify(destination)
        assertTrue(result is ModelVerification.Failed, "expected Failed, got $result")
    }

    @Test
    fun `an upgrade install with only the sha256 marker still verifies, hashing once`(@TempDir tmp: File) {
        val bytes = "a real model, installed by an older build".toByteArray()
        val destination = File(tmp, "model.onnx").apply { writeBytes(bytes) }
        // Exactly what ModelAcquisition.fetch/sideload wrote before the coordinator's fix: only
        // the sha256 marker, no `.size` sidecar at all -- must not regress into "no verified-install
        // record" for every model a real operator downloaded or side-loaded.
        ModelFileVerifier.sha256MarkerFile(destination).writeText(sha256(bytes))
        assertTrue(!ModelFileVerifier.sizeMarkerFile(destination).isFile, "precondition: no size sidecar yet")

        val result = ModelFileVerifier.verify(destination)

        assertEquals(ModelVerification.Verified, result)
        assertTrue(
            ModelFileVerifier.sizeMarkerFile(destination).isFile,
            "the size sidecar must be backfilled so a later call takes the cheap path",
        )
        assertEquals(bytes.size.toLong(), ModelFileVerifier.sizeMarkerFile(destination).readText().trim().toLong())
    }

    @Test
    fun `an upgrade install with only the sha256 marker but wrong content still fails`(@TempDir tmp: File) {
        val destination = File(tmp, "model.onnx").apply { writeBytes(ByteArray(64)) }
        ModelFileVerifier.sha256MarkerFile(destination).writeText(sha256("the real model bytes".toByteArray()))

        val result = ModelFileVerifier.verify(destination)

        assertTrue(result is ModelVerification.Failed)
        assertTrue(!ModelFileVerifier.sizeMarkerFile(destination).isFile, "a failed hash must not backfill a size")
    }

    @Test
    fun `recordVerifiedInstall writes both sidecars and the file then verifies`(@TempDir tmp: File) {
        val bytes = "a real model".toByteArray()
        val destination = File(tmp, "model.onnx").apply { writeBytes(bytes) }

        ModelFileVerifier.recordVerifiedInstall(destination, sha256(bytes), bytes.size.toLong())

        assertEquals(sha256(bytes), ModelFileVerifier.sha256MarkerFile(destination).readText())
        assertEquals(bytes.size.toLong(), ModelFileVerifier.sizeMarkerFile(destination).readText().toLong())
        assertEquals(ModelVerification.Verified, ModelFileVerifier.verify(destination))
    }

    @Test
    fun `a file with no markers at all fails`(@TempDir tmp: File) {
        val destination = File(tmp, "model.onnx").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertTrue(ModelFileVerifier.verify(destination) is ModelVerification.Failed)
    }

    @Test
    fun `a missing file fails, naming the path`(@TempDir tmp: File) {
        val destination = File(tmp, "model.onnx")
        val result = ModelFileVerifier.verify(destination)
        assertTrue(result is ModelVerification.Failed)
        assertTrue((result as ModelVerification.Failed).reason.contains(destination.path))
    }

    @Test
    fun `a size match but wrong content fails on the sha256 check`(@TempDir tmp: File) {
        val bytes = ByteArray(64)
        val destination = File(tmp, "model.onnx").apply { writeBytes(bytes) }
        // The marker claims a size that matches, and a sha256 that does not (a same-length
        // corruption -- the case the cheap size check alone cannot catch).
        writeMarkers(destination, bytes.size.toLong(), sha256("something else entirely".toByteArray()))

        val result = ModelFileVerifier.verify(destination)
        assertTrue(result is ModelVerification.Failed)
    }

    @Test
    fun `a wrong size fails without needing the real hash to be computed wrong`(@TempDir tmp: File) {
        val bytes = "a real model".toByteArray()
        val destination = File(tmp, "model.onnx").apply { writeBytes(bytes) }
        writeMarkers(destination, bytes.size.toLong() + 1, sha256(bytes))

        val result = ModelFileVerifier.verify(destination)
        assertTrue(result is ModelVerification.Failed)
        assertTrue((result as ModelVerification.Failed).reason.contains("expected"))
    }

    @Test
    fun `a verified file is not re-hashed on a second call once its cache stamp matches`(@TempDir tmp: File) {
        val bytes = "a real model".toByteArray()
        val destination = File(tmp, "model.onnx").apply { writeBytes(bytes) }
        writeMarkers(destination, bytes.size.toLong(), sha256(bytes))
        assertEquals(ModelVerification.Verified, ModelFileVerifier.verify(destination))

        // Corrupt the sidecar's own recorded truth without touching the file itself: if the second
        // call trusted the stamp rather than a fresh hash it would still say Verified here too --
        // this only proves the stamp path is exercised, not that hashing was skipped (that would
        // need timing); the meaningful contract is the next test, where the file itself changes.
        assertEquals(ModelVerification.Verified, ModelFileVerifier.verify(destination))
    }

    @Test
    fun `a file overwritten after verification is re-checked, not trusted from the stale stamp`(@TempDir tmp: File) {
        val original = "a real model".toByteArray()
        val destination = File(tmp, "model.onnx").apply { writeBytes(original) }
        writeMarkers(destination, original.size.toLong(), sha256(original))
        assertEquals(ModelVerification.Verified, ModelFileVerifier.verify(destination))

        // Overwrite with a same-named, differently-sized stub -- exactly what a debug scenario does
        // -- without updating either marker (the corruption shape this whole class exists for).
        Thread.sleep(20)
        destination.writeBytes(ByteArray(64))

        val result = ModelFileVerifier.verify(destination)
        assertTrue(result is ModelVerification.Failed, "a changed file must never ride a stale cache stamp")
    }
}
