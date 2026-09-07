package org.ort.lexicon

/**
 * The bounds within which a prior's log-odds contribution is clamped (FR-LEX-9). Clamping is
 * how "no prior may act as a hard filter" becomes structural rather than a rule an
 * implementation must remember (constitution VII): [AbstractPrior.evaluate] applies it, not
 * the individual prior.
 */
public data class PriorClamp(val positive: Float, val negative: Float) {
    init {
        require(positive >= 0f) { "positive clamp must be >= 0, was $positive" }
        require(negative <= 0f) { "negative clamp must be <= 0, was $negative" }
    }

    public fun clamp(value: Float): Float = value.coerceIn(negative, positive)

    public companion object {
        public fun symmetric(magnitude: Float): PriorClamp = PriorClamp(magnitude, -magnitude)
    }
}

/**
 * One prior's contribution to ranking a candidate (technical design §9.3). [logOdds] is always
 * already clamped. [coldStart] is true exactly when the prior had no basis to say anything for
 * this candidate (FR-LEX-31) — in which case [logOdds] is exactly `0f`, never a default.
 */
public data class PriorContribution(val name: String, val logOdds: Float, val coldStart: Boolean = false) {
    init {
        require(logOdds.isFinite()) { "logOdds must be finite, was $logOdds" }
        require(!coldStart || logOdds == 0f) { "a cold-start contribution must be exactly zero, was $logOdds" }
    }
}

/** A single ranking prior (technical design §9.3, FR-LEX-9). */
public interface Prior {
    public val name: String
    public val clamp: PriorClamp
    public fun evaluate(candidate: CallsignCandidate, context: RankingContext): PriorContribution
}

/**
 * Base class every real prior extends. Clamping and the cold-start-is-exactly-zero rule are
 * enforced here, once, rather than trusted to each subclass — the only way "no prior can
 * eliminate a candidate" (FR-LEX-9) and "cold start contributes exactly zero" (FR-LEX-31) hold
 * for every prior including ones added later.
 */
public abstract class AbstractPrior(final override val name: String, final override val clamp: PriorClamp) : Prior {

    /** True when this prior has no basis to say anything about [candidate] (FR-LEX-31). */
    protected open fun isColdStart(candidate: CallsignCandidate, context: RankingContext): Boolean = false

    /** The raw, unclamped log-odds. Never consulted when [isColdStart] returns true. */
    protected abstract fun rawLogOdds(candidate: CallsignCandidate, context: RankingContext): Float

    final override fun evaluate(candidate: CallsignCandidate, context: RankingContext): PriorContribution =
        if (isColdStart(candidate, context)) {
            PriorContribution(name, 0f, coldStart = true)
        } else {
            PriorContribution(name, clamp.clamp(rawLogOdds(candidate, context)))
        }
}
