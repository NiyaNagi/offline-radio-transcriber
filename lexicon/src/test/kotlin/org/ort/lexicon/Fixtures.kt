package org.ort.lexicon

internal fun bundledGrammar(): CallsignGrammar =
    CallsignGrammar(ItuPrefixTable.bundled(), ConfusionCostMatrix.bundled())

internal fun latticeOf(vararg units: PhoneticUnit): PhoneticLattice =
    PhoneticLattice.ofUnits(units.toList(), LatticeSource.ACOUSTIC)
