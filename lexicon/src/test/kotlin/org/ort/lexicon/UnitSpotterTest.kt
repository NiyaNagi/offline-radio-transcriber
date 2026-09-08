package org.ort.lexicon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M4.1 (build-plan P11, technical design §9.7): `UnitSpotter` is specified at the interface
 * only in the design ("Two candidate implementations to be raced in M4, both producing the same
 * type"), because M4 decides whether Pass C exists at all (R3). [TextDerivedUnitSpotter] is the
 * baseline every acoustic spotter must beat — it is the same T0 degraded path
 * [TextDerivedLatticeBuilder] already builds, wrapped so the M4 comparison can drive all
 * candidate spotters through one uniform interface.
 */
class UnitSpotterTest {

    @Test
    fun `TextDerivedUnitSpotter ignores the audio and expands the given transcript, tagged TEXT_DERIVED`() {
        val spotter = TextDerivedUnitSpotter(VariantTable.bundled()) { "kilo seven alpha bravo charlie" }
        val lattice = spotter.spot(FloatArray(0))
        assertEquals(LatticeSource.TEXT_DERIVED, lattice.source)
        assertEquals(PhoneticUnit.spell("K7ABC"), lattice.slots.map { it.top.unit })
    }

    @Test
    fun `an unrecognised token is excluded rather than crashing the whole spot`() {
        val spotter = TextDerivedUnitSpotter(VariantTable.bundled()) { "static kilo seven alpha bravo charlie" }
        val lattice = spotter.spot(FloatArray(0))
        assertEquals(PhoneticUnit.spell("K7ABC"), lattice.slots.map { it.top.unit })
    }

    @Test
    fun `an empty transcript spots an empty lattice, never a fabricated guess`() {
        val spotter = TextDerivedUnitSpotter(VariantTable.bundled()) { "" }
        assertTrue(spotter.spot(FloatArray(0)).isEmpty)
    }
}
