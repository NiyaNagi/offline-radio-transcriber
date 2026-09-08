package org.ort.lexicon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * FR-LEX-11's first sentence — "emit a ranked candidate list with scores" — is a `:lexicon`
 * capability: [PriorCombiner.rank] over [CallsignGrammar.parse]'s output. The requirement's
 * second sentence (declaring `AMBIGUOUS` when the top two scores are within a separation
 * threshold) is a *different* capability, implemented and tested downstream in
 * `:pipeline`'s `CallsignResolver`/`CallsignResolverTest` — out of this module's ownership, so
 * not established here (see the audit finding this test answers).
 */
class RankedCandidateListTest {

    @Test
    fun `FR_LEX_11 ranking emits every surviving candidate with its own score, not just a winner`() {
        // P->B costs 0.30, P->D costs 0.35 in the bundled confusion matrix (ConfusionCostMatrixTest),
        // so a lattice spelling P7ABC yields both B7ABC and D7ABC as distinctly scored candidates.
        val candidates = bundledGrammar().parse(
            latticeOf(PhoneticUnit.P, PhoneticUnit.N7, PhoneticUnit.A, PhoneticUnit.B, PhoneticUnit.C),
        )
        val ranked = PriorCombiner(defaultPriors(PropagationModel())).rank(candidates, RankingContext())

        assertTrue(ranked.size >= 2, "expected multiple candidates in the list, got $ranked")
        val texts = ranked.map { it.candidate.text }
        assertTrue("B7ABC" in texts && "D7ABC" in texts, texts.toString())
        // every entry carries its own score
        ranked.forEach { assertTrue(it.totalScore.isFinite(), "candidate ${it.candidate.text} has no finite score") }
    }

    @Test
    fun `FR_LEX_11 the emitted list is sorted best score first`() {
        val candidates = bundledGrammar().parse(
            latticeOf(PhoneticUnit.P, PhoneticUnit.N7, PhoneticUnit.A, PhoneticUnit.B, PhoneticUnit.C),
        )
        val ranked = PriorCombiner(defaultPriors(PropagationModel())).rank(candidates, RankingContext())
        val scores = ranked.map { it.totalScore }
        assertEquals(scores.sortedDescending(), scores, "ranked list must be best-first")
    }
}
