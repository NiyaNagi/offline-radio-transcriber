package org.ort.lexicon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * R-1148 (split from R-1144/R-1117; FR-UI-8, constitution I): [CallsignGrammar.parse]'s emitted
 * [CallsignCandidate.slotAlignment] — the genuinely per-*candidate* record [CallsignGrammarSlotDetailTest]'s
 * own `FR_UI_8_slot_detail_is_the_same_across_every_candidate_from_the_same_lattice` proves
 * [CallsignCandidate.slotDetails] can never be: two candidates parsed from one lattice must
 * disagree here wherever their own paths through the beam search actually disagreed.
 *
 * Fixture note (constitution VI): `K7LWH`/`K7LVH` are the board's own worked example
 * (`Detail-Why.dc.html`) — fictional, structurally-only callsigns; these are pure-code unit tests,
 * no fold/machine/provider applies.
 */
class CallsignGrammarSlotAlignmentTest {

    private val grammar = bundledGrammar()

    @Test
    fun `R_1148 an exact match candidate agrees with the lattice at every slot`() {
        val lattice = PhoneticLattice.ofUnits(PhoneticUnit.spell("K7LWH"), LatticeSource.TEXT_DERIVED)

        val chosen = grammar.parse(lattice).first { it.text == "K7LWH" }

        assertEquals(5, chosen.slotAlignment.size)
        assertEquals((0..4).toList(), chosen.slotAlignment.map { it.slotIndex })
        chosen.slotAlignment.forEach { alignment ->
            assertTrue(alignment.matches, alignment.toString())
            assertEquals(alignment.latticeUnit, alignment.candidateUnit)
            assertTrue(alignment.offeredByLattice, alignment.toString())
        }
    }

    @Test
    fun `R_1148 a substitution the lattice never offered is recorded as not offered`() {
        // A single-alt (text-derived-shaped) 5-slot lattice for K7ABD: slot 3 offers only B.
        // The bundled confusion matrix lists B<->D (E-set, cost 0.30), so K7ADD is a real
        // candidate -- but D was never itself one of slot 3's own alternatives.
        val lattice = PhoneticLattice.ofUnits(PhoneticUnit.spell("K7ABD"), LatticeSource.TEXT_DERIVED)

        val runnerUp = grammar.parse(lattice).first { it.text == "K7ADD" }

        val slot3 = runnerUp.slotAlignment.single { it.slotIndex == 3 }
        assertEquals("B", slot3.latticeUnit)
        assertEquals("D", slot3.candidateUnit)
        assertFalse(slot3.matches)
        assertFalse(slot3.offeredByLattice, "the lattice's only alt at slot 3 was B, never D")
    }

    @Test
    fun `R_1148 a substitution to the lattices own kept alternate is recorded as offered`() {
        // Board's own figures: slot 3 carries both W (top, .64) and V (kept alternate, .29).
        val lattice = PhoneticLattice(
            source = LatticeSource.ACOUSTIC,
            slots = listOf(
                slotOf(UnitScore(PhoneticUnit.K, 0.96f)),
                slotOf(UnitScore(PhoneticUnit.N7, 0.99f)),
                slotOf(UnitScore(PhoneticUnit.L, 0.91f)),
                slotOf(UnitScore(PhoneticUnit.W, 0.64f), UnitScore(PhoneticUnit.V, 0.29f)),
                slotOf(UnitScore(PhoneticUnit.H, 0.88f)),
            ),
        )

        val runnerUp = grammar.parse(lattice).first { it.text == "K7LVH" }

        val slot3 = runnerUp.slotAlignment.single { it.slotIndex == 3 }
        assertEquals("W", slot3.latticeUnit)
        assertEquals("V", slot3.candidateUnit)
        assertFalse(slot3.matches)
        assertTrue(slot3.offeredByLattice, "V was the slot's own kept alternate, genuinely offered")
    }

    @Test
    fun `R_1148 two candidates from the same lattice disagree in slot alignment where their paths disagreed`() {
        val lattice = PhoneticLattice(
            source = LatticeSource.ACOUSTIC,
            slots = listOf(
                slotOf(UnitScore(PhoneticUnit.K, 0.96f)),
                slotOf(UnitScore(PhoneticUnit.N7, 0.99f)),
                slotOf(UnitScore(PhoneticUnit.L, 0.91f)),
                slotOf(UnitScore(PhoneticUnit.W, 0.64f), UnitScore(PhoneticUnit.V, 0.29f)),
                slotOf(UnitScore(PhoneticUnit.H, 0.88f)),
            ),
        )

        val candidates = grammar.parse(lattice)
        val chosen = candidates.first { it.text == "K7LWH" }
        val runnerUp = candidates.first { it.text == "K7LVH" }

        // The pre-existing, per-lattice fact stays identical across candidates (unchanged
        // behaviour, R-320) -- a test of this change must not regress that.
        assertEquals(chosen.slotDetails[3], runnerUp.slotDetails[3])

        // The new, per-candidate fact must differ exactly where the two paths differed: a test
        // that passed against today's shared SlotDetail list is not a test of this change.
        assertEquals("W", chosen.slotAlignment[3].candidateUnit)
        assertEquals("V", runnerUp.slotAlignment[3].candidateUnit)
        assertTrue(chosen.slotAlignment[3] != runnerUp.slotAlignment[3])

        // Every other slot agrees between the two paths (they only ever differed at slot 3).
        listOf(0, 1, 2, 4).forEach { i ->
            assertEquals(
                chosen.slotAlignment[i],
                runnerUp.slotAlignment[i],
                "slot $i should agree between K7LWH and K7LVH",
            )
        }
    }

    @Test
    fun `R_1148 a deleted slot records no candidate unit and is never offered`() {
        // A 6-slot lattice for K7LXWH; K7LWH is the same 5-character parse the board's own worked
        // example uses, reached here by deleting the "X" slot (index 3) rather than keeping it.
        val lattice = PhoneticLattice.ofUnits(PhoneticUnit.spell("K7LXWH"), LatticeSource.TEXT_DERIVED)

        val candidates = grammar.parse(lattice)
        val deleted = candidates.first { it.text == "K7LWH" }
        val kept = candidates.first { it.text == "K7LXWH" }

        assertEquals(6, deleted.slotAlignment.size, deleted.slotAlignment.toString())
        val deletedSlot = deleted.slotAlignment.single { it.slotIndex == 3 }
        assertEquals("X", deletedSlot.latticeUnit)
        assertNull(deletedSlot.candidateUnit)
        assertFalse(deletedSlot.offeredByLattice, "a deleted slot is never offered")
        assertFalse(deletedSlot.matches)

        // The candidate that kept every slot agrees with the lattice everywhere, including slot 3.
        assertEquals(6, kept.slotAlignment.size)
        val keptSlot3 = kept.slotAlignment.single { it.slotIndex == 3 }
        assertEquals("X", keptSlot3.candidateUnit)
        assertTrue(keptSlot3.matches)
    }

    private fun slotOf(vararg alts: UnitScore) = LatticeSlot(0, 100, alts.toList())
}
