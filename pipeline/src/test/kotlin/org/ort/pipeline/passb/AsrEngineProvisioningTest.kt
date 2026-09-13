package org.ort.pipeline.passb

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.ort.asrapi.DecodeOptions
import org.ort.core.assets.ModelFileVerifier
import org.ort.pipeline.diagnostics.DiagnosticsLog
import org.ort.pipeline.diagnostics.ModelAssetId
import org.ort.testing.TestClock
import java.io.File
import java.security.MessageDigest

/**
 * Build-plan P12: "an honest failure if it cannot [fetch the model]" -- do NOT let it silently
 * look like transcription is working when no model is present. These tests pin that the app-private
 * model-residency check is honest (never a stand-in) and that [UnavailableAsrEngine] never
 * silently returns text.
 */
class AsrEngineProvisioningTest {

    @AfterEach
    fun tearDown() {
        DiagnosticsLog.shutdown()
    }

    private fun capturePipelineLog(filesDir: File): List<String> {
        val file = File(File(filesDir, "diagnostics-logs"), DiagnosticsLog.Category.PIPELINE.fileName)
        return if (file.isFile) file.readLines() else emptyList()
    }

    /** Writes an unverifiable 64-byte stub at [file]'s exact path — no `.sha256`/`.size` sidecars
     * at all, the shape R-1052's own scenario-fixture corruption takes. */
    private fun writeCorrupt(file: File) {
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(64))
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Writes a real, verifiable model at [file]'s exact path — the shape a genuinely completed,
     * verified [org.ort.app.assets.BundledAssetInstaller] install leaves behind. */
    private fun writeVerified(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        ModelFileVerifier.sizeMarkerFile(file).writeText(bytes.size.toString())
        ModelFileVerifier.sha256MarkerFile(file).writeText(sha256(bytes))
    }

    @Test
    fun `locate returns null when the model directory does not exist at all`(@TempDir tmp: File) {
        assertNull(AsrModelLocator.locate(tmp))
    }

    @Test
    fun `locate returns null when only some of the three required files are present`(@TempDir tmp: File) {
        val dir = AsrModelLocator.modelsDir(tmp)
        dir.mkdirs()
        File(dir, "tiny.en-encoder.int8.onnx").writeText("not a real model")
        // decoder and tokens missing -- a partial fetch must not be treated as a usable model.
        assertNull(AsrModelLocator.locate(tmp))
    }

    @Test
    fun `provide reports Unavailable, with a reason naming the expected path, when no model is installed`(
        @TempDir tmp: File,
    ) {
        val result = RealAsrEngineProvider(tmp).provide()
        assertTrue(result is AsrEngineAvailability.Unavailable)
        val reason = (result as AsrEngineAvailability.Unavailable).reason
        assertTrue(
            reason.contains(AsrModelLocator.modelsDir(tmp).path),
            "reason should name the expected model path so a person can act on it, got: $reason",
        )
    }

    @Test
    fun `UnavailableAsrEngine never silently returns text -- it always fails loudly`() = runTest {
        val engine = UnavailableAsrEngine("no model installed")
        val error = kotlin.runCatching { engine.transcribe(FloatArray(10), DecodeOptions()) }.exceptionOrNull()
        assertTrue(error != null && error.message!!.contains("ASR unavailable"))
    }

    // ---- Register R-1052 (halt): a validator's fresh install died with SIGABRT the moment a
    // debug-fixture stub sat at these three paths and `provide()` reached the native decoder
    // constructor -- no Kotlin `catch (t: Throwable)` can stop the C++ exception that follows. The
    // three tests below use [RealAsrEngineProvider]'s `nativeLoader` seam to prove the native
    // constructor is never reached once any one of the three files fails verification, without
    // ever loading a real native library. ------------------------------------------------------

    @Test
    fun `R_1052 a 64-byte stub with no verified-install record never reaches the native loader`(@TempDir tmp: File) {
        val dir = AsrModelLocator.modelsDir(tmp)
        dir.mkdirs()
        File(dir, "tiny.en-encoder.int8.onnx").writeBytes(ByteArray(64))
        File(dir, "tiny.en-decoder.int8.onnx").writeBytes(ByteArray(64))
        File(dir, "tiny.en-tokens.txt").writeBytes(ByteArray(64))
        // No .size/.sha256 sidecars at all -- exactly ScenarioFixtures.installModelFixture's shape
        // for the encoder/decoder (only tokens gets a marker in that fixture; here none do, which
        // must still be refused for every one of the three files).

        var nativeLoaderCalled = false
        val provider = RealAsrEngineProvider(tmp) {
            nativeLoaderCalled = true
            error("must never be reached")
        }

        val result = provider.provide()

        assertFalse(nativeLoaderCalled, "the native decoder must never be constructed for unverified files")
        assertTrue(result is AsrEngineAvailability.Unavailable)
    }

    @Test
    fun `R_1052 one unverified file among three verified ones still refuses to load`(@TempDir tmp: File) {
        val dir = AsrModelLocator.modelsDir(tmp)
        val encoder = File(dir, "tiny.en-encoder.int8.onnx")
        val decoder = File(dir, "tiny.en-decoder.int8.onnx")
        val tokens = File(dir, "tiny.en-tokens.txt")
        writeVerified(encoder, "encoder bytes".toByteArray())
        writeVerified(decoder, "decoder bytes".toByteArray())
        tokens.parentFile?.mkdirs()
        tokens.writeBytes(ByteArray(64)) // corrupted, no markers

        var nativeLoaderCalled = false
        val provider = RealAsrEngineProvider(tmp) {
            nativeLoaderCalled = true
            error("must never be reached")
        }

        val result = provider.provide()

        assertFalse(nativeLoaderCalled)
        assertTrue(result is AsrEngineAvailability.Unavailable)
        assertTrue((result as AsrEngineAvailability.Unavailable).reason.contains(tokens.name))
    }

    @Test
    fun `R_1052 three genuinely verified files reach the native loader exactly once`(@TempDir tmp: File) {
        val dir = AsrModelLocator.modelsDir(tmp)
        val encoder = File(dir, "tiny.en-encoder.int8.onnx")
        val decoder = File(dir, "tiny.en-decoder.int8.onnx")
        val tokens = File(dir, "tiny.en-tokens.txt")
        writeVerified(encoder, "encoder bytes".toByteArray())
        writeVerified(decoder, "decoder bytes".toByteArray())
        writeVerified(tokens, "tokens bytes".toByteArray())

        var callCount = 0
        var closed = false
        val fakeDecoder = org.ort.asrsherpa.SherpaDecoder { _, _ ->
            org.ort.asrsherpa.DecodedHypothesis(
                text = "fake",
                nBest = emptyList(),
                noSpeechProb = null,
                avgLogProb = null,
                tokens = emptyList(),
            )
        }
        val provider = RealAsrEngineProvider(tmp) { files ->
            callCount++
            assertEquals(encoder.path, files.encoder.path)
            fakeDecoder to AutoCloseable { closed = true }
        }

        val result = provider.provide()

        assertEquals(1, callCount)
        assertTrue(result is AsrEngineAvailability.Available, "expected Available, got $result")
        (result as AsrEngineAvailability.Available).engine // touch it, no exception expected
        assertFalse(closed, "close must not run just from a successful provide()")
    }

    // ---- Register R-1058 (spec): each of the three ASR files is verified independently and must
    // log its own asset id -- never a single generic "ASR" event that hides which file failed. ---

    @Test
    fun `R_1058 a bad tokens file among two verified ones logs ASR_TOKENS, not the other two`(@TempDir tmp: File) {
        DiagnosticsLog.configure(tmp, TestClock())
        val dir = AsrModelLocator.modelsDir(tmp)
        writeVerified(File(dir, "tiny.en-encoder.int8.onnx"), "encoder bytes".toByteArray())
        writeVerified(File(dir, "tiny.en-decoder.int8.onnx"), "decoder bytes".toByteArray())
        writeCorrupt(File(dir, "tiny.en-tokens.txt")) // no markers

        RealAsrEngineProvider(tmp).provide()
        runBlocking { DiagnosticsLog.flush() }

        val written = capturePipelineLog(tmp)
        assertEquals(1, written.size, "only the one bad file must be logged, got: $written")
        assertTrue(written[0].contains("assetId=${ModelAssetId.ASR_TOKENS.name}"))
        assertTrue(written[0].contains("kind=MISSING_RECORD"))
    }

    @Test
    fun `R_1058 a bad encoder file logs ASR_ENCODER`(@TempDir tmp: File) {
        DiagnosticsLog.configure(tmp, TestClock())
        val dir = AsrModelLocator.modelsDir(tmp)
        writeCorrupt(File(dir, "tiny.en-encoder.int8.onnx"))
        writeVerified(File(dir, "tiny.en-decoder.int8.onnx"), "decoder bytes".toByteArray())
        writeVerified(File(dir, "tiny.en-tokens.txt"), "tokens bytes".toByteArray())

        RealAsrEngineProvider(tmp).provide()
        runBlocking { DiagnosticsLog.flush() }

        val written = capturePipelineLog(tmp)
        assertEquals(1, written.size)
        assertTrue(written[0].contains("assetId=${ModelAssetId.ASR_ENCODER.name}"))
    }

    @Test
    fun `R_1058 three genuinely verified files log no verification failure`(@TempDir tmp: File) {
        DiagnosticsLog.configure(tmp, TestClock())
        val dir = AsrModelLocator.modelsDir(tmp)
        val encoder = File(dir, "tiny.en-encoder.int8.onnx")
        val decoder = File(dir, "tiny.en-decoder.int8.onnx")
        val tokens = File(dir, "tiny.en-tokens.txt")
        writeVerified(encoder, "encoder bytes".toByteArray())
        writeVerified(decoder, "decoder bytes".toByteArray())
        writeVerified(tokens, "tokens bytes".toByteArray())
        val fakeDecoder = org.ort.asrsherpa.SherpaDecoder { _, _ ->
            org.ort.asrsherpa.DecodedHypothesis(
                text = "fake",
                nBest = emptyList(),
                noSpeechProb = null,
                avgLogProb = null,
                tokens = emptyList(),
            )
        }

        RealAsrEngineProvider(tmp) { files -> fakeDecoder to AutoCloseable {} }.provide()
        runBlocking { DiagnosticsLog.flush() }

        assertTrue(capturePipelineLog(tmp).isEmpty())
    }
}
