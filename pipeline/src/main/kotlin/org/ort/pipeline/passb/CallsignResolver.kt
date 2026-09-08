package org.ort.pipeline.passb

import org.ort.core.Attribution
import org.ort.lexicon.RankedCandidate

/**
 * The M1 resolver (build-plan P11, technical design §9.2/§9.3, FR-LEX-11): the last step of
 * "text-derived lattice → grammar → priors → **resolver** → attribution". It reads nothing the
 * grammar and prior ranking did not already compute — its only job is turning a ranked list into
 * one of the four closed [org.ort.core.AttributionState]s (constitution I), never a bare score.
 *
 * Only `CONFIRMED`, `AMBIGUOUS` and `UNKNOWN` are reachable from here — never `INFERRED`. A
 * text-derived candidate's callsign text came directly out of *this* transmission's Pass B
 * transcript, so a confident, separated top candidate is legitimately "heard and resolved in
 * this transmission" (constitution I). `INFERRED` is reserved for a station carried from context
 * or a voice match, which this resolver — reading only the current transmission's lattice — never
 * does.
 *
 * Precision outranks recall at every tier (NFR-1a): below [confirmThreshold] the candidate is
 * never asserted, not even as a weaker state. A weaker device may know less; it must not be more
 * wrong.
 */
public class CallsignResolver(
    /** FR-LEX-11: two top candidates within this margin of `totalScore` are too close to separate. */
    private val separationThreshold: Float,
    /** The minimum `totalScore` at which the top candidate is trusted enough to assert at all. */
    private val confirmThreshold: Float,
) {
    public fun resolve(ranked: List<RankedCandidate>): Attribution {
        val top = ranked.firstOrNull() ?: return Attribution.unknown()
        val second = ranked.getOrNull(1)
        if (second != null && (top.totalScore - second.totalScore) < separationThreshold) {
            return Attribution.ambiguous()
        }
        if (top.totalScore < confirmThreshold) {
            return Attribution.unknown()
        }
        // Confidence is reported as a [0,1] probability (FR-LEX-17); this resolver is not itself
        // a calibrator, so it clamps rather than pretending a raw score is one. Real confidence
        // comes from a fitted `PlattCalibrator` (technical design §9.5) upstream of this call —
        // out of scope for this session (no dev-fold data to fit against, see CHANGELOG).
        val confidence = top.totalScore.coerceIn(0f, 1f).toDouble()
        return Attribution.confirmed(top.candidate.text, confidence)
    }
}
