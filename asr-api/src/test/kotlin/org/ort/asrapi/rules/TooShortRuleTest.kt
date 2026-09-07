package org.ort.asrapi.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.ort.asrapi.SegmentCandidate
import org.ort.testing.Requirement

class TooShortRuleTest {

    @Test
    @Requirement("AC-7", "FR-ASR-5")
    fun `AC_7 too_short rejects a segment below the floor with no model invoked`() {
        val rule = TooShortRule(minDurationMs = 250)
        val verdict = rule.evaluate(SegmentCandidate(durationMs = 120, vadDetectedSpeech = true))
        assertEquals(RejectionRuleId.TOO_SHORT, (verdict as RejectionVerdict.Reject).rule)
    }

    @Test
    @Requirement("AC-7")
    fun `a segment at or above the floor is accepted by this rule`() {
        val rule = TooShortRule(minDurationMs = 250)
        val verdict = rule.evaluate(SegmentCandidate(durationMs = 250, vadDetectedSpeech = true))
        assertEquals(RejectionVerdict.Accept, verdict)
    }
}
