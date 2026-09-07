package org.ort.eval

import org.ort.lexicon.CallsignGrammar
import org.ort.lexicon.ConfusionCostMatrix
import org.ort.lexicon.ItuPrefixTable
import org.ort.lexicon.LatticeSource
import org.ort.lexicon.PhoneticLattice
import org.ort.lexicon.PhoneticUnit
import org.ort.lexicon.PriorCombiner
import org.ort.lexicon.PropagationModel
import org.ort.lexicon.RankingContext
import org.ort.lexicon.defaultPriors

internal fun bundledGrammar(): CallsignGrammar =
    CallsignGrammar(ItuPrefixTable.bundled(), ConfusionCostMatrix.bundled())

internal fun bundledCombiner(): PriorCombiner = PriorCombiner(defaultPriors(PropagationModel()))

internal fun acousticLattice(callsign: String): PhoneticLattice =
    PhoneticLattice.ofUnits(PhoneticUnit.spell(callsign), LatticeSource.ACOUSTIC)

/** A positive example: a lattice that resolves to [callsign], with ground truth [callsign]. */
internal fun positive(id: String, callsign: String, context: RankingContext = RankingContext()): LabeledOccurrence =
    LabeledOccurrence(id, "synthetic", acousticLattice(callsign), context, truthCallsign = callsign)

/** A negative example: noise-shaped audio with no callsign present. Spelling an unallocated
 * prefix reliably produces zero candidates, which is what "no callsign here" looks like. */
internal fun negative(id: String, context: RankingContext = RankingContext()): LabeledOccurrence =
    LabeledOccurrence(id, "synthetic", acousticLattice("0X1AA"), context, truthCallsign = null)
