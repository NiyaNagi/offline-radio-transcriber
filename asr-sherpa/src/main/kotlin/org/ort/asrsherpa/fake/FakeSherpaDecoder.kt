package org.ort.asrsherpa.fake

import org.ort.asrapi.DecodeOptions
import org.ort.asrsherpa.DecodedHypothesis
import org.ort.asrsherpa.SherpaDecoder

/**
 * The behavioural fake for [SherpaDecoder] (constitution II): scriptable to a fixed decode, or
 * to crash, so [org.ort.asrsherpa.SherpaAsrEngine]'s plumbing is testable without a real
 * sherpa-onnx binding.
 */
public class FakeSherpaDecoder(private val behaviour: Behaviour = Behaviour.Returns(defaultHypothesis())) :
    SherpaDecoder {

    public sealed interface Behaviour {
        public data class Returns(val hypothesis: DecodedHypothesis) : Behaviour
        public data class Crashes(val error: Throwable) : Behaviour
    }

    override fun decode(audio: FloatArray, opts: DecodeOptions): DecodedHypothesis = when (behaviour) {
        is Behaviour.Returns -> behaviour.hypothesis
        is Behaviour.Crashes -> throw behaviour.error
    }

    public companion object {
        public fun defaultHypothesis(text: String = "test transmission received"): DecodedHypothesis =
            DecodedHypothesis(
                text = text,
                nBest = emptyList(),
                noSpeechProb = 0.05f,
                avgLogProb = -0.2f,
                tokens = emptyList(),
            )
    }
}
