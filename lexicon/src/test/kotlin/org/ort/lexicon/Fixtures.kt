package org.ort.lexicon

internal fun bundledGrammar(): CallsignGrammar =
    CallsignGrammar(ItuPrefixTable.bundled(), ConfusionCostMatrix.bundled())

internal fun latticeOf(vararg units: PhoneticUnit): PhoneticLattice =
    PhoneticLattice.ofUnits(units.toList(), LatticeSource.ACOUSTIC)

/** Test-only convenience: the top structurally valid candidate the bundled grammar produces for [callsign]. */
internal fun grammarCandidate(callsign: String): CallsignCandidate = bundledGrammar()
    .parse(PhoneticLattice.ofUnits(PhoneticUnit.spell(callsign), LatticeSource.ACOUSTIC))
    .first { it.text == callsign }
