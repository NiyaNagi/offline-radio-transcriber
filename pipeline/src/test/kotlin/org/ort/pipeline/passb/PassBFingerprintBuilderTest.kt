package org.ort.pipeline.passb

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ort.asrapi.rules.RejectionRuleId

/**
 * Register R-1133 (FR-ASR-5, FR-ASR-6; AC-6; constitution I, VI): [PassBFingerprintBuilder
 * .inertControlsSignature] is the deterministic, plain-text rendering of
 * [org.ort.asrapi.PassBOutcome.inertControls] that [DataPassBResultSink] persists beside the
 * rest of a transmission's pass provenance — see that function's own doc comment for why it is
 * comma-joined text, not a SHA-256 hash: constitution I's "every machine conclusion MUST be
 * inspectable" only holds if a debug dump can show which controls were live in plain words.
 */
public class PassBFingerprintBuilderTest {

    @Test
    public fun `R_1133 empty set signs as an empty string, never a fabricated placeholder`() {
        assertEquals("", PassBFingerprintBuilder.inertControlsSignature(emptySet()))
    }

    @Test
    public fun `R_1133 a single inert control signs as its bare name`() {
        assertEquals(
            "NO_SPEECH_PROB",
            PassBFingerprintBuilder.inertControlsSignature(setOf(RejectionRuleId.NO_SPEECH_PROB)),
        )
    }

    @Test
    public fun `R_1133 multiple inert controls are sorted by declared RejectionRuleId order, not insertion order`() {
        // COMPRESSION_RATIO and TOO_SHORT are declared in that reversed order below -- the
        // signature must still read in RejectionRuleId.entries order (TOO_SHORT first),
        // otherwise two identical inert sets built in a different order would sign differently.
        val signature = PassBFingerprintBuilder.inertControlsSignature(
            setOf(RejectionRuleId.COMPRESSION_RATIO, RejectionRuleId.TOO_SHORT, RejectionRuleId.NO_SPEECH_PROB),
        )

        assertEquals("TOO_SHORT,NO_SPEECH_PROB,COMPRESSION_RATIO", signature)
    }

    @Test
    public fun `R_1133 the same set built in a different insertion order signs identically`() {
        val a = PassBFingerprintBuilder.inertControlsSignature(
            setOf(RejectionRuleId.BLOCKLIST, RejectionRuleId.VAD_NO_SPEECH),
        )
        val b = PassBFingerprintBuilder.inertControlsSignature(
            setOf(RejectionRuleId.VAD_NO_SPEECH, RejectionRuleId.BLOCKLIST),
        )

        assertEquals(a, b)
    }
}
