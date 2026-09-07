package org.ort.lexicon

import org.ort.core.AssetRef

/**
 * Confusion-weighted substitution costs for the grammar beam search (FR-LEX-10, technical
 * design §9.2). Edit distance over phonetic units is **not** uniform Levenshtein: swapping one
 * member of a known ASR confusion set (the E-set `B D E P V T`, the nasal pair `M N`, the
 * fricative pair `S F`, …) for another costs a fraction of an arbitrary substitution, so a
 * near-miss along a plausible acoustic axis outranks a near-miss along an implausible one.
 *
 * Symmetric by construction. Identity costs 0; an unlisted pair costs [defaultCost] (1.0).
 * The matrix is a bundled, independently versioned asset — initialised from the literature,
 * refit from measured M0 confusion counts later (a P7 concern).
 */
public class ConfusionCostMatrix internal constructor(
    private val costs: Map<Pair<PhoneticUnit, PhoneticUnit>, Float>,
    public val version: AssetRef,
    public val defaultCost: Float = 1.0f,
) {
    /** The cost of substituting [from] with [to]. `0` when equal, [defaultCost] when unlisted. */
    public fun substitutionCost(from: PhoneticUnit, to: PhoneticUnit): Float = when {
        from == to -> 0f
        else -> costs[from to to] ?: defaultCost
    }

    /** The units [unit] is confusable with — those whose substitution cost is below [defaultCost]. */
    public fun confusableWith(unit: PhoneticUnit): List<PhoneticUnit> =
        costs.entries.filter { it.key.first == unit && it.value < defaultCost }.map { it.key.second }

    public companion object {
        private const val RESOURCE = "/org/ort/lexicon/confusion-costs.tsv"

        /** Load the bundled confusion cost matrix. */
        public fun bundled(): ConfusionCostMatrix {
            val tsv = readResource(RESOURCE)
            val map = HashMap<Pair<PhoneticUnit, PhoneticUnit>, Float>()
            tsvRows(tsv).forEach { cols ->
                require(cols.size >= 3) { "confusion row needs 3 columns: ${cols.joinToString("|")}" }
                val a = PhoneticUnit.valueOf(cols[0])
                val b = PhoneticUnit.valueOf(cols[1])
                val cost = cols[2].toFloat()
                require(cost in 0f..1f) { "confusion cost $cost outside [0,1] for $a/$b" }
                map[a to b] = cost
                map[b to a] = cost
            }
            return ConfusionCostMatrix(map, AssetRef("lexicon-confusion-costs", readVersion(tsv)))
        }
    }
}
