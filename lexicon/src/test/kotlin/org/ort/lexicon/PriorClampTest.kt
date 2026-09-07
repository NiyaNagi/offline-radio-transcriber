package org.ort.lexicon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** FR-LEX-9: each prior's contribution is clamped, so no prior can eliminate a candidate. */
class PriorClampTest {

    private val candidate = grammarCandidate("VK3MMM")

    @Test
    fun `FR_LEX_9 a prior cannot exceed its declared clamp regardless of an adversarial raw signal`() {
        val hostilePrior = object : AbstractPrior("hostile", PriorClamp.symmetric(1.0f)) {
            override fun rawLogOdds(candidate: CallsignCandidate, context: RankingContext) = -1_000_000f
        }
        val contribution = hostilePrior.evaluate(candidate, RankingContext())
        assertEquals(-1.0f, contribution.logOdds)
    }

    @Test
    fun `FR_LEX_9 an asymmetric clamp bounds positive and negative independently`() {
        val prior = object : AbstractPrior("asym", PriorClamp(0.5f, -1.5f)) {
            override fun rawLogOdds(candidate: CallsignCandidate, context: RankingContext) = 50f
        }
        assertEquals(0.5f, prior.evaluate(candidate, RankingContext()).logOdds)
    }

    @Test
    fun `FR_LEX_9 combining every real prior at its most negative still leaves the candidate ranked`() {
        val combiner = PriorCombiner(defaultPriors(PropagationModel()))
        val hostileContext = RankingContext(
            repeater = RepeaterMatch(146_520_000, setOf("W1AW")),
            databaseHits = emptySet(),
            recency = emptyMap(),
            geographicDistanceKm = { 19_999.0 },
            conversation = ConversationInfo(otherStationIdentified = false),
            myStations = emptySet(),
            propagation = PropagationInputs("VHF", localHour = 14, season = Season.WINTER, distanceKm = 19_000.0),
        )
        val ranked = combiner.rank(listOf(candidate), hostileContext)
        assertEquals(1, ranked.size, "the candidate must survive even the most hostile combination of priors")
        assertTrue(ranked.single().totalScore.isFinite())
    }
}
