package org.ort.pipeline.passb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.ort.core.AttributionState
import org.ort.lexicon.CallsignCandidate
import org.ort.lexicon.ItuAllocation
import org.ort.lexicon.ParsedCallsign
import org.ort.lexicon.RankedCandidate

/**
 * AC-15 / FR-LEX-11 / constitution I: the M1 resolver turns ranked candidates into an
 * [org.ort.core.Attribution] whose state is one of the closed four (never a bare score). Because
 * a text-derived candidate's callsign text was read directly out of *this* transmission's Pass B
 * transcript — not carried from a voice match or another session — a confident, separated top
 * candidate is legitimately `CONFIRMED` here (constitution I: "heard and resolved in this
 * transmission"), never `INFERRED`, which this resolver never produces.
 */
class CallsignResolverTest {

    /** A [RankedCandidate] whose [RankedCandidate.totalScore] is exactly [totalScore] (no priors). */
    private fun fakeRanked(text: String, totalScore: Float): RankedCandidate {
        val parsed = ParsedCallsign(
            prefix = text.dropLast(4),
            areaDigit = text[text.length - 4],
            suffix = text.takeLast(3),
        )
        val candidate = CallsignCandidate(
            parsed = parsed,
            allocation = ItuAllocation(parsed.prefix, "Test Entity", "TT"),
            acousticLogProb = totalScore,
            editPenalty = 0f,
            slotSpan = 0..0,
        )
        return RankedCandidate(candidate, emptyList())
    }

    /** Wraps the repeated `CallsignResolver(separationThreshold = ..., calibrator =
     * FakeCalibrator(confirmThreshold = ...))` construction once, keeping each case's own
     * [confirmThreshold] — the value that distinguishes the cases below — visible at the call site. */
    private fun resolver(confirmThreshold: Float, separationThreshold: Float = 0.5f): CallsignResolver =
        CallsignResolver(
            separationThreshold = separationThreshold,
            calibrator = FakeCalibrator(confirmThreshold = confirmThreshold),
        )

    @Test
    fun `a single well-separated candidate above threshold is CONFIRMED`() {
        val resolver = resolver(confirmThreshold = 0.6f)
        val ranked = listOf(fakeRanked("K7ABC", 5.0f))
        val attribution = resolver.resolve(ranked)
        assertEquals(AttributionState.CONFIRMED, attribution.state)
        assertEquals("K7ABC", attribution.stationId)
    }

    @Test
    fun `two candidates within the separation threshold are AMBIGUOUS`() {
        val resolver = resolver(confirmThreshold = 0.1f)
        val ranked = listOf(fakeRanked("K7ABC", 5.0f), fakeRanked("K7ABD", 4.8f))
        val attribution = resolver.resolve(ranked)
        assertEquals(AttributionState.AMBIGUOUS, attribution.state)
    }

    @Test
    fun `no candidates is UNKNOWN`() {
        val resolver = resolver(confirmThreshold = 0.1f)
        assertEquals(AttributionState.UNKNOWN, resolver.resolve(emptyList()).state)
    }

    @Test
    fun `a well-separated candidate below the confirm threshold is UNKNOWN, never asserted (NFR-1a)`() {
        val resolver = resolver(confirmThreshold = 100f)
        val ranked = listOf(fakeRanked("K7ABC", 5.0f))
        assertEquals(AttributionState.UNKNOWN, resolver.resolve(ranked).state)
    }

    @Test
    fun `never produces INFERRED (constitution I -- CONFIRMED means heard in this transmission, not carried)`() {
        val resolver = resolver(confirmThreshold = -1000f, separationThreshold = 0.0f)
        val ranked = listOf(fakeRanked("K7ABC", 5.0f), fakeRanked("K7ABD", -1000f))
        assertEquals(AttributionState.CONFIRMED, resolver.resolve(ranked).state)
    }

    // --- P33 / R-1110: CONFIRMED requires a calibration; with none, the honest state is
    // AMBIGUOUS -- never CONFIRMED (constitution I) and never UNKNOWN (there is a real,
    // uncontested candidate here, which is exactly not "nothing worth asserting").

    @Test
    fun `R_1110 a well-separated top candidate with no calibrator is AMBIGUOUS, never CONFIRMED`() {
        val resolver = CallsignResolver(separationThreshold = 0.5f, calibrator = null)
        val ranked = listOf(fakeRanked("K7ABC", 5.0f))
        assertEquals(AttributionState.AMBIGUOUS, resolver.resolve(ranked).state)
    }

    @Test
    fun `R_1110 a well-separated top candidate with no calibrator is never UNKNOWN either`() {
        // Distinguishes "no calibration" from "confirmThreshold set impossibly high": a resolver
        // with a calibrator whose threshold nothing can clear still correctly reports UNKNOWN
        // (see the test above this one) -- the no-calibrator case is a different state on purpose.
        val resolver = CallsignResolver(separationThreshold = 0.5f, calibrator = null)
        val ranked = listOf(fakeRanked("K7ABC", 5.0f))
        assertEquals(AttributionState.AMBIGUOUS, resolver.resolve(ranked).state)
        org.junit.jupiter.api.Assertions.assertNotEquals(AttributionState.UNKNOWN, resolver.resolve(ranked).state)
    }
}
