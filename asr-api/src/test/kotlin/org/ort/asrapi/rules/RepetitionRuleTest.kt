package org.ort.asrapi.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.ort.asrapi.AsrResult
import org.ort.core.AssetRef
import org.ort.testing.Requirement

class RepetitionRuleTest {

    private fun result(text: String) = AsrResult(
        text = text,
        nBest = emptyList(),
        noSpeechProb = 0.05f,
        avgLogProb = -0.1f,
        tokens = emptyList(),
        modelRef = AssetRef("m", "1"),
    )

    @Test
    @Requirement("AC-7", "FR-ASR-5")
    fun `AC_7 repetition rejects a 4-gram repeated at least three times`() {
        val rule = RepetitionRule()
        val verdict = rule.evaluate(result("go go go go go go go go go go go go"))
        assertEquals(RejectionRuleId.REPETITION, (verdict as RejectionVerdict.Reject).rule)
    }

    @Test
    @Requirement("AC-7")
    fun `ordinary speech-shaped text is accepted`() {
        val rule = RepetitionRule()
        val verdict = rule.evaluate(result("this is kilo seven able baker charlie do you copy"))
        assertEquals(RejectionVerdict.Accept, verdict)
    }
}
