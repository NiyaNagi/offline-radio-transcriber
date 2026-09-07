package org.ort.segment

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.ort.core.Tier
import org.ort.testing.Requirement
import kotlin.reflect.KFunction

class SegmenterTierRejectionTest {

    private fun assertNoTierParam(ctors: Collection<KFunction<*>>) {
        for (ctor in ctors) {
            assertFalse(
                ctor.parameters.any { it.type.classifier == Tier::class },
                "constructor $ctor must not accept a Tier (segmentation is tier-invariant)",
            )
        }
    }

    @Test
    @Requirement("AC-94", "FR-SEG-7", "CON-SEG-1")
    fun `AC_94 Segmenter cannot be constructed with a Tier`() {
        assertNoTierParam(Segmenter::class.constructors)
    }

    @Test
    @Requirement("AC-94", "FR-SEG-7")
    fun `AC_94 SegmentConfig cannot be constructed with a Tier`() {
        assertNoTierParam(SegmentConfig::class.constructors)
    }
}
