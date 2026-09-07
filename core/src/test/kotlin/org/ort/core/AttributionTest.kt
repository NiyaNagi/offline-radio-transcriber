package org.ort.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AttributionTest {

    @Test
    fun `the attribution state set is exactly the four closed values`() {
        assertEquals(
            setOf("CONFIRMED", "INFERRED", "AMBIGUOUS", "UNKNOWN"),
            AttributionState.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun `FR_SPK_10 every attribution carries a non-optional state`() {
        // there is no factory that produces an Attribution without a state — this compiles
        // only because each path sets one.
        assertEquals(AttributionState.CONFIRMED, Attribution.confirmed("K7ABC", 0.97).state)
        assertEquals(AttributionState.INFERRED, Attribution.inferred("K7ABC", 0.8).state)
        assertEquals(AttributionState.AMBIGUOUS, Attribution.ambiguous().state)
        assertEquals(AttributionState.UNKNOWN, Attribution.unknown().state)
    }

    @Test
    fun `CONFIRMED requires a resolved station and a calibrated probability`() {
        assertThrows(IllegalArgumentException::class.java) { Attribution.confirmed("", 0.9) }
        assertThrows(IllegalArgumentException::class.java) { Attribution.confirmed("K7ABC", 1.4) }
    }

    @Test
    fun `a user correction locks the attribution against re-propagation`() {
        val corrected = Attribution.inferred("W1AW", 0.6).withCorrection("K7ABC")
        assertTrue(corrected.corrected)
        assertEquals("K7ABC", corrected.stationId)
        assertEquals(AttributionState.INFERRED, corrected.state)
    }

    @Test
    fun `AMBIGUOUS and UNKNOWN assert no station`() {
        assertEquals(null, Attribution.ambiguous().stationId)
        assertEquals(null, Attribution.unknown().stationId)
    }
}
