package org.ort.lexicon

/**
 * Builds the T0 degraded [PhoneticLattice] from Pass B text (FR-LEX-6, AC-15): where Pass C
 * acoustic spotting is unavailable, expand the recognised words into the same
 * [PhoneticLattice] type Pass C would have produced, with one alternative per slot and a flat
 * confidence, and `source = TEXT_DERIVED` so every downstream record can tell the two apart
 * (technical design §9.2). Everything past this point — the grammar, the priors, calibration —
 * is identical to the acoustic path; this is exactly what makes M1 buildable before M4 exists,
 * and it is the baseline M4 must beat.
 *
 * An unrecognised token is rejected outright (never guessed at) via [VariantTable.require].
 */
public class TextDerivedLatticeBuilder(private val variants: VariantTable) {

    /** [tokens] are Pass B's recognised words, e.g. `["kilo", "seven", "alpha", "bravo", "charlie"]`. */
    public fun build(tokens: List<String>, msPerSlot: Int = 100): PhoneticLattice {
        val units = tokens.map { variants.require(it) }
        return PhoneticLattice.ofUnits(units, LatticeSource.TEXT_DERIVED, msPerSlot = msPerSlot)
    }
}
