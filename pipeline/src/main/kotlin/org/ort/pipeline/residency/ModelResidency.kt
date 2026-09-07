package org.ort.pipeline.residency

import org.ort.core.Tier

/** technical design §4.3: how long a loaded model is expected to stay resident. */
public enum class ResidencyClass {
    /** Loaded for the life of the session; never evicted under pressure. */
    PINNED,

    /** Loaded on demand, kept warm for a while, evictable under memory pressure. */
    HOT,

    /** Loaded per-use and released immediately after. */
    COLD,
}

/** One entry in the model registry: its declared memory footprint and residency class. */
public data class ModelDescriptor(val id: String, val residencyClass: ResidencyClass, val residentBytes: Long)

/**
 * The declared memory footprint registry (technical design §4.3, §6.2 → AC-103). No real models
 * exist yet (`:asr-*` isn't built), so this is a registry-shaped interface with declared
 * footprints — [FakeModelResidencyRegistry] is the behavioural fake every test in this module
 * runs against.
 */
public interface ModelResidencyRegistry {
    /** Every model resident *right now* at [tier]. */
    public fun residentAt(tier: Tier): List<ModelDescriptor>
}

public class FakeModelResidencyRegistry(private val byTier: Map<Tier, List<ModelDescriptor>>) : ModelResidencyRegistry {
    override fun residentAt(tier: Tier): List<ModelDescriptor> = byTier[tier].orEmpty()
}

/** Per-tier resident-memory budget (technical design §6.2). */
public data class TierBudget(val tier: Tier, val maxResidentBytes: Long)

public data class BudgetCheck(val tier: Tier, val residentBytes: Long, val budgetBytes: Long) {
    public val withinBudget: Boolean get() = residentBytes <= budgetBytes
}

/** AC-103: the sum of concurrently resident model memory at a tier must fall within its budget. */
public object ResidencyBudgetChecker {
    public fun check(registry: ModelResidencyRegistry, budget: TierBudget): BudgetCheck {
        val resident = registry.residentAt(budget.tier).sumOf { it.residentBytes }
        return BudgetCheck(budget.tier, resident, budget.maxResidentBytes)
    }
}
