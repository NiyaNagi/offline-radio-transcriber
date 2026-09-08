package org.ort.lexicon

/**
 * Per-slot detail for one candidate's phonetic lattice (R-320, FR-UI-8: `Detail-Why.dc.html`
 * section 1, "each unit's score and kept alternate"; R-182, FR-UI-4: the transcript-highlight
 * span). [CallsignGrammar.parse] emits one [SlotDetail] per lattice slot the candidate's parse
 * span covers — [index] is the [PhoneticLattice.slots] index (`0 until lattice.slots.size`, the
 * same range [CallsignCandidate.slotSpan] already carries), not a position within the parsed
 * callsign text: a deletion means the parsed text can be shorter than the number of slots visited.
 *
 * [unit]/[score] are the lattice's own top-scoring alternative at that slot ([LatticeSlot.top]) —
 * "what was heard best" at that instant, a fact about the *lattice*, not about which candidate is
 * being displayed. A confusion-weighted grammar substitution can make a candidate's parsed text
 * differ from a slot's top alt (e.g. `K7LVH` substituting `V` for a `W`-topped slot) — [unit] still
 * reports what the lattice itself heard best there, exactly as the board's own worked example
 * shows a `W .64` slot underneath a `K7LWH` candidate whose own edit penalty already accounts for
 * that gap.
 *
 * [keptAlternate] is the slot's second-best alternative's unit symbol, or `null` when the slot
 * carries only one alternative — never fabricated to fill the field. Every slot in a
 * [LatticeSource.TEXT_DERIVED] lattice has exactly one alternative today
 * ([org.ort.lexicon.VariantTable] resolves one spoken form to exactly one unit, never a ranked
 * set — see [PhoneticLattice.ofUnits]), so [keptAlternate] is `null` for every slot of such a
 * lattice; a real second alternative can only come from an [LatticeSource.ACOUSTIC] lattice whose
 * producer (Pass C spotting) actually kept one.
 *
 * [charStart]/[charEnd] are a half-open `[charStart, charEnd)` character range into the Pass B
 * transcript text this slot's unit was matched against — present only when the lattice was built
 * by *text-anchored* matching ([TextDerivedLatticeBuilder.buildAnchored]) over that exact string;
 * `null`/`null` for every other lattice, including every [LatticeSource.ACOUSTIC] one, which has
 * no transcript-text relationship to offer at all (constitution I: never fabricate what was not
 * computed). Both are null together or non-null together; when non-null, `charStart <= charEnd`.
 */
public data class SlotDetail(
    val index: Int,
    val unit: String,
    val score: Double,
    val keptAlternate: String? = null,
    val charStart: Int? = null,
    val charEnd: Int? = null,
) {
    init {
        require(index >= 0) { "slot index must not be negative, was $index" }
        require(unit.isNotBlank()) { "a slot's unit must not be blank" }
        require((charStart == null) == (charEnd == null)) {
            "charStart and charEnd must be both null or both set, got charStart=$charStart charEnd=$charEnd"
        }
        if (charStart != null && charEnd != null) {
            require(charStart <= charEnd) { "charStart ($charStart) must not be after charEnd ($charEnd)" }
        }
    }
}
