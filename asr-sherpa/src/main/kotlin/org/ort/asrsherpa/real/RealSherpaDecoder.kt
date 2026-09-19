package org.ort.asrsherpa.real

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import org.ort.asrapi.DecodeOptions
import org.ort.asrapi.Hypothesis
import org.ort.asrapi.TokenScore
import org.ort.asrsherpa.DecodedHypothesis
import org.ort.asrsherpa.SherpaDecoder

/**
 * The real [SherpaDecoder]: a genuine sherpa-onnx `OfflineRecognizer` (JNI binding, JVM/desktop
 * `sherpa-onnx-jvm` artifact — see `asr-sherpa/README.md` for exactly where that binding comes
 * from and how it is resolved) running a genuine Whisper-family ONNX model on CPU.
 *
 * This is the counterpart to [org.ort.asrsherpa.fake.FakeSherpaDecoder] promised in P10: that
 * fake remains the one every other test in this module uses; this class exists so the seam has
 * at least one real implementation behind it, exercised by
 * `RealSherpaDecoderRealModelTest` (gated — see that file and `asr-sherpa/README.md`).
 *
 * **Register R-1012 (correction — this doc comment previously claimed the opposite): this class
 * IS wired into [org.ort.asrsherpa.SherpaAsrEngine] by a real production entry point.**
 * `:pipeline`'s `org.ort.pipeline.passb.RealAsrEngineProvider.provide()` constructs it directly
 * (its own `nativeLoader` default) whenever [org.ort.pipeline.passb.AsrModelLocator.locate] finds
 * all three model files under app-private storage and [org.ort.core.assets.ModelFileVerifier]
 * passes every one of them — a stale comment here is exactly how R-1001 (this decoder never
 * actually decoding on-device transcription) went unnoticed for as long as it did. What genuinely
 * remains out of scope is only *getting the model files there in the first place*: asset
 * installation/activation for a real Whisper model (FR-ASR-8, FR-AST-2) still fetches nothing
 * automatically (see `RealAsrEngineProvider`'s own class doc and this task's original CHANGELOG
 * entry) — an operator or a test must place the three files under
 * `AsrModelLocator.modelsDir(filesDir)` themselves. The gated real-model test still constructs
 * this class directly, from explicit file paths, independent of that provider.
 *
 * @param encoderOnnxPath path to the Whisper encoder `.onnx` file.
 * @param decoderOnnxPath path to the Whisper decoder `.onnx` file.
 * @param tokensPath path to the model's `tokens.txt`.
 * @param numThreads CPU threads sherpa-onnx's ONNX Runtime session uses. Kept small and explicit
 *   — this runs on the same device budget as everything else in the tier (FR-TIER-8).
 */
public class RealSherpaDecoder(
    encoderOnnxPath: String,
    decoderOnnxPath: String,
    tokensPath: String,
    numThreads: Int = 1,
) : SherpaDecoder,
    AutoCloseable {

    private val recognizer: OfflineRecognizer = OfflineRecognizer(
        OfflineRecognizerConfig.builder()
            .setOfflineModelConfig(
                OfflineModelConfig.builder()
                    .setWhisper(
                        OfflineWhisperModelConfig.builder()
                            .setEncoder(encoderOnnxPath)
                            .setDecoder(decoderOnnxPath)
                            .build(),
                    )
                    .setTokens(tokensPath)
                    .setNumThreads(numThreads)
                    .setDebug(false)
                    .setModelType("whisper")
                    .build(),
            )
            .build(),
    )

    override fun decode(audio: FloatArray, opts: DecodeOptions): DecodedHypothesis {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(audio, SAMPLE_RATE_HZ)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            val text = result.text
            val tokens = decodeTokens(result)
            return DecodedHypothesis(
                text = text,
                nBest = listOf(Hypothesis(text = text, logProb = 0f, tokens = tokens)).takeIf { opts.nBest > 0 }
                    ?: emptyList(),
                noSpeechProb = null,
                avgLogProb = null,
                tokens = tokens,
            )
        } finally {
            stream.release()
        }
    }

    /** Releases the native recognizer. Callers own this lifecycle. */
    override fun close() {
        recognizer.release()
    }

    private fun decodeTokens(result: OfflineRecognizerResult): List<TokenScore> {
        val tokenTexts = result.tokens ?: return emptyList()
        val timestamps = result.timestamps
        val durations = result.durations
        return tokenTexts.mapIndexed { i, token ->
            val startMs = timestamps?.getOrNull(i)?.let { (it * MILLIS_PER_SECOND).toInt() } ?: 0
            val durationMs = durations?.getOrNull(i)?.let { (it * MILLIS_PER_SECOND).toInt() } ?: 0
            TokenScore(token = token, logProb = 0f, startMs = startMs, endMs = startMs + durationMs)
        }
    }

    public companion object {
        /** The pipeline's one fixed sample rate (`SampleClock.DEFAULT_SAMPLE_RATE`, FR-RUN-16). */
        public const val SAMPLE_RATE_HZ: Int = 16_000
        private const val MILLIS_PER_SECOND: Float = 1000f
    }
}
