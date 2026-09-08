package org.ort.lexicon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * FR-LEX-31: with no history, recency and conversation priors contribute exactly zero — not a
 * default value — and their absence widens the reported confidence interval rather than
 * distorting the ranking.
 */
class ColdStartTest {

    private val candidate = grammarCandidate("VK3MMM")

    @Test
    fun `FR_LEX_31 recency contributes exactly zero with no history at all`() {
        val prior = RecencyPrior()
        val contribution = prior.evaluate(candidate, RankingContext(recency = null))
        assertEquals(0f, contribution.logOdds)
        assertTrue(contribution.coldStart)
    }

    @Test
    fun `FR_LEX_31 conversation context contributes exactly zero with no thread yet`() {
        val prior = ConversationContextPrior()
        val contribution = prior.evaluate(candidate, RankingContext(conversation = null))
        assertEquals(0f, contribution.logOdds)
        assertTrue(contribution.coldStart)
    }

    @Test
    fun `FR_LEX_31 a warm recency prior is not marked cold and does not contribute zero by default`() {
        val prior = RecencyPrior()
        val contribution = prior.evaluate(
            candidate,
            RankingContext(recency = mapOf(candidate.text to RecencyInfo(secondsSinceLastHeard = 0))),
        )
        assertFalse(contribution.coldStart)
        assertTrue(contribution.logOdds > 0f, "just-heard should contribute positively, was ${contribution.logOdds}")
    }

    @Test
    fun `FR_LEX_31 cold-start priors widen the ranked candidate's reported interval, not its ranking distortion`() {
        val combiner = PriorCombiner(defaultPriors(PropagationModel()))
        val cold = combiner.rank(listOf(candidate), RankingContext()).single()
        val warm = combiner.rank(
            listOf(candidate),
            RankingContext(
                recency = mapOf(candidate.text to RecencyInfo(0)),
                conversation = ConversationInfo(otherStationIdentified = true),
            ),
        ).single()
        assertTrue(cold.intervalWidening > warm.intervalWidening)
        assertTrue(cold.coldPriorNames.isNotEmpty())
    }

    @Test
    fun `FR_LEX_31 structural cold start is exactly zero, never a nonzero default`() {
        // Every real prior, fully cold, must contribute precisely 0 log-odds each.
        val cold = RankingContext()
        for (prior in defaultPriors(PropagationModel())) {
            val c = prior.evaluate(candidate, cold)
            if (c.coldStart) assertEquals(0f, c.logOdds, "prior '${prior.name}' cold contribution must be exactly zero")
        }
    }

    @Test
    fun `AC_57 FR_LEX_23 denying location leaves only the geographic prior cold, everything else unaffected`() {
        // "Location permission denied" surfaces here as RankingContext.geographicDistanceKm == null
        // (FR-LEX-22/23's fallback state) — every other prior must still function at full strength.
        val combiner = PriorCombiner(defaultPriors(PropagationModel()))
        val withoutLocation = RankingContext(
            geographicDistanceKm = null,
            recency = mapOf(candidate.text to RecencyInfo(0)),
            myStations = setOf(candidate.text),
        )
        val ranked = combiner.rank(listOf(candidate), withoutLocation).single()

        assertTrue("geographic" in ranked.coldPriorNames, "geographic prior should be cold with no location")
        assertEquals(0f, ranked.contribution("geographic")!!.logOdds)
        // the other configured priors still contributed — full function, only geographic precision lost
        assertTrue(ranked.contribution("recency")!!.logOdds > 0f)
        assertTrue(ranked.contribution("my-stations")!!.logOdds > 0f)
        assertFalse(ranked.contribution("recency")!!.coldStart)
    }
}
