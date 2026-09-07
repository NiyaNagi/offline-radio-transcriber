package org.ort.asrapi.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.ort.asrapi.AsrResult
import org.ort.core.AssetRef
import org.ort.testing.Requirement

class CompressionRatioRuleTest {

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
    fun `AC_7 compression_ratio rejects highly repetitive degenerate text`() {
        val rule = CompressionRatioRule(ceiling = 2.4)
        val degenerate = "the ".repeat(400)
        val verdict = rule.evaluate(result(degenerate))
        assertEquals(RejectionRuleId.COMPRESSION_RATIO, (verdict as RejectionVerdict.Reject).rule)
    }

    @Test
    @Requirement("AC-7")
    fun `natural varied speech text is accepted`() {
        val rule = CompressionRatioRule(ceiling = 2.4)
        val natural = "kilo seven able baker this is whiskey seven x-ray yankee zulu do you copy over"
        assertEquals(RejectionVerdict.Accept, rule.evaluate(result(natural)))
    }
}
