package org.ort.pipeline.rig

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.ort.core.Tier
import org.ort.core.capture.CaptureMode
import org.ort.segment.Segmenter
import org.ort.testing.Requirement

/**
 * E2-D09, constitution III ("the segmenter is the one decision reprocessing cannot undo... the
 * segmenter MUST NOT accept a tier argument at all"): this wave adds capture modes throughout the
 * pipeline (`CaptureConfiguration`, `RigSupervisor`, session facts) but [Segmenter]'s own
 * constructor carries no [CaptureMode] or [Tier]. Reflection, not a compile-time citation alone,
 * so a future change that quietly adds either parameter fails this test rather than only a
 * code-review comment.
 *
 * WPSEQUELCH (FR-SEG-5): the constructor grew a fifth parameter, `squelchGate:
 * org.ort.segment.SquelchGate?` — FR-SEG-5's rig-squelch fusion input, a plain data-carrying type
 * with no Tier or CaptureMode inside it (see `org.ort.segment.Squelch.kt`). The count below moved
 * from 4 to 5 for exactly that addition; the two type-absence assertions, the actual guarantee
 * this test exists to enforce, are unchanged.
 */
class SegmenterHasNoModeOrTierTest {

    @Test
    @Requirement("FR-SEG-7")
    fun `E2_D09 Segmenter's constructor takes no CaptureMode and no Tier parameter`() {
        // Kotlin emits a second, synthetic constructor for default-argument dispatch (squelchGate
        // and originSample both have defaults) -- the real one is the one WITHOUT the trailing
        // synthetic-marker params, i.e. the one with the fewest parameters.
        val ctor = Segmenter::class.java.constructors.minByOrNull { it.parameterCount }!!
        val paramTypes = ctor.parameterTypes.toList()

        assertFalse(
            paramTypes.any { it == Tier::class.java },
            "the segmenter MUST NOT accept a tier argument at all (constitution III)",
        )
        assertFalse(
            paramTypes.any { it == CaptureMode::class.java },
            "the segmenter MUST NOT accept a capture-mode argument (constitution III / E2-D09)",
        )
        assertEquals(
            5,
            paramTypes.size,
            "SegmentConfig, Vad, SegmentSink, an optional FR-SEG-5 SquelchGate and an optional " +
                "origin sample -- WPSEQUELCH added the SquelchGate parameter",
        )
    }
}
