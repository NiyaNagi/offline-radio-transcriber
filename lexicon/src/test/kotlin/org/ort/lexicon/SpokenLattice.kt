package org.ort.lexicon

/**
 * Test-only convenience: turn a spoken phrase ("kilo seven alpha bravo charlie") into a
 * single-alternative [PhoneticLattice] via the bundled [VariantTable]. The production
 * text-derived lattice builder (FR-LEX-6 / AC-15) is P7's; this is just a fixture helper.
 */
internal object SpokenLattice {
    private val variants = VariantTable.bundled()

    fun of(phrase: String, source: LatticeSource = LatticeSource.TEXT_DERIVED): PhoneticLattice =
        PhoneticLattice.ofUnits(
            phrase.trim().split(Regex("\\s+")).map { variants.require(it) },
            source,
        )
}
