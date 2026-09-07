package org.ort.asrapi.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.ort.asrapi.AsrResult
import org.ort.core.AssetRef
import org.ort.testing.Requirement

class BlocklistRuleTest {

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
    fun `AC_7 blocklist rejects a known Whisper hallucination phrase, case and punctuation insensitive`() {
        val rule = BlocklistRule()
        val verdict = rule.evaluate(result("Thanks for watching!"))
        assertEquals(RejectionRuleId.BLOCKLIST, (verdict as RejectionVerdict.Reject).rule)
    }

    @Test
    @Requirement("AC-7")
    fun `matching is normalised so a different case still matches`() {
        val rule = BlocklistRule()
        val verdict = rule.evaluate(result("thanks FOR watching"))
        assertEquals(RejectionRuleId.BLOCKLIST, (verdict as RejectionVerdict.Reject).rule)
    }

    @Test
    @Requirement("AC-7")
    fun `a user-extended blocklist entry is honoured`() {
        val rule = BlocklistRule(phrases = setOf("break break break"))
        val verdict = rule.evaluate(result("Break break break"))
        assertEquals(RejectionRuleId.BLOCKLIST, (verdict as RejectionVerdict.Reject).rule)
    }

    @Test
    fun `ordinary radio traffic is accepted`() {
        val rule = BlocklistRule()
        assertEquals(RejectionVerdict.Accept, rule.evaluate(result("K7ABC this is W7XYZ go ahead")))
    }
}
