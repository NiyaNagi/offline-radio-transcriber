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

    /**
     * R-182/FR-UI-4, R-320/FR-UI-8: like [build], but over [sourceText] directly (whitespace-split,
     * unlike [build]'s pre-split [tokens]) and — the reason this overload exists — recording each
     * kept unit's real `[charStart, charEnd)` offset into [sourceText] on its [LatticeSlot]
     * (constitution I: a fabricated span would be worse than none). An unrecognised token is
     * silently excluded rather than aborting the whole text, the same choice `:pipeline`'s own
     * `PassB.resolveFromText` already makes for continuous speech containing many non-spelled
     * words — [build] keeps its stricter "throw on the first unknown word" contract for callers
     * that already have a definite, pre-filtered unit sequence and want that guarantee.
     */
    public fun buildAnchored(sourceText: String, msPerSlot: Int = 100): PhoneticLattice {
        val slots = mutableListOf<LatticeSlot>()
        var slotIndex = 0
        for (match in TOKEN_PATTERN.findAll(sourceText)) {
            val unit = variants.resolve(match.value) ?: continue
            slots += LatticeSlot(
                startMs = slotIndex * msPerSlot,
                endMs = (slotIndex + 1) * msPerSlot,
                alts = listOf(UnitScore(unit, 0f)),
                charStart = match.range.first,
                charEnd = match.range.last + 1,
            )
            slotIndex++
        }
        return PhoneticLattice(slots, LatticeSource.TEXT_DERIVED)
    }

    private companion object {
        val TOKEN_PATTERN = Regex("""\S+""")
    }
}
