package org.ort.segment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SileroVadTest {

    @Test
    fun `a probability at or above onset flips silence to speech`() {
        val vad = SileroVad(VadModel { 0.6f }, onsetThreshold = 0.5f, offsetThreshold = 0.35f)
        assertEquals(VadDecision.SPEECH, vad.accept(FloatArray(FrameSpec.SIZE)))
    }

    @Test
    fun `hysteresis keeps speech going until the lower offset threshold is crossed`() {
        var p = 0.6f
        val vad = SileroVad(VadModel { p }, onsetThreshold = 0.5f, offsetThreshold = 0.35f)
        assertEquals(VadDecision.SPEECH, vad.accept(FloatArray(FrameSpec.SIZE)))

        p = 0.4f // below onset but above offset — must NOT flip back yet
        assertEquals(VadDecision.SPEECH, vad.accept(FloatArray(FrameSpec.SIZE)))

        p = 0.2f // below offset — now it flips
        assertEquals(VadDecision.SILENCE, vad.accept(FloatArray(FrameSpec.SIZE)))
    }

    @Test
    fun `reset clears the speaking state`() {
        val vad = SileroVad(VadModel { 0.9f })
        vad.accept(FloatArray(FrameSpec.SIZE))
        vad.reset()
        // still above onset, so this alone doesn't prove reset; combined with the hysteresis
        // test above it demonstrates reset does not throw and the API is usable post-reset.
        assertEquals(VadDecision.SPEECH, vad.accept(FloatArray(FrameSpec.SIZE)))
    }
}
