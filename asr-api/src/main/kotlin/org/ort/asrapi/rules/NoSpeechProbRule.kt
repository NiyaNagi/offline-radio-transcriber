package org.ort.asrapi.rules

import org.ort.asrapi.AsrResult

/**
 * Rule 3 (§8.2): rejects a decode whose `no_speech_prob` exceeds [ceiling]. Whisper's own
 * default is 0.60; the design requires this be **refitted against the development noise tape**
 * (§8.2, "Thresholds are fitted, not inherited") rather than adopted from upstream. As of this
 * change no development noise tape exists in this repository (Q2/Q16 — see spec/open-questions.md
 * and CHANGELOG.md), so [DEFAULT_CEILING] remains Whisper's convention, unrefitted, and this is
 * recorded rather than silently presented as measured (constitution VI). No new ceiling is
 * invented here either — a number without its fold, machine and provider is not reportable in
 * this project (constitution VI), and refitting needs the labelled tape, not tonight's session.
 *
 * **Register R-1121: `noSpeechProb` is `null` on every real decode today**, not occasionally —
 * `RealSherpaDecoder` wraps sherpa-onnx's `OfflineRecognizer`, and that binding's
 * `OfflineRecognizerResult` (confirmed by decompiling `sherpa-onnx-jvm-1.13.7.jar`) carries no
 * no-speech probability, no per-hypothesis or per-token log-probability, and no n-best list at
 * all. A `null` here is therefore not "nothing to check this once" but "this control cannot run
 * against this decoder, full stop" — which is exactly why it evaluates to [Indeterminate] rather
 * than [Accept]: accepting would make the control silently inert forever while looking, in every
 * fingerprint and every passing test, identical to a control that is healthy.
 */
public class NoSpeechProbRule(private val ceiling: Double = DEFAULT_CEILING) : PostDecodeRejectionRule {
    override val id: RejectionRuleId = RejectionRuleId.NO_SPEECH_PROB

    override fun evaluate(result: AsrResult): RejectionVerdict {
        val p = result.noSpeechProb
            ?: return RejectionVerdict.Indeterminate(
                id,
                "no_speech_prob was not produced for this decode — the decoder's binding does not " +
                    "expose a no-speech or utterance-level confidence signal",
            )
        return if (p > ceiling) {
            RejectionVerdict.Reject(id, "no_speech_prob=$p exceeds ceiling=$ceiling")
        } else {
            RejectionVerdict.Accept
        }
    }

    public companion object {
        /** Whisper's conventional default (technical design §8.2) — see class doc: unrefitted. */
        public const val DEFAULT_CEILING: Double = 0.60
    }
}
