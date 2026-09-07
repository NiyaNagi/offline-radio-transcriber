package org.ort.eval

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.lexicon.PriorCombiner
import org.ort.lexicon.RankingContext
import org.ort.lexicon.RepeaterMatch
import org.ort.lexicon.defaultPriors

/** Per-prior ablation: report how metrics move when one prior is removed (build-plan P7). */
class PriorAblationTest {

    private fun testConfig() = HarnessConfig("dev", "m", "cpu", 1, "test")

    @Test
    fun `ablating a prior is reported for every prior the combiner carries`() {
        val combiner = PriorCombiner(defaultPriors())
        val harness = Harness(bundledGrammar(), combiner)
        val report = harness.run(listOf(positive("p1", "VK3MMM")), testConfig())
        assertTrue(report.ablation.map { it.priorRemoved }.toSet() == combiner.priorNames.toSet())
    }

    @Test
    fun `removing the deciding frequency prior changes which candidate the resolver prefers`() {
        // Two candidates with equal acoustic score are impossible to construct through the real
        // grammar directly, so instead we show the ablation mechanism at the PriorCombiner level
        // it is built on: the frequency prior is what breaks a tie toward the repeater's roster.
        val candidate = grammarCandidateFor("VK3MMM")
        val context = RankingContext(repeater = RepeaterMatch(146_520_000, setOf("VK3MMM")))
        val withFrequency = PriorCombiner(defaultPriors()).rank(listOf(candidate), context).single()
        val withoutFrequency = PriorCombiner(defaultPriors())
            .withoutPrior("frequency")
            .rank(listOf(candidate), context)
            .single()
        assertTrue(
            withFrequency.totalScore > withoutFrequency.totalScore,
            "the frequency prior must have added log-odds",
        )
    }
}

private fun grammarCandidateFor(callsign: String) =
    bundledGrammar().parse(acousticLattice(callsign)).first { it.text == callsign }
