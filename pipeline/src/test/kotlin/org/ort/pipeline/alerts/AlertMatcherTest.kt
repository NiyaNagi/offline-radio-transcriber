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
        transcript: String? = null,
        frequencyHz: Long? = null,
        state: AttributionState = AttributionState.CONFIRMED,
    ) = AlertMatchInput(
        transmissionId = "TX1",
        attributionState = state,
        stationId = stationId,
        transcriptText = transcript,
        frequencyHz = frequencyHz,
    )

    @Test
    fun `FR_ALR_1 a callsign watch matches the resolved station id case-insensitively`() {
        val watch = AlertWatch.Callsign(id = "w1", callsign = "K7ABC")

        assertTrue(AlertMatcher.matches(watch, input(stationId = "k7abc")))
        assertFalse(AlertMatcher.matches(watch, input(stationId = "W7XYZ")))
        assertFalse(AlertMatcher.matches(watch, input(stationId = null)))
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

        assertTrue(AlertMatcher.matches(watch, input(stationId = "K7ABC")))
    }
}
