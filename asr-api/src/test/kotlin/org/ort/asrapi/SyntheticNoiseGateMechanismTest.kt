package org.ort.asrapi

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.asrapi.fake.FakeAsrEngine

/**
 * **This test does NOT establish AC-6.**
 *
 * AC-6 requires the **real development noise tape** (spec/open-questions.md Q2/Q16, functional
 * spec §14A.3/§7.3 note, test-plan §7's device recording session) — roughly 20 minutes of real
 * squelch tails from the TH-D75A and SDS150 with no speech — run through the **real** ASR engine
 * (`:asr-sherpa` over sherpa-onnx with a real Whisper/distil-whisper export). As of this change:
 *
 * - The development noise tape has not been recorded. `spec/open-questions.md` still lists Q2
 *   ("record the tape") and Q16 (the labelling protocol that gates it) as the two open questions
 *   in the entire specification.
 * - No real ONNX ASR model is available/downloadable in this sandbox for `:asr-sherpa` to run.
 *
 * Fabricating "zero accepted transcripts" against a synthetic stand-in and reporting it as AC-6
 * would be exactly the kind of confident, silently-wrong number constitution VI forbids ("no
 * number without its fold, machine and provider"). What this test *does* show, honestly: given
 * a set of decoded results **shaped like** what a real ASR model tends to emit over pure noise
 * (empty/near-empty text, high `no_speech_prob`, degenerate repetition, or the exact documented
 * Whisper hallucination phrases) run through [RejectionPipeline] backed by [FakeAsrEngine], the
 * six-control mechanism rejects all of them and none is silently accepted. That is a genuine,
 * valuable partial result about the *mechanism* — it is not a measurement of the product.
 *
 * A real AC-6 run needs, at minimum: the recorded tape (Q2), the labelling protocol (Q16) is
 * not actually required for AC-6 itself since it measures *zero accepted transcripts*, not
 * transcript accuracy, and a real ONNX Whisper/distil-whisper export runnable from JVM
 * sherpa-onnx bindings, wired through `:asr-sherpa`'s real `AsrEngine` implementation.
 */
class SyntheticNoiseGateMechanismTest {

    /**
     * Hand-crafted decode outputs of the shape a real ASR model plausibly emits when fed pure
     * squelch/noise with no speech: near-silence with a high no_speech_prob, empty text, a
     * degenerate repeated token, and a known Whisper hallucination artifact phrase. None of this
     * audio was recorded from a real radio; it is a synthetic stand-in, used only to prove the
     * rejection mechanism has no gap an obviously-noise-shaped decode could slip through.
     */
    private fun syntheticNoiseLikeDecodes(): List<Pair<String, FakeAsrEngine.Behaviour.Returns>> = listOf(
        "high no_speech_prob, empty text" to FakeAsrEngine.Behaviour.Returns(
            FakeAsrEngine.defaultResult(text = "", noSpeechProb = 0.97f),
        ),
        "high no_speech_prob, near-empty text" to FakeAsrEngine.Behaviour.Returns(
            FakeAsrEngine.defaultResult(text = ".", noSpeechProb = 0.88f),
        ),
        "documented Whisper hallucination artifact over silence" to FakeAsrEngine.Behaviour.Returns(
            FakeAsrEngine.defaultResult(text = "Thanks for watching!", noSpeechProb = 0.75f),
        ),
        "degenerate repetition over a squelch tail" to FakeAsrEngine.Behaviour.Returns(
            FakeAsrEngine.defaultResult(text = "you you you you you you you you you you you you", noSpeechProb = 0.70f),
        ),
        "low-information filler the model sometimes emits over static" to FakeAsrEngine.Behaviour.Returns(
            FakeAsrEngine.defaultResult(
                text = "the the the the the the the the the the the the the the",
                noSpeechProb = 0.65f,
            ),
        ),
    )

    @Test
    fun `synthetic noise-shaped decodes are all rejected, zero accepted (NOT a substitute for AC-6, see class doc)`() =
        runTest {
            for ((label, behaviour) in syntheticNoiseLikeDecodes()) {
                val engine = FakeAsrEngine(behaviour)
                val pipeline = RejectionPipeline(engine)

                val outcome = pipeline.process(
                    SegmentCandidate(durationMs = 2000, vadDetectedSpeech = true),
                    FloatArray(32_000), // 2s of silence at 16kHz, all zeros — never inspected by the fake
                    DecodeOptions(),
                )

                assertTrue(outcome is PassBOutcome.Rejected, "expected a rejection for case: $label, got $outcome")
            }
        }

    @Test
    fun `a segment that never reaches the model at all (too-short, no-VAD-speech) is also zero-accepted`() = runTest {
        val engine = FakeAsrEngine() // would return a normal result if ever called
        val pipeline = RejectionPipeline(engine)

        val tooShort = pipeline.process(SegmentCandidate(0, vadDetectedSpeech = true), FloatArray(16), DecodeOptions())
        val noSpeechCandidate = SegmentCandidate(5000, vadDetectedSpeech = false)
        val noSpeech = pipeline.process(noSpeechCandidate, FloatArray(16), DecodeOptions())

        assertTrue(tooShort is PassBOutcome.Rejected)
        assertTrue(noSpeech is PassBOutcome.Rejected)
        assertEquals(0, engine.callCount)
    }
}
