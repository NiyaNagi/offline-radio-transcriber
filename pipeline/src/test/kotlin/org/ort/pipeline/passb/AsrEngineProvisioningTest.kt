package org.ort.pipeline.passb

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.ort.asrapi.DecodeOptions
import java.io.File

/**
 * Build-plan P12: "an honest failure if it cannot [fetch the model]" -- do NOT let it silently
 * look like transcription is working when no model is present. These tests pin that the app-private
 * model-residency check is honest (never a stand-in) and that [UnavailableAsrEngine] never
 * silently returns text.
 */
class AsrEngineProvisioningTest {

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
}
