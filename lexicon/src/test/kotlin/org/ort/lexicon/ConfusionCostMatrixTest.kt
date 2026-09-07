package org.ort.lexicon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfusionCostMatrixTest {

    private val matrix = ConfusionCostMatrix.bundled()

    @Test
    fun `FR_LEX_10 substitution within the E-set costs less than an arbitrary substitution`() {
        val eSet = PhoneticUnit.spell("BDEPVT")
        val withinCosts = eSet.flatMap { a -> eSet.filter { it != a }.map { b -> matrix.substitutionCost(a, b) } }
        val arbitrary = matrix.substitutionCost(PhoneticUnit.B, PhoneticUnit.K)

        assertEquals(1.0f, arbitrary, "an unlisted pair costs the default")
        assertTrue(withinCosts.max() < arbitrary, "E-set max ${withinCosts.max()} should beat $arbitrary")
    }

    @Test
    fun `FR_LEX_10 the matrix is symmetric and identity is free`() {
        assertEquals(0f, matrix.substitutionCost(PhoneticUnit.B, PhoneticUnit.B))
        assertEquals(
            matrix.substitutionCost(PhoneticUnit.M, PhoneticUnit.N),
            matrix.substitutionCost(PhoneticUnit.N, PhoneticUnit.M),
        )
    }

    @Test
    fun `FR_LEX_10 a confusion-weighted near miss outranks a less-confusable one`() {
        // lattice spells P7ABC; P->B costs 0.30, P->D costs 0.35, so B7ABC should rank above D7ABC
        val candidates = bundledGrammar().parse(
            latticeOf(PhoneticUnit.P, PhoneticUnit.N7, PhoneticUnit.A, PhoneticUnit.B, PhoneticUnit.C),
        )
        val b = candidates.first { it.text == "B7ABC" }
        val d = candidates.first { it.text == "D7ABC" }
        assertTrue(b.score > d.score, "B7ABC ${b.score} should outrank D7ABC ${d.score}")
        assertTrue(b.editPenalty < d.editPenalty)
    }

    @Test
    fun `the matrix is a versioned asset`() {
        assertEquals("lexicon-confusion-costs", matrix.version.assetId)
    }
}
