package org.ort.core.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * FR-SEG-10: the closed set of VAD detector identities, and the single derived rule for whether
 * one conforms to FR-SEG-1 (the two detectors it names, Silero and TEN-VAD — never the energy
 * fallback, never an unrecorded/pre-migration row). `:core` has no dependency on `:testing`
 * (this module's own build.gradle.kts, "NO dependency on any other module") so this carries no
 * `@Requirement` tag — the coverage-matrix-visible proof for AC-162/FR-SEG-10 lives in `:data`,
 * `:pipeline` and `:app`, where the real write/read paths this enum backs are exercised.
 */
class VadDetectorKindTest {

    @Test
    fun `the VAD detector kind set is exactly the four closed values`() {
        assertEquals(
            setOf("UNKNOWN", "SILERO", "TEN_VAD", "ENERGY"),
            VadDetectorKind.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun `only SILERO and TEN_VAD conform to FR-SEG-1 -- ENERGY and UNKNOWN never do`() {
        assertTrue(VadDetectorKind.SILERO.conformsToFrSeg1)
        assertTrue(VadDetectorKind.TEN_VAD.conformsToFrSeg1)
        assertFalse(
            VadDetectorKind.ENERGY.conformsToFrSeg1,
            "the energy fallback is never one of the detectors FR-SEG-1 names",
        )
        assertFalse(
            VadDetectorKind.UNKNOWN.conformsToFrSeg1,
            "an unrecorded/pre-migration detector must never read as conforming (constitution I)",
        )
    }
}
