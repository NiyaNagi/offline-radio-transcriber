package org.ort.lexicon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * R-320 (register, `Detail-Why.dc.html` section 1, FR-UI-8): [CallsignGrammar.parse]'s emitted
 * [CallsignCandidate.slotDetails] — the real per-slot unit, score and kept alternate, read
 * straight from the lattice's own [LatticeSlot]s (see [SlotDetail]'s own doc comment for why the
 * grammar's confusion-weighted substitutions do not change what a slot reports).
 *
 * Fixture note (constitution VI): every callsign here is fictional and structurally-only —
 * `K7LWH`/`K7LVH` are the board's own worked example (`Detail-Why.dc.html`); these are unit tests
 * of pure code, not a corpus-driven accuracy measurement, so no fold/machine/provider applies —
 * `LexiconGrammarSlotDetailAnchoredTest`'s [TextDerivedLatticeBuilder.buildAnchored] tests draw
 * their spelled callsign from `corpus/manifest.json`'s **dev** fold (`synth-callsigns/dev-01`,
 * never `eval`), per this round's instruction to use existing dev-fold fixtures.
 */
class CallsignGrammarSlotDetailTest {

    private val grammar = bundledGrammar()

    @Test
    fun `FR_UI_8_slot_detail_reports_the_lattices_own_top_unit_and_score_per_slot`() {
        // The board's own worked example: a 5-slot lattice for K7LWH where slot 3 ("W") also
        // carries a kept runner-up ("V" .29) — Detail-Why.dc.html's own figures.
        val lattice = PhoneticLattice(
            source = LatticeSource.ACOUSTIC,
            slots = listOf(
                slotOf(UnitScore(PhoneticUnit.K, 0.96f), UnitScore(PhoneticUnit.C, 0.03f)),
                slotOf(UnitScore(PhoneticUnit.N7, 0.99f)),
                slotOf(UnitScore(PhoneticUnit.L, 0.91f), UnitScore(PhoneticUnit.M, 0.06f)),
                slotOf(UnitScore(PhoneticUnit.W, 0.64f), UnitScore(PhoneticUnit.V, 0.29f)),
                slotOf(UnitScore(PhoneticUnit.H, 0.88f), UnitScore(PhoneticUnit.A, 0.07f)),
            ),
        )

        val chosen = grammar.parse(lattice).first { it.text == "K7LWH" }

        assertEquals(5, chosen.slotDetails.size)
        assertEquals((0..4).toList(), chosen.slotDetails.map { it.index })
        assertEquals(listOf("K", "7", "L", "W", "H"), chosen.slotDetails.map { it.unit })
        assertEquals(0.96, chosen.slotDetails[0].score, 1e-6)
        assertEquals(0.64, chosen.slotDetails[3].score, 1e-6)
    }

    @Test
    fun `FR_UI_8_slot_detail_kept_alternate_is_the_slots_second_best_unit`() {
        val lattice = PhoneticLattice(
            source = LatticeSource.ACOUSTIC,
            slots = listOf(
                slotOf(UnitScore(PhoneticUnit.K, 0.96f), UnitScore(PhoneticUnit.C, 0.03f)),
                slotOf(UnitScore(PhoneticUnit.N7, 0.99f)),
                slotOf(UnitScore(PhoneticUnit.L, 0.91f), UnitScore(PhoneticUnit.M, 0.06f)),
                slotOf(UnitScore(PhoneticUnit.W, 0.64f), UnitScore(PhoneticUnit.V, 0.29f)),
                slotOf(UnitScore(PhoneticUnit.H, 0.88f), UnitScore(PhoneticUnit.A, 0.07f)),
            ),
        )

        val chosen = grammar.parse(lattice).first { it.text == "K7LWH" }

        assertEquals("C", chosen.slotDetails[0].keptAlternate)
        assertEquals("V", chosen.slotDetails[3].keptAlternate)
        assertEquals("A", chosen.slotDetails[4].keptAlternate)
    }

    @Test
    fun `FR_UI_8_slot_detail_kept_alternate_is_null_not_invented_when_a_slot_has_only_one_alternative`() {
        val lattice = PhoneticLattice(
            source = LatticeSource.ACOUSTIC,
            slots = listOf(
                slotOf(UnitScore(PhoneticUnit.K, 0.96f)),
                slotOf(UnitScore(PhoneticUnit.N7, 0.99f)),
                slotOf(UnitScore(PhoneticUnit.L, 0.91f)),
                slotOf(UnitScore(PhoneticUnit.W, 0.64f)),
                slotOf(UnitScore(PhoneticUnit.H, 0.88f)),
            ),
        )

        val chosen = grammar.parse(lattice).first { it.text == "K7LWH" }

        assertTrue(chosen.slotDetails.all { it.keptAlternate == null }, chosen.slotDetails.toString())
    }

    @Test
    fun `FR_UI_8_slot_detail_is_the_same_across_every_candidate_from_the_same_lattice`() {
        // Both K7LWH (the winner) and K7LVH ("V for W" — the board's own runner-up candidate)
        // come from the same lattice, so slot 3's own reported detail must be identical for both:
        // it is a fact about the lattice, not about which candidate is being viewed.
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

        assertEquals(chosen.slotDetails[3], runnerUp.slotDetails[3])
        assertEquals("W", chosen.slotDetails[3].unit)
        assertEquals("V", chosen.slotDetails[3].keptAlternate)
    }

    @Test
    fun `FR_UI_8_slot_detail_char_span_is_null_for_an_acoustic_lattice`() {
        val lattice = PhoneticLattice.ofUnits(PhoneticUnit.spell("K7LWH"), LatticeSource.ACOUSTIC)

        val chosen = grammar.parse(lattice).first { it.text == "K7LWH" }

        assertTrue(chosen.slotDetails.isNotEmpty())
        chosen.slotDetails.forEach {
            assertNull(it.charStart, "slot ${it.index}")
            assertNull(it.charEnd, "slot ${it.index}")
        }
    }

    @Test
    fun `FR_UI_8_slot_detail_char_span_is_null_for_a_text_derived_lattice_built_without_anchoring`() {
        // build(tokens) — the pre-existing entry point — never computed char spans and still
        // does not; only buildAnchored (R-182) does.
        val lattice = SpokenLattice.of("kilo seven lima whiskey hotel")

        val chosen = grammar.parse(lattice).first { it.text == "K7LWH" }

        chosen.slotDetails.forEach { assertNull(it.charStart) }
    }

    @Test
    fun `FR_UI_8_slot_detail_score_matches_the_lattices_own_top_alternative_logProb_exactly`() {
        val lattice = PhoneticLattice(
            source = LatticeSource.ACOUSTIC,
            slots = listOf(
                slotOf(UnitScore(PhoneticUnit.K, -0.42f), UnitScore(PhoneticUnit.C, -1.9f)),
                slotOf(UnitScore(PhoneticUnit.N7, 0f)),
                slotOf(UnitScore(PhoneticUnit.A, 0f)),
                slotOf(UnitScore(PhoneticUnit.B, 0f)),
                slotOf(UnitScore(PhoneticUnit.C, 0f)),
            ),
        )
        val chosen = grammar.parse(lattice).first { it.text == "K7ABC" }

        assertNotNull(chosen.slotDetails.first())
        assertEquals(lattice.slots.first().top.logProb.toDouble(), chosen.slotDetails.first().score, 1e-6)
    }

    private fun slotOf(vararg alts: UnitScore) = LatticeSlot(0, 100, alts.toList())
}
