package org.ort.pipeline.passb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.ort.core.AttributionState
import org.ort.lexicon.CallsignGrammar
import org.ort.lexicon.ConfusionCostMatrix
import org.ort.lexicon.ItuPrefixTable
import org.ort.lexicon.LatticeSource
import org.ort.lexicon.PhoneticLattice
import org.ort.lexicon.PhoneticUnit
import org.ort.lexicon.PriorCombiner
import org.ort.lexicon.RankingContext
import org.ort.lexicon.TextDerivedUnitSpotter
import org.ort.lexicon.UnitSpotter
import org.ort.lexicon.VariantTable
import org.ort.lexicon.fake.FakeUnitSpotter

/**
 * M4.8's *mechanism* (build-plan P11, R3): "same audio, same resolver, same folds" for
 * {text-derived, KWS, encoder-similarity}. This session has no reference device, no real
 * sherpa-onnx KWS binding and no development noise tape (see CHANGELOG), so it cannot run the
 * real dev-fold comparison — what this test proves is that driving multiple [UnitSpotter]s
 * through the identical grammar → priors → [CallsignResolver] chain produces independently
 * inspectable results per spotter, which is the plumbing the real comparison needs. The
 * `FakeUnitSpotter` standing in for "an acoustic spotter" here is not a claim about KWS's or
 * encoder-similarity's real accuracy — it is exactly as informative as its script, no more.
 */
class SpotterComparisonTest {

    private val grammar = CallsignGrammar(ItuPrefixTable.bundled(), ConfusionCostMatrix.bundled())
    private val combiner = PriorCombiner(emptyList())
    private val resolver = CallsignResolver(separationThreshold = 0.01f, confirmThreshold = -1f)

    private fun attributionFor(spotter: UnitSpotter, audio: FloatArray): AttributionState {
        val lattice = spotter.spot(audio)
        if (lattice.isEmpty) return AttributionState.UNKNOWN
        val candidates = grammar.parse(lattice)
        if (candidates.isEmpty()) return AttributionState.UNKNOWN
        return resolver.resolve(combiner.rank(candidates, RankingContext())).state
    }

    @Test
    fun `the same audio run through different spotters can be compared under one resolver`() {
        val audio = FloatArray(16_000) // stands in for "one segment's audio" -- identical for every spotter
        val textDerived = TextDerivedUnitSpotter(VariantTable.bundled()) { "kilo seven alpha bravo charlie" }
        val fakeKws = FakeUnitSpotter(PhoneticLattice.ofUnits(PhoneticUnit.spell("K7ABC"), LatticeSource.ACOUSTIC))
        val silentSpotter = FakeUnitSpotter.spotsNothing()

        assertEquals(AttributionState.CONFIRMED, attributionFor(textDerived, audio))
        assertEquals(AttributionState.CONFIRMED, attributionFor(fakeKws, audio))
        assertEquals(AttributionState.UNKNOWN, attributionFor(silentSpotter, audio))
    }
}
