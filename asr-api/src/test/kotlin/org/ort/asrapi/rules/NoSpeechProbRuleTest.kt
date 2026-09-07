package org.ort.asrapi.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.ort.asrapi.AsrResult
import org.ort.core.AssetRef
import org.ort.testing.Requirement

class NoSpeechProbRuleTest {

    private fun result(noSpeechProb: Float?) = AsrResult(
        text = "some text",
        nBest = emptyList(),
        noSpeechProb = noSpeechProb,
        avgLogProb = -0.1f,
        tokens = emptyList(),
        modelRef = AssetRef("m", "1"),
    )

    @Test
    @Requirement("AC-7", "FR-ASR-5")
    fun `AC_7 no_speech_prob rejects a decode above the ceiling`() {
        val rule = NoSpeechProbRule(ceiling = 0.60)
        val verdict = rule.evaluate(result(noSpeechProb = 0.95f))
        assertEquals(RejectionRuleId.NO_SPEECH_PROB, (verdict as RejectionVerdict.Reject).rule)
    }

    @Test
    @Requirement("AC-7")
    fun `a decode at or below the ceiling is accepted`() {
        val rule = NoSpeechProbRule(ceiling = 0.60)
        assertEquals(RejectionVerdict.Accept, rule.evaluate(result(noSpeechProb = 0.10f)))
    }

    @Test
    fun `a decode with no no_speech_prob at all is accepted by this rule (nothing to check)`() {
        val rule = NoSpeechProbRule()
        assertEquals(RejectionVerdict.Accept, rule.evaluate(result(noSpeechProb = null)))
    }
}
