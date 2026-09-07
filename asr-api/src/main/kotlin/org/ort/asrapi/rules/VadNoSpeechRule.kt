package org.ort.asrapi.rules

import org.ort.asrapi.SegmentCandidate

/**
 * Rule 2 (§8.2): a segment the VAD did not judge to contain speech is rejected before decode.
 * In the real pipeline the segmenter would not close such a segment at all (FR-SEG-1); this
 * rule exists for the case of a segment that reaches Pass B by another path (reprocessing,
 * manual re-run) still carrying a VAD verdict of "no speech" — and for AC-7's requirement that
 * every control be independently demonstrable with a crafted input.
 */
public class VadNoSpeechRule : PreDecodeRejectionRule {
    override val id: RejectionRuleId = RejectionRuleId.VAD_NO_SPEECH

    override fun evaluate(candidate: SegmentCandidate): RejectionVerdict = if (!candidate.vadDetectedSpeech) {
        RejectionVerdict.Reject(id, "VAD did not detect speech in this segment")
    } else {
        RejectionVerdict.Accept
    }
}
