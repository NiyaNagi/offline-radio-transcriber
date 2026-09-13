package org.ort.pipeline.export

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.core.AttributionState
import kotlin.reflect.full.isSubclassOf

/**
 * Register R-1009 (WPX), FR-EXP-4, constitution I. See [ExportAttribution]'s own kdoc for the
 * structural argument; these tests are the "try to violate it" proof the register row asked for.
 */
class ExportAttributionTest {

    @Test
    fun `FR_EXP_4 the closed set covers the same four data-layer states, plus one honest-inconsistency branch`() {
        // "Try to violate it": ExportAttribution must stay sealed with exactly these branches, so
        // a fifth data-layer state added later cannot silently fall through an existing `when`
        // anywhere in this package without the compiler flagging every non-exhaustive branch.
        // Register R-1039: UnresolvedCallsign is a fifth *branch*, not a fifth *state* — it always
        // carries a real AttributionState (CONFIRMED or INFERRED) of its own, see this class's own
        // `R_1039` tests below.
        val direct = ExportAttribution::class.sealedSubclasses.map { it.simpleName }.toSet()
        assertEquals(setOf("CallsignKnown", "Ambiguous", "Unknown", "UnresolvedCallsign"), direct)
        val callsignKnown = ExportAttribution.CallsignKnown::class.sealedSubclasses.map { it.simpleName }.toSet()
        assertEquals(setOf("Confirmed", "Inferred"), callsignKnown)
        assertTrue(ExportAttribution.Confirmed::class.isSubclassOf(ExportAttribution.CallsignKnown::class))
        assertTrue(ExportAttribution.Inferred::class.isSubclassOf(ExportAttribution.CallsignKnown::class))
        assertFalse(ExportAttribution.UnresolvedCallsign::class.isSubclassOf(ExportAttribution.CallsignKnown::class)) {
            "UnresolvedCallsign must never be able to carry a callsign"
        }
    }

    @Test
    fun `R_1039 UnresolvedCallsign refuses construction for AMBIGUOUS or UNKNOWN — those have their own branches`() {
        assertThrows(IllegalArgumentException::class.java) {
            ExportAttribution.UnresolvedCallsign(AttributionState.AMBIGUOUS, "should never construct")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExportAttribution.UnresolvedCallsign(AttributionState.UNKNOWN, "should never construct")
        }
    }

    @Test
    fun `R_1039 a CONFIRMED UnresolvedCallsign states its real state and reason, never a callsign`() {
        val unresolved = ExportAttribution.UnresolvedCallsign(AttributionState.CONFIRMED, "no station id recorded")
        val cells = unresolved.toCells()
        assertEquals("CONFIRMED", cells.stateTag)
        assertEquals("no station id recorded", cells.noteCell)
        assertEquals("", cells.confidenceCell)
        assertFalse(unresolved is ExportAttribution.CallsignKnown)
    }

    @Test
    fun `R_1039 an INFERRED UnresolvedCallsign is never indistinguishable from a CONFIRMED one`() {
        val confirmed = ExportAttribution.UnresolvedCallsign(AttributionState.CONFIRMED, "reason")
        val inferred = ExportAttribution.UnresolvedCallsign(AttributionState.INFERRED, "reason")
        assertEquals("CONFIRMED", confirmed.toCells().stateTag)
        assertEquals("INFERRED", inferred.toCells().stateTag)
    }

    @Test
    fun `R_1039 a CallsignKnown row's confidence may be genuinely absent — a human correction, never a fabricated 0`() {
        val corrected = ExportAttribution.Inferred(callsign = "KJ7ABC", confidence = null, corrected = true)
        val cells = corrected.toCells()
        assertEquals("KJ7ABC", cells.callsignCell)
        assertEquals("INFERRED", cells.stateTag)
        assertEquals("", cells.confidenceCell)
        assertEquals("true", cells.correctedCell)
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
