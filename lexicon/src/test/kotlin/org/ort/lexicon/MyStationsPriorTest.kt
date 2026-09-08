package org.ort.lexicon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * FR-LEX-14: the user-editable "my stations" list receives a strong recency-class prior. The
 * list's storage/editing lives above `:lexicon` (data/app layer); what belongs here, and what
 * this test establishes, is that a station on the list is boosted by a magnitude matching the
 * other recency-class priors (FR-LEX-9), never merely nudged, while a station not on it is
 * unaffected rather than penalised.
 */
class MyStationsPriorTest {

    private val onList = grammarCandidate("VK3MMM")
    private val notOnList = grammarCandidate("K7ABC")

    @Test
    fun `FR_LEX_14 a station on the my-stations list receives the prior's full positive clamp`() {
        val prior = MyStationsPrior()
        val context = RankingContext(myStations = setOf("VK3MMM"))
        val contribution = prior.evaluate(onList, context)
        assertEquals(prior.clamp.positive, contribution.logOdds)
        assertTrue(contribution.logOdds > 0f)
    }

    @Test
    fun `FR_LEX_14 a station absent from a configured my-stations list contributes zero, not a penalty`() {
        val prior = MyStationsPrior()
        val context = RankingContext(myStations = setOf("VK3MMM"))
        val contribution = prior.evaluate(notOnList, context)
        assertEquals(0f, contribution.logOdds)
    }

    @Test
    fun `FR_LEX_14 the prior's magnitude matches the other recency-class priors (FR-LEX-9)`() {
        val myStations = MyStationsPrior()
        val recency = RecencyPrior()
        assertEquals(
            recency.clamp.positive,
            myStations.clamp.positive,
            "FR-LEX-14 names this a recency-class prior — same order of magnitude as RecencyPrior",
        )
    }

    @Test
    fun `FR_LEX_14 with no my-stations list configured at all, the prior is cold and contributes zero`() {
        val prior = MyStationsPrior()
        val contribution = prior.evaluate(onList, RankingContext(myStations = null))
        assertTrue(contribution.coldStart)
        assertEquals(0f, contribution.logOdds)
    }
}
