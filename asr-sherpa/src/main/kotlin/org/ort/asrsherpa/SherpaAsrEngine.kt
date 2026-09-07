package org.ort.asrsherpa

import org.ort.asrapi.AsrEngine
import org.ort.asrapi.AsrResult
import org.ort.asrapi.DecodeOptions
import org.ort.core.AssetRef
import org.ort.onnx.OnnxSession

/**
 * `AsrEngine` over a sherpa-onnx offline recognizer session (technical design §8.1, Pass B).
 *
 * **What is real here and what is not:** the JVM/JNI binding to sherpa-onnx's offline decoder
 * is not available/verifiable in this build environment (no downloadable ONNX Whisper/
 * distil-whisper export, no confirmed sherpa-onnx JVM artifact resolvable here — see this
 * prompt's CHANGELOG entry). [SherpaDecoder] is the seam a real binding plugs into: it is the
 * one method a native sherpa-onnx `OfflineRecognizer.decode()` call would implement. This class
 * — the [OnnxSession] lifecycle, error mapping, and [AsrResult] shape — is real and tested
 * against [org.ort.asrsherpa.fake.FakeSherpaDecoder]; only the native decode body is a stand-in.
 */
public class SherpaAsrEngine(private val session: OnnxSession, private val decoder: SherpaDecoder) : AsrEngine {

    override suspend fun transcribe(audio: FloatArray, opts: DecodeOptions): AsrResult {
        check(!session.isClosed) { "transcribe() called on a closed session for ${session.descriptor.assetRef}" }
        val decoded = decoder.decode(audio, opts)
        return AsrResult(
            text = decoded.text,
            nBest = decoded.nBest,
            noSpeechProb = decoded.noSpeechProb,
            avgLogProb = decoded.avgLogProb,
            tokens = decoded.tokens,
            modelRef = session.descriptor.assetRef,
        )
    }
}

/** The native decode call a real sherpa-onnx binding implements. See class doc on [SherpaAsrEngine]. */
public fun interface SherpaDecoder {
    public fun decode(audio: FloatArray, opts: DecodeOptions): DecodedHypothesis
}

/** The raw decode output before [SherpaAsrEngine] stamps it with the session's [AssetRef]. */
public data class DecodedHypothesis(
    val text: String,
    val nBest: List<org.ort.asrapi.Hypothesis>,
    val noSpeechProb: Float?,
    val avgLogProb: Float?,
    val tokens: List<org.ort.asrapi.TokenScore>,
)
