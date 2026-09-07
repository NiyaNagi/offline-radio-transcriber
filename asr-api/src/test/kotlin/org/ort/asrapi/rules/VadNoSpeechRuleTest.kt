package org.ort.asrapi.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.ort.asrapi.SegmentCandidate
import org.ort.testing.Requirement

class VadNoSpeechRuleTest {

    @Test
    @Requirement("AC-7", "FR-ASR-5")
    fun `AC_7 vad_no_speech rejects a segment the VAD did not mark as speech`() {
        val rule = VadNoSpeechRule()
        val verdict = rule.evaluate(SegmentCandidate(durationMs = 2000, vadDetectedSpeech = false))
        assertEquals(RejectionRuleId.VAD_NO_SPEECH, (verdict as RejectionVerdict.Reject).rule)
    }

    @Test
    @Requirement("AC-7")
    fun `a VAD-positive segment is accepted by this rule`() {
        val rule = VadNoSpeechRule()
        val verdict = rule.evaluate(SegmentCandidate(durationMs = 2000, vadDetectedSpeech = true))
        assertEquals(RejectionVerdict.Accept, verdict)
    }
}
