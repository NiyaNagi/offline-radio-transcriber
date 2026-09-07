package org.ort.asrapi

import org.ort.core.Outcome
import org.ort.core.Tier
import org.ort.onnx.ModelDescriptor

/** One tier's ranked candidate models, most-preferred first (technical design §8.4). */
public data class ModelSet(val tier: Tier, val candidates: List<ModelDescriptor>) {
    init {
        require(candidates.isNotEmpty()) { "a ModelSet must declare at least one candidate for $tier" }
    }
}

/** Surfaced when the preferred model for a tier could not be activated and a lower one was used instead (F13). */
public data class ModelFallback(val requested: ModelDescriptor, val activated: ModelDescriptor, val reason: String)

/**
 * Resolves a [Tier] and provider to an activated model, falling back to the next-lower
 * candidate — and surfacing that fallback — when the preferred one is missing or invalid
 * (technical design §8.4, F13). [validate] is injected so this stays testable without a real
 * runtime: in production it is backed by `:onnx`'s `OnnxSessionFactory.load` + `probeRun`.
 */
public class ModelRegistry(
    private val modelSets: Map<Tier, ModelSet>,
    private val validate: (ModelDescriptor) -> Outcome<Unit>,
) {
    /**
     * Walks [ModelSet.candidates] for [tier] in preference order, activating the first one
     * [validate] accepts. Returns [Outcome.Err] only if every candidate for the tier fails —
     * there is no lower tier left to fall back to inside a single [ModelSet].
     */
    public fun resolve(tier: Tier): Outcome<Resolution> {
        val set = modelSets[tier] ?: return Outcome.Err("no ModelSet declared for tier $tier")
        var fallback: ModelFallback? = null
        for ((index, candidate) in set.candidates.withIndex()) {
            val result = validate(candidate)
            if (result is Outcome.Ok) {
                return Outcome.Ok(
                    Resolution(
                        descriptor = candidate,
                        fallback = if (index == 0) null else fallback?.copy(activated = candidate),
                    ),
                )
            }
            if (index == 0) {
                fallback = ModelFallback(
                    requested = candidate,
                    activated = candidate, // overwritten with the actually-activated one above, if any
                    reason = (result as Outcome.Err).reason,
                )
            }
        }
        return Outcome.Err("every candidate model for tier $tier failed validation")
    }

    /** The activated model, plus the fallback event if the preferred one was not what activated (F13). */
    public data class Resolution(val descriptor: ModelDescriptor, val fallback: ModelFallback?)
}
