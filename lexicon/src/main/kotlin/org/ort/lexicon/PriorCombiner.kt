package org.ort.lexicon

/**
 * A [CallsignCandidate] ranked with its prior contributions attached, one per prior, so any
 * resolution is auditable (constitution I, FR-UI-8) and re-rankable (FR-LEX-12) without
 * recomputing the priors.
 */
public data class RankedCandidate(val candidate: CallsignCandidate, val contributions: List<PriorContribution>) {

    /** Sum of the already-clamped prior log-odds. */
    public val priorLogOdds: Float get() = contributions.sumOf { it.logOdds.toDouble() }.toFloat()

    /** The acoustic/edit score plus every prior's clamped contribution — the ranking key. */
    public val totalScore: Float get() = candidate.score + priorLogOdds

    public val coldPriorNames: List<String> get() = contributions.filter { it.coldStart }.map { it.name }

    /**
     * Widens the reported confidence interval in proportion to how many priors had nothing to
     * say (FR-LEX-31): cold start must not distort the ranking, only the stated uncertainty.
     */
    public val intervalWidening: Float get() = coldPriorNames.size * WIDENING_PER_COLD_PRIOR

    public fun contribution(name: String): PriorContribution? = contributions.firstOrNull { it.name == name }

    private companion object {
        const val WIDENING_PER_COLD_PRIOR = 0.05f
    }
}

/**
 * Combines a candidate's acoustic/edit score with every prior's clamped log-odds contribution
 * (technical design §9.3, FR-LEX-9). `AMBIGUOUS` declaration (FR-LEX-11) reads [RankedCandidate]
 * downstream; this class only ranks and explains.
 */
public class PriorCombiner(private val priors: List<Prior>) {

    public val priorNames: List<String> get() = priors.map { it.name }

    /** Ranks [candidates] under one shared [context], best first. Every input candidate survives
     * — ranking can only reorder, never eliminate (FR-LEX-9). */
    public fun rank(candidates: List<CallsignCandidate>, context: RankingContext): List<RankedCandidate> = candidates
        .map { candidate -> RankedCandidate(candidate, priors.map { it.evaluate(candidate, context) }) }
        .sortedByDescending { it.totalScore }

    /** A copy of this combiner with one named prior removed — the harness's per-prior ablation. */
    public fun withoutPrior(name: String): PriorCombiner {
        require(priors.any { it.name == name }) { "no prior named '$name' among $priorNames" }
        return PriorCombiner(priors.filterNot { it.name == name })
    }
}
