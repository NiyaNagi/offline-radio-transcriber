package org.ort.asrapi.fake

import kotlinx.coroutines.delay
import org.ort.asrapi.AsrEngine
import org.ort.asrapi.AsrResult
import org.ort.asrapi.DecodeOptions
import org.ort.core.AssetRef

/**
 * The behavioural fake for [AsrEngine] (constitution II): scriptable to return a fixed result,
 * hang past any reasonable timeout, or throw — everything [org.ort.asrapi.RejectionPipeline]'s
 * timeout and failure paths need to be exercised deterministically, without a real model.
 */
public class FakeAsrEngine(private val behaviour: Behaviour = Behaviour.Returns(defaultResult())) : AsrEngine {

    public var callCount: Int = 0
        private set

    public sealed interface Behaviour {
        public data class Returns(val result: AsrResult) : Behaviour
        public data class HangsFor(val millis: Long) : Behaviour
        public data class Throws(val error: Throwable) : Behaviour
    }

    override suspend fun transcribe(audio: FloatArray, opts: DecodeOptions): AsrResult {
        callCount++
        return when (behaviour) {
            is Behaviour.Returns -> behaviour.result
            is Behaviour.HangsFor -> {
                delay(behaviour.millis)
                behaviour.result()
            }
            is Behaviour.Throws -> throw behaviour.error
        }
    }

    private fun Behaviour.HangsFor.result(): AsrResult = defaultResult()

    public companion object {
        public fun defaultResult(text: String = "test transmission received", noSpeechProb: Float = 0.05f): AsrResult =
            AsrResult(
                text = text,
                nBest = emptyList(),
                noSpeechProb = noSpeechProb,
                avgLogProb = -0.2f,
                tokens = emptyList(),
                modelRef = AssetRef("fake-asr-model", "1"),
            )
    }
}
