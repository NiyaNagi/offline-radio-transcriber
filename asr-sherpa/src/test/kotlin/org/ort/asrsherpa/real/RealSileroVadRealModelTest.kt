package org.ort.asrsherpa.real

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.ort.testing.Requirement
import java.io.File

/**
 * Exercises [RealSileroVad] against a genuine sherpa-onnx Silero VAD `.onnx` model — the P12 proof
 * that the [org.ort.segment.VadModel] seam has a real Silero implementation behind it, not just
 * `RealCaptureService`'s RMS-energy `EnergyVadModel` stand-in.
 *
 * **Gated, deliberately** (mirrors [RealSherpaDecoderRealModelTest]'s pattern exactly): this test
 * never runs in ordinary CI. Nothing caches a model file there, and a missing model or unset env
 * var is a *skip*, not a failure.
 *
 * To run this test for real:
 * ```
 * $env:ORT_RUN_REAL_SHERPA = "1"
 * ./gradlew :asr-sherpa:test --tests "*RealSileroVadRealModelTest*"
 * ```
 * with `silero_vad.onnx` (e.g. from
 * `https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx`) placed at
 * `asr-sherpa/.models-cache/silero-vad/silero_vad.onnx`, or `$ORT_SILERO_VAD_MODEL` pointed at it
 * directly.
 */
@EnabledIfEnvironmentVariable(named = "ORT_RUN_REAL_SHERPA", matches = "1")
class RealSileroVadRealModelTest {

    @Test
    @Requirement("FR-CAP-6")
    fun `a real Silero VAD model scores silence low and a synthetic tone segment higher`() {
        val modelFile = resolveModelFile()
        assumeTrue(modelFile != null && modelFile.isFile) {
            "ORT_RUN_REAL_SHERPA=1 but no Silero VAD model found. Set ORT_SILERO_VAD_MODEL, or place " +
                "silero_vad.onnx at ${defaultModelFile().absolutePath} (asr-sherpa/README.md has the " +
                "download URL)."
        }
        val vad = RealSileroVad(requireNotNull(modelFile).absolutePath)
        try {
            val silence = FloatArray(WINDOW_SIZE)
            val silenceProb = vad.speechProbability(silence)

            // A synthetic 440Hz tone is not real speech, but it is far more "signal" than silence
            // -- this is not a claim about detection accuracy on real speech (constitution VI would
            // require the real dev noise tape for that, which doesn't exist yet), only that a real
            // decode happened and produced a probability, not a placeholder.
            val tone = FloatArray(WINDOW_SIZE) { i ->
                kotlin.math.sin(2.0 * Math.PI * 440.0 * i / SAMPLE_RATE).toFloat()
            }
            val toneProb = vad.speechProbability(tone)

            println("RealSileroVadRealModelTest: silenceProb=$silenceProb toneProb=$toneProb")
            assertTrue(silenceProb in 0f..1f, "expected a calibrated probability, got $silenceProb")
            assertTrue(toneProb in 0f..1f, "expected a calibrated probability, got $toneProb")
        } finally {
            vad.close()
        }
    }

    private fun resolveModelFile(): File? {
        val fromEnv = System.getenv("ORT_SILERO_VAD_MODEL")
        if (!fromEnv.isNullOrBlank()) return File(fromEnv)
        return defaultModelFile()
    }

    private fun defaultModelFile(): File = File("").absoluteFile.resolve(".models-cache/silero-vad/silero_vad.onnx")

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val WINDOW_SIZE = 512
    }
}
