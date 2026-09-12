package org.ort.pipeline.export

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.reflect.full.isSubclassOf

/**
 * Register R-1009 (WPX), FR-EXP-4, constitution I. See [ExportAttribution]'s own kdoc for the
 * structural argument; these tests are the "try to violate it" proof the register row asked for.
 */
class ExportAttributionTest {

    @Test
    fun `FR_EXP_4 the closed set has exactly four states, the same four as the data layer`() {
        // "Try to violate it": ExportAttribution must stay sealed with exactly these branches, so
        // a fifth state added later cannot silently fall through an existing `when` anywhere in
        // this package without the compiler flagging every non-exhaustive branch.
        val direct = ExportAttribution::class.sealedSubclasses.map { it.simpleName }.toSet()
        assertEquals(setOf("CallsignKnown", "Ambiguous", "Unknown"), direct)
        val callsignKnown = ExportAttribution.CallsignKnown::class.sealedSubclasses.map { it.simpleName }.toSet()
        assertEquals(setOf("Confirmed", "Inferred"), callsignKnown)
        assertTrue(ExportAttribution.Confirmed::class.isSubclassOf(ExportAttribution.CallsignKnown::class))
        assertTrue(ExportAttribution.Inferred::class.isSubclassOf(ExportAttribution.CallsignKnown::class))
    }

    @Test
    fun `FR_EXP_4 an inferred attribution's cells are tagged INFERRED, never CONFIRMED`() {
        val inferred = ExportAttribution.Inferred(callsign = "KI7ABC", confidence = 0.62, corrected = false)
        val cells = inferred.toCells()
        assertEquals("INFERRED", cells.stateTag)
        assertFalse(cells.stateTag == "CONFIRMED")
        assertEquals("KI7ABC", cells.callsignCell)
        assertEquals("0.6200", cells.confidenceCell)
    }

    @Test
    fun `FR_EXP_4 a confirmed attribution's cells are tagged CONFIRMED with its real confidence`() {
        val confirmed = ExportAttribution.Confirmed(callsign = "KI7ABC", confidence = 0.94, corrected = false)
        val cells = confirmed.toCells()
        assertEquals("CONFIRMED", cells.stateTag)
        assertEquals("0.9400", cells.confidenceCell)
    }

    @Test
    fun `FR_EXP_4 ambiguous and unknown attributions never carry a callsign or a confidence value`() {
        val ambiguousCells = ExportAttribution.Ambiguous.toCells()
        assertEquals("AMBIGUOUS", ambiguousCells.stateTag)
        assertEquals("", ambiguousCells.confidenceCell)
        assertEquals("UNIDENTIFIED", ambiguousCells.callsignCell)

        val unknownCells = ExportAttribution.Unknown.toCells()
        assertEquals("UNKNOWN", unknownCells.stateTag)
        assertEquals("", unknownCells.confidenceCell)
        assertEquals("UNIDENTIFIED", unknownCells.callsignCell)
    }

    @Test
    fun `FR_EXP_4 there is no accessor that reads a callsign without also reading a state`() {
        // "Try to violate it": the only public member on the sealed interface itself is none at
        // all — `callsign` exists solely on `CallsignKnown`, so a function typed to accept the
        // base `ExportAttribution` (which is what every writer's input list actually carries) has
        // no member access to a callsign whatsoever without first narrowing (via `is`/`when`) to
        // `CallsignKnown` — the exact narrowing that also makes the state (the sealed branch
        // itself) known at the same point. Reflection confirms the base type declares no such
        // member for something to accidentally call.
        val baseMembers = ExportAttribution::class.members.map { it.name }
        assertFalse(baseMembers.contains("callsign"))
        assertFalse(baseMembers.contains("confidence"))
    }
}
