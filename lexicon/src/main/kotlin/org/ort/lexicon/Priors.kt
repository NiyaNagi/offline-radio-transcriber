package org.ort.lexicon

import kotlin.math.exp

/** Frequency / repeater match from the Rig Module — very strong when present (technical design §9.3). */
public class FrequencyPrior(clamp: PriorClamp = PriorClamp.symmetric(3.0f)) : AbstractPrior("frequency", clamp) {
    override fun isColdStart(candidate: CallsignCandidate, context: RankingContext): Boolean = context.repeater == null

    override fun rawLogOdds(candidate: CallsignCandidate, context: RankingContext): Float {
        val repeater = context.repeater!!
        return when {
            candidate.text in repeater.expectedCallsigns -> clamp.positive
            repeater.expectedCallsigns.isEmpty() -> 0f
            else -> -1.0f
        }
    }
}

/** Band plausibility — the static propagation model (FR-LEX-25..27), strong but asymmetric. */
public class BandPlausibilityPrior(
    private val model: PropagationModel,
    clamp: PriorClamp = PriorClamp(positive = 0.5f, negative = -1.5f),
) : AbstractPrior("band-plausibility", clamp) {
    override fun isColdStart(candidate: CallsignCandidate, context: RankingContext): Boolean =
        context.propagation == null

    override fun rawLogOdds(candidate: CallsignCandidate, context: RankingContext): Float {
        // FR-LEX-27: suppressed entirely on a known repeater / internet-linked frequency — a DX
        // call there is normal, not implausible, and the suppression is a decision, not "no data".
        if (context.repeater?.isKnownRepeaterOrInternetLinked == true) return 0f
        return model.plausibilityLogOdds(context.propagation!!)
    }
}

/** Database presence — moderate; absence is only weak evidence against (technical design §9.3). */
public class DatabasePresencePrior(clamp: PriorClamp = PriorClamp(positive = 1.0f, negative = -0.3f)) :
    AbstractPrior("database", clamp) {
    override fun isColdStart(candidate: CallsignCandidate, context: RankingContext): Boolean =
        context.databaseHits == null

    override fun rawLogOdds(candidate: CallsignCandidate, context: RankingContext): Float =
        if (candidate.text in context.databaseHits!!) 1.0f else -0.3f
}

/** Recency — heard in this session or historically. Strong once warm, useless (zero) cold (FR-LEX-31). */
public class RecencyPrior(
    clamp: PriorClamp = PriorClamp.symmetric(2.0f),
    private val halfLifeSeconds: Double = 3_600.0,
) : AbstractPrior("recency", clamp) {
    override fun isColdStart(candidate: CallsignCandidate, context: RankingContext): Boolean = context.recency == null

    override fun rawLogOdds(candidate: CallsignCandidate, context: RankingContext): Float {
        val info = context.recency!![candidate.text] ?: return 0f
        val decay = exp(-info.secondsSinceLastHeard / halfLifeSeconds)
        return (clamp.positive * decay).toFloat()
    }
}

/** Geographic prefix vs. operator location — weak alone, useful combined with band. */
public class GeographicPrior(clamp: PriorClamp = PriorClamp.symmetric(0.8f), private val falloffKm: Double = 20_000.0) :
    AbstractPrior("geographic", clamp) {
    override fun isColdStart(candidate: CallsignCandidate, context: RankingContext): Boolean =
        context.geographicDistanceKm == null

    override fun rawLogOdds(candidate: CallsignCandidate, context: RankingContext): Float {
        val distance = context.geographicDistanceKm!!(candidate.parsed.prefix) ?: return 0f
        val score = (1.0 - distance / falloffKm).coerceIn(-1.0, 1.0)
        return (clamp.positive * score).toFloat()
    }
}

/** Conversation context — the other station already identified. Strong once available. */
public class ConversationContextPrior(clamp: PriorClamp = PriorClamp.symmetric(2.0f)) :
    AbstractPrior("conversation", clamp) {
    override fun isColdStart(candidate: CallsignCandidate, context: RankingContext): Boolean =
        context.conversation == null

    override fun rawLogOdds(candidate: CallsignCandidate, context: RankingContext): Float =
        if (context.conversation!!.otherStationIdentified) clamp.positive else 0f
}

/** The user's own "my stations" list (FR-LEX-14) — a strong recency-class prior. */
public class MyStationsPrior(clamp: PriorClamp = PriorClamp.symmetric(2.0f)) : AbstractPrior("my-stations", clamp) {
    override fun isColdStart(candidate: CallsignCandidate, context: RankingContext): Boolean =
        context.myStations == null

    override fun rawLogOdds(candidate: CallsignCandidate, context: RankingContext): Float =
        if (candidate.text in context.myStations!!) clamp.positive else 0f
}

/** The full set of priors technical design §9.3 lists, wired to one [PropagationModel] instance. */
public fun defaultPriors(model: PropagationModel = PropagationModel()): List<Prior> = listOf(
    FrequencyPrior(),
    BandPlausibilityPrior(model),
    DatabasePresencePrior(),
    RecencyPrior(),
    GeographicPrior(),
    ConversationContextPrior(),
    MyStationsPrior(),
)
