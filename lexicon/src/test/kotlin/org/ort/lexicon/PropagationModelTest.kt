package org.ort.lexicon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** FR-LEX-25..27: static offline propagation model, asymmetric and weak in the negative
 * direction, suppressed entirely on known repeater/internet-linked frequencies. */
class PropagationModelTest {

    private val model = PropagationModel()
    private val candidate = grammarCandidate("VK3MMM")
    private val prior = BandPlausibilityPrior(model)

    @Test
    fun `FR_LEX_26 an implausible DX path demotes but the clamp keeps it above elimination`() {
        val implausible = PropagationInputs(band = "VHF", localHour = 12, season = Season.SUMMER, distanceKm = 16_000.0)
        val contribution = prior.evaluate(candidate, RankingContext(propagation = implausible))
        assertTrue(contribution.logOdds < 0f, "an implausible VHF DX path should demote")
        assertTrue(contribution.logOdds >= prior.clamp.negative, "must never exceed the declared negative clamp")
    }

    @Test
    fun `FR_LEX_26 the negative clamp bound is larger in magnitude than the positive one`() {
        assertTrue(
            prior.clamp.positive < -prior.clamp.negative,
            "weak positive, larger negative bound is the spec's asymmetry",
        )
    }

    @Test
    fun `FR_LEX_27 the prior is suppressed entirely on a known repeater or internet-linked frequency`() {
        val implausible = PropagationInputs(band = "VHF", localHour = 12, season = Season.SUMMER, distanceKm = 16_000.0)
        val ctx = RankingContext(
            propagation = implausible,
            repeater = RepeaterMatch(146_940_000, setOf("W7ABC"), isKnownRepeaterOrInternetLinked = true),
        )
        val contribution = prior.evaluate(candidate, ctx)
        assertEquals(0f, contribution.logOdds, "a DX call on a linked repeater is normal, not implausible")
        assertTrue(!contribution.coldStart, "suppression is a decision, not an absence of data")
    }

    @Test
    fun `FR_LEX_25 the model is a pure static function of band, time, season and distance only`() {
        val a = model.plausibilityLogOdds(PropagationInputs("20M", 14, Season.WINTER, 8000.0))
        val b = model.plausibilityLogOdds(PropagationInputs("20M", 14, Season.WINTER, 8000.0))
        assertEquals(a, b, "the model must be deterministic — no network, no live solar data")
    }
}
