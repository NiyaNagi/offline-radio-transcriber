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
 * Precision outranks recall at every tier (NFR-1a): below the [Calibrator]'s `confirmThreshold`
 * the candidate is never asserted, not even as a weaker state. A weaker device may know less; it
 * must not be more wrong.
 *
 * **P33 / R-1110 (constitution I, VI):** `CONFIRMED` requires a real, fitted [calibrator] — there
 * is no path from here to `CONFIRMED` without one, and no path that substitutes a hand-picked
 * threshold constant for it (a number without its fold is not evidence). With [calibrator] `null`
 * — production's state today, absent dev-fold data to fit one against — a resolvable top
 * candidate (one that survived the separation check) reports `AMBIGUOUS`, never `CONFIRMED` and
 * never `UNKNOWN`: there genuinely is a real, uncontested candidate here, so "nothing worth
 * asserting" (`UNKNOWN`) would be as dishonest as asserting it outright; "we cannot yet say how
 * sure we are" (`AMBIGUOUS`) is the true state.
 */
public class CallsignResolver(
    /** FR-LEX-11: two top candidates within this margin of `totalScore` are too close to separate. */
    private val separationThreshold: Float,
    /** Supplies the confirm threshold and the fingerprint's calibration provenance. `null` means
     * no calibration exists yet — see this class's own doc comment for what that does below. */
    private val calibrator: Calibrator?,
) {
    public fun resolve(ranked: List<RankedCandidate>): Attribution {
        val top = ranked.firstOrNull() ?: return Attribution.unknown()
        val second = ranked.getOrNull(1)
        if (second != null && (top.totalScore - second.totalScore) < separationThreshold) {
            return Attribution.ambiguous()
        }
        // R-1110: no calibrator means no confirmThreshold to test against, so there is nothing to
        // assert against -- never CONFIRMED, and never UNKNOWN either (a real, uncontested
        // candidate is exactly not "nothing worth asserting"). AMBIGUOUS is the honest state.
        val calibration = calibrator ?: return Attribution.ambiguous()
        if (top.totalScore < calibration.confirmThreshold) {
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
