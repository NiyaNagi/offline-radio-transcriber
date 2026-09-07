package org.ort.asrsherpa.real

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.ort.asrapi.DecodeOptions
import org.ort.testing.Requirement
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Exercises [RealSherpaDecoder] against a genuine sherpa-onnx `OfflineRecognizer` loading a
 * genuine ONNX Whisper (`tiny.en`) model and transcribing a genuine held-out speech clip — this
 * is the P10-follow-up proof that the [org.ort.asrsherpa.SherpaDecoder] seam has a real
 * implementation behind it, not just [org.ort.asrsherpa.fake.FakeSherpaDecoder].
 *
 * **Gated, deliberately** (mirrors `corpus/tests/test_probe_lora_export.py`'s `ORT_RUN_REAL_R1`
 * pattern, adapted to JUnit5): this test never runs in ordinary CI. Nothing caches a ~120MB
 * model there, and the point of the gate is that a missing model or unset env var is a *skip*,
 * not a failure — see `asr-sherpa/README.md` for exactly what to set to make it run for real.
 *
 * To run this test for real:
 * ```
 * $env:ORT_RUN_REAL_SHERPA = "1"
 * ./gradlew :asr-sherpa:test --tests "*RealSherpaDecoderRealModelTest*"
 * ```
 * with the model extracted under `asr-sherpa/.models-cache/sherpa-onnx-whisper-tiny.en/` (the
 * default this test looks for) or `$ORT_SHERPA_MODEL_DIR` pointed at wherever it was extracted.
 */
@EnabledIfEnvironmentVariable(named = "ORT_RUN_REAL_SHERPA", matches = "1")
class RealSherpaDecoderRealModelTest {

    @Test
    @Requirement("FR-ASR-1")
    fun `a real sherpa-onnx whisper model transcribes a real clip into recognizable words`() {
        val modelDir = resolveModelDir()
        assumeTrue(modelDir != null && modelDir.isDirectory) {
            "ORT_RUN_REAL_SHERPA=1 but no model directory found. Set ORT_SHERPA_MODEL_DIR, or " +
                "extract sherpa-onnx-whisper-tiny.en into ${defaultModelDir().absolutePath} " +
                "(asr-sherpa/README.md has the download command)."
        }
        val dir = requireNotNull(modelDir)
        val encoder = File(dir, "tiny.en-encoder.int8.onnx")
        val decoderModel = File(dir, "tiny.en-decoder.int8.onnx")
        val tokens = File(dir, "tiny.en-tokens.txt")
        val wav = File(dir, "test_wavs/0.wav")
        assumeTrue(encoder.isFile && decoderModel.isFile && tokens.isFile && wav.isFile) {
            "model directory $dir is missing one of the expected files " +
                "(tiny.en-{encoder,decoder}.int8.onnx, tiny.en-tokens.txt, test_wavs/0.wav)"
        }

        val decoder = RealSherpaDecoder(
            encoderOnnxPath = encoder.absolutePath,
            decoderOnnxPath = decoderModel.absolutePath,
            tokensPath = tokens.absolutePath,
        )
        try {
            val audio = readPcm16MonoWav(wav)

            val hypothesis = decoder.decode(audio, DecodeOptions())
            println("RealSherpaDecoderRealModelTest transcribed: \"${hypothesis.text}\"")

            // Real, non-degenerate text: not empty, not garbage, and containing the substance of
            // the actual reference transcript for this clip (test_wavs/trans.txt, ground truth):
            // "AFTER EARLY NIGHTFALL THE YELLOW LAMPS WOULD LIGHT UP HERE AND THERE THE SQUALID
            // QUARTER OF THE BROTHELS". This is not a claim about accuracy (constitution VI) —
            // it is a claim that a real decode happened, on real audio, through a real binding.
            val normalized = hypothesis.text.lowercase()
            assertTrue(hypothesis.text.isNotBlank(), "expected real transcribed text, got blank")
            assertTrue(normalized.contains("nightfall"), "expected 'nightfall' in: ${hypothesis.text}")
            assertTrue(normalized.contains("lamps"), "expected 'lamps' in: ${hypothesis.text}")
            assertTrue(hypothesis.tokens.isNotEmpty(), "expected per-token timing to be populated")
        } finally {
            decoder.close()
        }
    }

    private fun resolveModelDir(): File? {
        val fromEnv = System.getenv("ORT_SHERPA_MODEL_DIR")
        if (!fromEnv.isNullOrBlank()) return File(fromEnv)
        return defaultModelDir()
    }

    private fun defaultModelDir(): File =
        File("").absoluteFile.resolve(".models-cache/sherpa-onnx-whisper-tiny.en")

    /** Minimal PCM16 mono WAV reader — good enough for this fixture; no new module dependency. */
    private fun readPcm16MonoWav(file: File): FloatArray {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(12)
            raf.readFully(header)
            require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF") { "not a RIFF file: $file" }
            require(String(header, 8, 4, Charsets.US_ASCII) == "WAVE") { "not a WAVE file: $file" }

            var dataBytes: ByteArray? = null
            while (raf.filePointer < raf.length()) {
                val chunkHeader = ByteArray(8)
                raf.readFully(chunkHeader)
                val id = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                val size = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (id == "data") {
                    dataBytes = ByteArray(size)
                    raf.readFully(dataBytes)
                } else {
                    raf.seek(raf.filePointer + size + (size % 2))
                }
            }
            val bytes = requireNotNull(dataBytes) { "no data chunk in $file" }
            val samples = FloatArray(bytes.size / 2)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in samples.indices) {
                samples[i] = buffer.short / SHORT_MAX
            }
            return samples
        }
    }

    private companion object {
        const val SHORT_MAX = 32768f
    }
}
