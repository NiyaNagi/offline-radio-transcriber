package org.ort.lexicon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PhoneticLatticeTest {

    @Test
    fun `FR_LEX_5 a slot retains alternatives with their scores, not a single best path`() {
        val slot = LatticeSlot(
            startMs = 0,
            endMs = 120,
            alts = listOf(UnitScore(PhoneticUnit.B, -0.2f), UnitScore(PhoneticUnit.D, -1.4f)),
        )
        assertEquals(setOf(PhoneticUnit.B, PhoneticUnit.D), slot.alts.map { it.unit }.toSet())
        assertEquals(PhoneticUnit.B, slot.top.unit)
        // the lattice still carries the loser, with its score, for downstream ranking
        assertEquals(-1.4f, slot.alts.first { it.unit == PhoneticUnit.D }.logProb)
    }

    @Test
    fun `FR_LEX_5 the parser sees both alternatives of an ambiguous slot`() {
        val grammar = bundledGrammar()
        val lattice = PhoneticLattice(
            source = LatticeSource.ACOUSTIC,
            slots = listOf(
                slotOf(UnitScore(PhoneticUnit.K, -0.1f), UnitScore(PhoneticUnit.W, -0.3f)),
                slotOf(UnitScore(PhoneticUnit.N7, 0f)),
                slotOf(UnitScore(PhoneticUnit.A, 0f)),
                slotOf(UnitScore(PhoneticUnit.B, 0f)),
                slotOf(UnitScore(PhoneticUnit.C, 0f)),
            ),
        )
        val texts = grammar.parse(lattice).map { it.text }
        assertTrue("K7ABC" in texts, texts.toString())
        assertTrue("W7ABC" in texts, texts.toString())
    }

    @Test
    fun `a slot with no alternatives is not a hypothesis`() {
        assertThrows(IllegalArgumentException::class.java) { LatticeSlot(0, 10, emptyList()) }
    }

    private fun slotOf(vararg alts: UnitScore) = LatticeSlot(0, 100, alts.toList())
}
