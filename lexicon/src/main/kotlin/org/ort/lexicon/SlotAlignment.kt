package org.ort.lexicon

/**
 * One lattice slot's outcome on a *specific* [CallsignCandidate]'s own winning path through
 * [CallsignGrammar]'s beam search (register R-1148, split from R-1144/R-1117; FR-UI-8,
 * constitution I). Recorded live inside [CallsignGrammar.expand] as each path is built, never
 * re-derived afterward by comparing text — so it is a fact about the path the beam search
 * actually walked to reach this candidate, not a guess reconstructed from its final text.
 *
 * This is the piece [SlotDetail] cannot be, by that type's own doc comment: [SlotDetail] is one
 * list per *lattice*, "identical across every candidate `parse` returns for the same lattice";
 * [SlotAlignment] is one list per *candidate*, and two candidates parsed from the same lattice
 * normally disagree at some slot — that disagreement is exactly what makes a per-candidate reason
 * honest rather than accidentally true for the winner and fabricated for everyone else.
 *
 * [latticeUnit] is the slot's own top pick ([LatticeSlot.top]) — a fact about the lattice, not
 * about this candidate, carried here so a caller never needs a second lookup to compare against
 * it. [candidateUnit] is `null` exactly when this candidate's path *deleted* this slot — treated
 * the audio at this position as no part of the callsign at all, the one way this grammar can make
 * a candidate shorter than the lattice it was parsed from (there is no symmetric "insertion"
 * branch: a candidate can never be *longer* than the lattice's own slot count). [offeredByLattice]
 * is `true` iff [candidateUnit] equals one of this slot's own [LatticeSlot.alts] — genuinely
 * present in what the lattice recorded, whether the top pick or a kept alternate — and is always
 * `false` when [candidateUnit] is `null` (a deletion is never "offered"). A `false` value paired
 * with a non-null [candidateUnit] means the confusion matrix substituted a unit this slot never
 * listed at all.
 *
 * None of this speaks to acoustic confidence: [LatticeSlot.alts]' own `logProb` is a text-derived
 * placeholder today, never a real acoustic score (register R-1121) — these three fields report
 * presence, absence and mismatch only, never "heard weakly" or "heard strongly" (constitution I).
 */
public data class SlotAlignment(
    val slotIndex: Int,
    val latticeUnit: String,
    val candidateUnit: String?,
    val offeredByLattice: Boolean,
) {
    init {
        require(slotIndex >= 0) { "slot index must not be negative, was $slotIndex" }
        require(latticeUnit.isNotBlank()) { "a slot's lattice unit must not be blank" }
        require(candidateUnit != null || !offeredByLattice) {
            "a deleted slot (candidateUnit == null) can never be offeredByLattice"
        }
    }

    /** True when this candidate's path agrees with the lattice's own top pick at this slot. */
    public val matches: Boolean get() = candidateUnit == latticeUnit
}
