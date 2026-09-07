package org.ort.lexicon

import org.ort.core.AssetRef

/** How a [PhoneticLattice] was produced. Recorded on every record so the source is auditable. */
public enum class LatticeSource {
    /** Pass C acoustic keyword spotting over the retained segment audio (FR-LEX-4). */
    ACOUSTIC,

    /** Degraded lattice expanded from Pass B text where Pass C is unavailable (FR-LEX-6, T0). */
    TEXT_DERIVED,
}

/** One candidate phonetic unit in a slot, with its acoustic log-probability (higher is better). */
public data class UnitScore(val unit: PhoneticUnit, val logProb: Float) {
    init {
        require(logProb.isFinite()) { "logProb must be finite, was $logProb" }
    }
}

/**
 * One time-ordered slot of the acoustic hypothesis. **Alternatives are retained with their
 * scores** (FR-LEX-5) — the lattice is never collapsed to a single best path, because the
 * downstream ranking (P7) depends on having alternatives to choose between.
 */
public data class LatticeSlot(val startMs: Int, val endMs: Int, val alts: List<UnitScore>) {
    init {
        require(startMs <= endMs) { "slot start after end" }
        require(alts.isNotEmpty()) { "a slot with no alternatives is not a hypothesis" }
    }

    /** The single highest-scoring alternative. A convenience for display — never the stored form. */
    public val top: UnitScore get() = alts.maxByOrNull { it.logProb } ?: alts.first()
}

/**
 * A confidence-weighted sequence of candidate phonetic units derived from a transmission
 * (functional spec §4, "Phonetic lattice"). Immutable; the grammar parser reads it and never
 * mutates it.
 */
public data class PhoneticLattice(
    val slots: List<LatticeSlot>,
    val source: LatticeSource,
    val modelRef: AssetRef? = null,
) {
    public val isEmpty: Boolean get() = slots.isEmpty()

    public companion object {
        /**
         * A lattice with one alternative per slot — the shape a text-derived expansion produces,
         * and the convenient form for callers that already have a definite unit sequence.
         * [msPerSlot] only spaces the slots out; it carries no acoustic meaning.
         */
        public fun ofUnits(
            units: List<PhoneticUnit>,
            source: LatticeSource,
            modelRef: AssetRef? = null,
            msPerSlot: Int = 100,
        ): PhoneticLattice = PhoneticLattice(
            slots = units.mapIndexed { i, u ->
                LatticeSlot(i * msPerSlot, (i + 1) * msPerSlot, listOf(UnitScore(u, 0f)))
            },
            source = source,
            modelRef = modelRef,
        )
    }
}
