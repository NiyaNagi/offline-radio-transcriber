package org.ort.lexicon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** AC-15: at T0, the text-derived lattice is the same type as an acoustic one and records its source. */
class TextDerivedLatticeBuilderTest {

    private val builder = TextDerivedLatticeBuilder(VariantTable.bundled())
    private val grammar = bundledGrammar()

    @Test
    fun `AC_15 a text-derived lattice is the same PhoneticLattice type as an acoustic one`() {
        val acoustic = PhoneticLattice.ofUnits(PhoneticUnit.spell("K7ABC"), LatticeSource.ACOUSTIC)
        val textDerived = builder.build(listOf("kilo", "seven", "alpha", "bravo", "charlie"))
        assertEquals(acoustic::class, textDerived::class)
        assertEquals(acoustic.slots.size, textDerived.slots.size)
    }

    @Test
    fun `AC_15 the record indicates the degraded text-derived source`() {
        val lattice = builder.build(listOf("kilo", "seven", "alpha", "bravo", "charlie"))
        assertEquals(LatticeSource.TEXT_DERIVED, lattice.source)
    }

    @Test
    fun `AC_15 resolution works identically whether the lattice came from Pass C or from Pass B text`() {
        val fromText = builder.build(listOf("kilo", "seven", "alpha", "bravo", "charlie"))
        val fromAudio = PhoneticLattice.ofUnits(PhoneticUnit.spell("K7ABC"), LatticeSource.ACOUSTIC)
        val textResult = grammar.parse(fromText).first().text
        val audioResult = grammar.parse(fromAudio).first().text
        assertEquals(audioResult, textResult)
    }

    @Test
    fun `AC_15 each slot has exactly one alternative, reflecting that Pass B text carries no acoustic alternatives`() {
        val lattice = builder.build(listOf("kilo", "seven"))
        assertTrue(lattice.slots.all { it.alts.size == 1 })
    }

    @Test
    fun `an unknown spoken token is rejected rather than guessed`() {
        org.junit.jupiter.api.Assertions.assertThrows(UnknownSpokenFormException::class.java) {
            builder.build(listOf("not-a-real-phonetic-word"))
        }
    }
}
