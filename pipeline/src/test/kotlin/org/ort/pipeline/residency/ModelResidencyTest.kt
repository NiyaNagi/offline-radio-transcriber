package org.ort.pipeline.residency

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ort.core.Tier
import org.ort.testing.Requirement

class ModelResidencyTest {

    @Test
    @Requirement("AC-103", "FR-TIER-8")
    fun `AC_103 resident memory at a tier is checked against that tier's budget`() {
        val registry = FakeModelResidencyRegistry(
            mapOf(
                Tier.T0 to listOf(ModelDescriptor("vad", ResidencyClass.PINNED, 5_000_000)),
                Tier.T2 to listOf(
                    ModelDescriptor("vad", ResidencyClass.PINNED, 5_000_000),
                    ModelDescriptor("asr-b", ResidencyClass.HOT, 200_000_000),
                ),
            ),
        )

        val t0 = ResidencyBudgetChecker.check(registry, TierBudget(Tier.T0, maxResidentBytes = 50_000_000))
        assertTrue(t0.withinBudget)

        val t2 = ResidencyBudgetChecker.check(registry, TierBudget(Tier.T2, maxResidentBytes = 100_000_000))
        assertFalse(t2.withinBudget, "205MB resident must fail a 100MB budget")
    }
}
