package org.ort.pipeline.alerts

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.core.AttributionState

/** Build-plan P31, FR-ALR-1: [AlertMatcher] is a pure decision — every case here is asserted
 * without touching a store, a dispatcher or a coroutine. */
class AlertMatcherTest {

    private fun input(
        stationId: String? = null,
        resolvedCallsign: String? = null,
        transcript: String? = null,
        frequencyHz: Long? = null,
        state: AttributionState = AttributionState.CONFIRMED,
    ) = AlertMatchInput(
        transmissionId = "TX1",
        attributionState = state,
        stationId = stationId,
        resolvedCallsign = resolvedCallsign,
        transcriptText = transcript,
        frequencyHz = frequencyHz,
    )

    @Test
    fun `FR_ALR_1 a callsign watch matches the resolved candidate case-insensitively`() {
        val watch = AlertWatch.Callsign(id = "w1", callsign = "K7ABC")

        assertTrue(AlertMatcher.matches(watch, input(resolvedCallsign = "k7abc")))
        assertFalse(AlertMatcher.matches(watch, input(resolvedCallsign = "W7XYZ")))
        assertFalse(AlertMatcher.matches(watch, input(resolvedCallsign = null)))
    }

    /**
     * Register R-1125: before this fix, [AlertMatcher] matched a [AlertWatch.Callsign] against
     * [AlertMatchInput.stationId], which is `null` for `AMBIGUOUS` (register R-1110: every
     * attribution today without a calibrator) — so a watch could never fire in production at all.
     * [AlertMatchInput.resolvedCallsign] is populated the moment the grammar parses a candidate,
     * independent of how sure the resolver is willing to be about it.
     */
    @Test
    fun `R_1125 a callsign watch fires on an AMBIGUOUS resolved candidate`() {
        val watch = AlertWatch.Callsign(id = "w1", callsign = "K7ABC")
        val ambiguousMatch = input(
            state = AttributionState.AMBIGUOUS,
            stationId = null,
            resolvedCallsign = "K7ABC",
        )

        assertTrue(
            AlertMatcher.matches(watch, ambiguousMatch),
            "an AMBIGUOUS candidate is a real resolved candidate, not a reason to stay dark",
        )
    }

    /** Register R-1125: the converse of the case above — a transcript this over's grammar could
     * not parse a candidate from at all (no [AlertMatchInput.resolvedCallsign]) must not match,
     * however the attribution state reads. */
    @Test
    fun `R_1125 a callsign watch does not fire when no candidate was resolved for this transmission`() {
        val watch = AlertWatch.Callsign(id = "w1", callsign = "K7ABC")
        val noCandidate = input(state = AttributionState.UNKNOWN, stationId = null, resolvedCallsign = null)

        assertFalse(AlertMatcher.matches(watch, noCandidate))
    }

    @Test
    fun `FR_ALR_1 a keyword watch matches a substring of the transcript case-insensitively`() {
        val watch = AlertWatch.Keyword(id = "w2", keyword = "SKYWARN")

        assertTrue(AlertMatcher.matches(watch, input(transcript = "this is a skywarn activation")))
        assertFalse(AlertMatcher.matches(watch, input(transcript = "ordinary traffic")))
        assertFalse(AlertMatcher.matches(watch, input(transcript = null)))
    }

    @Test
    fun `FR_ALR_1 a frequency watch matches within tolerance, not beyond it`() {
        val watch = AlertWatch.Frequency(id = "w3", frequencyHz = 146_520_000L)

        assertTrue(AlertMatcher.matches(watch, input(frequencyHz = 146_520_000L)))
        assertTrue(AlertMatcher.matches(watch, input(frequencyHz = 146_520_000L + AlertMatcher.FREQUENCY_TOLERANCE_HZ)))
        assertFalse(
            AlertMatcher.matches(watch, input(frequencyHz = 146_520_000L + AlertMatcher.FREQUENCY_TOLERANCE_HZ + 1)),
        )
        assertFalse(AlertMatcher.matches(watch, input(frequencyHz = null)))
    }

    @Test
    fun `a disabled watch is still a structural match -- filtering by enabled is the caller's job`() {
        // AlertEvaluationCoordinator, not AlertMatcher, is responsible for skipping disabled
        // watches (its own `filter { it.enabled }`) -- this test pins that division of labour so a
        // future change does not silently duplicate or drop the check.
        val watch = AlertWatch.Callsign(id = "w1", callsign = "K7ABC", enabled = false)

        assertTrue(AlertMatcher.matches(watch, input(resolvedCallsign = "K7ABC")))
    }
}
