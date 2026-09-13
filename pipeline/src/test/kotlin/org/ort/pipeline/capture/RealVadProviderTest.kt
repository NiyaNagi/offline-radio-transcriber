package org.ort.pipeline.capture

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.ort.pipeline.diagnostics.DiagnosticsLog
import org.ort.pipeline.diagnostics.ModelAssetId
import org.ort.testing.TestClock
import java.io.File
import java.security.MessageDigest

/**
 * Build-plan P12: [org.ort.pipeline.capture.RealCaptureService]'s `EnergyVadModel` is an
 * RMS-energy stand-in, explicitly not Silero. A real Silero VAD binding does exist in the
 * already-resolved `sherpa-onnx-jvm` jar (`asr-sherpa/README.md`), but it needs a real `.onnx`
 * model file this repo does not commit -- [RealVadProvider] must report that honestly (constitution
 * I) rather than silently falling back.
 *
 * Register R-1052 (halt): a validator's fresh install died with `SIGABRT` the moment [provide] was
 * reached with a debug-fixture stub sitting at the real model path — the native `RealSileroVad`
 * constructor throws an uncaught C++ exception no Kotlin `catch (t: Throwable)` here can stop.
 * [nativeLoader] is the seam that lets these tests prove the fix without ever loading a real
 * native library: it stands in for the one call ([RealSileroVad]'s constructor) that must never
 * happen once [org.ort.core.assets.ModelFileVerifier] has said a file is untrustworthy.
 */
class RealVadProviderTest {

    private val originalLoader = RealVadProvider.nativeLoader

    @AfterEach
    fun restoreLoader() {
        RealVadProvider.nativeLoader = originalLoader
        DiagnosticsLog.shutdown()
    }

    private fun capturePipelineLog(filesDir: File): List<String> {
        val file = File(File(filesDir, "diagnostics-logs"), DiagnosticsLog.Category.PIPELINE.fileName)
        return if (file.isFile) file.readLines() else emptyList()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `no model file present reports Unavailable, naming the expected path`(@TempDir tmp: File) {
        val result = RealVadProvider.provide(tmp)
        assertTrue(result is VadProvisionResult.Unavailable)
        val reason = (result as VadProvisionResult.Unavailable).reason
        assertTrue(reason.contains(SileroVadLocator.modelFile(tmp).path))
    }

    @Test
    fun `modelFile is a fixed, documented app-private path`(@TempDir tmp: File) {
        val expected = File(tmp, "models/silero-vad/silero_vad.onnx")
        assertTrue(SileroVadLocator.modelFile(tmp) == expected)
        assertFalse(expected.exists())
    }

    @Test
    fun `R_1052 a stub with no verified-install record never reaches the native loader`(@TempDir tmp: File) {
        val file = SileroVadLocator.modelFile(tmp)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(64)) // R-1052's exact shape: ScenarioFixtures' 64-byte stub

        var nativeLoaderCalled = false
        RealVadProvider.nativeLoader = { _ ->
            nativeLoaderCalled = true
            error("must never be reached — this stands in for the real JNI constructor")
        }

        val result = RealVadProvider.provide(tmp)

        assertFalse(nativeLoaderCalled, "the native loader must never be invoked for an unverified file")
        assertTrue(result is VadProvisionResult.Unavailable)
        val reason = (result as VadProvisionResult.Unavailable).reason
        assertTrue(reason.contains("failed verification") || reason.contains("verified-install"))
    }

    @Test
    fun `R_1052 a same-size corruption also never reaches the native loader`(@TempDir tmp: File) {
        val file = SileroVadLocator.modelFile(tmp)
        file.parentFile?.mkdirs()
        val bytes = ByteArray(64)
        file.writeBytes(bytes)
        // A marker claiming a size that matches but a sha256 that does not — the case a
        // size-only check would miss.
        org.ort.core.assets.ModelFileVerifier.sizeMarkerFile(file).writeText(bytes.size.toString())
        org.ort.core.assets.ModelFileVerifier.sha256MarkerFile(file).writeText(sha256("something else".toByteArray()))

        var nativeLoaderCalled = false
        RealVadProvider.nativeLoader = { _ ->
            nativeLoaderCalled = true
            error("must never be reached")
        }

        val result = RealVadProvider.provide(tmp)

        assertFalse(nativeLoaderCalled)
        assertTrue(result is VadProvisionResult.Unavailable)
    }

    @Test
    fun `R_1052 a genuinely verified model reaches the native loader exactly once`(@TempDir tmp: File) {
        val file = SileroVadLocator.modelFile(tmp)
        file.parentFile?.mkdirs()
        val bytes = "a real model".toByteArray()
        file.writeBytes(bytes)
        org.ort.core.assets.ModelFileVerifier.sizeMarkerFile(file).writeText(bytes.size.toString())
        org.ort.core.assets.ModelFileVerifier.sha256MarkerFile(file).writeText(sha256(bytes))

        var callCount = 0
        var closed = false
        RealVadProvider.nativeLoader = { path ->
            callCount++
            assertEquals(file.path, path)
            ({ _: FloatArray -> 1f }) to { closed = true }
        }

        val result = RealVadProvider.provide(tmp)

        assertEquals(1, callCount)
        assertTrue(result is VadProvisionResult.Available)
        (result as VadProvisionResult.Available).close()
        assertTrue(closed, "the real close callback must run")
    }

    // ---- Register R-1058 (spec): a verification failure must reach DiagnosticsLog, not just the
    // in-memory Unavailable reason -- once per launch, naming the closed-vocabulary asset id and
    // failure kind, never free text. -------------------------------------------------------------

    @Test
    fun `R_1058 an unverified VAD file logs one model_verification_failed event naming VAD and its kind`(
        @TempDir tmp: File,
    ) {
        DiagnosticsLog.configure(tmp, TestClock())
        val file = SileroVadLocator.modelFile(tmp)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(64)) // no markers at all -> MISSING_RECORD

        RealVadProvider.provide(tmp)
        runBlocking { DiagnosticsLog.flush() }

        val written = capturePipelineLog(tmp)
        assertEquals(1, written.size, "expected exactly one event, got: $written")
        assertTrue(written[0].contains(DiagnosticsLog.EVENT_MODEL_VERIFICATION_FAILED))
        assertTrue(written[0].contains("assetId=${ModelAssetId.VAD.name}"))
        assertTrue(written[0].contains("kind=MISSING_RECORD"))
    }

    @Test
    fun `R_1058 calling provide twice in one launch logs the failure only once`(@TempDir tmp: File) {
        DiagnosticsLog.configure(tmp, TestClock())
        val file = SileroVadLocator.modelFile(tmp)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(64))

        RealVadProvider.provide(tmp)
        RealVadProvider.provide(tmp)
        runBlocking { DiagnosticsLog.flush() }

        assertEquals(1, capturePipelineLog(tmp).size, "must log once per launch, not once per call")
    }

    // ---- Register R-1060 (process): a placeholder marker must win even over real-looking bytes
    // already sitting at the destination -- the shape of a no-token build's manifest marking VAD
    // missing while a stale real (or debug-fixture) file from an earlier build is still present. --

    @Test
    fun `R_1060 a placeholder-marked destination is refused without reaching native code, even with real bytes present`(
        @TempDir tmp: File,
    ) {
        val file = SileroVadLocator.modelFile(tmp)
        file.parentFile?.mkdirs()
        val bytes = "looks exactly like a real, previously verified model".toByteArray()
        file.writeBytes(bytes)
        org.ort.core.assets.ModelFileVerifier.recordVerifiedInstall(file, sha256(bytes), bytes.size.toLong())
        org.ort.core.assets.ModelFileVerifier.recordPlaceholder(file) // this build's manifest marks VAD missing

        var nativeLoaderCalled = false
        RealVadProvider.nativeLoader = { _ ->
            nativeLoaderCalled = true
            error("must never be reached — a placeholder must never reach native code")
        }

        val result = RealVadProvider.provide(tmp)

        assertFalse(nativeLoaderCalled)
        assertTrue(result is VadProvisionResult.Unavailable)
    }

    @Test
    fun `R_1058 a genuinely verified model logs no verification failure`(@TempDir tmp: File) {
        DiagnosticsLog.configure(tmp, TestClock())
        val file = SileroVadLocator.modelFile(tmp)
        file.parentFile?.mkdirs()
        val bytes = "a real model".toByteArray()
        file.writeBytes(bytes)
        org.ort.core.assets.ModelFileVerifier.sizeMarkerFile(file).writeText(bytes.size.toString())
        org.ort.core.assets.ModelFileVerifier.sha256MarkerFile(file).writeText(sha256(bytes))
        RealVadProvider.nativeLoader = { _ -> ({ _: FloatArray -> 1f }) to {} }

        RealVadProvider.provide(tmp)
        runBlocking { DiagnosticsLog.flush() }

        assertTrue(capturePipelineLog(tmp).isEmpty(), "a successful verification must log nothing")
    }
}
