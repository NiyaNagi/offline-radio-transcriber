package org.ort.pipeline.digest

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.ort.core.Tier
import org.ort.pipeline.capture.CaptureState

/**
 * E2-I03 (`results/e2e-audit/checklist.md`), FR-DIG-5, AC-87, AC-138: every conjunct of
 * [ProseDigestGate.evaluate] falsified on its own, then all five held at once.
 */
class ProseDigestGateTest {

    private fun readySignals() = FakeProseDigestDeviceSignals(idle = true, charging = true)

    @AfterEach
    fun resetCaptureState() {
        CaptureState.idle(clearSession = true)
    }

    @Test
    fun `AC_87_runs_when_every_conjunct_holds`() {
        val decision = ProseDigestGate.evaluate(
            signals = readySignals(),
            tier = Tier.T3,
            enabled = true,
            isCapturing = false,
        )

        assertEquals(ProseDigestGateDecision.Run, decision)
    }

    @Test
    fun `FR_DIG_5_blocked_when_not_idle`() {
        val decision = ProseDigestGate.evaluate(
            signals = FakeProseDigestDeviceSignals(idle = false, charging = true),
            tier = Tier.T3,
            enabled = true,
            isCapturing = false,
        )

        assertEquals(ProseDigestGateDecision.Blocked(setOf(ProseDigestBlockReason.NOT_IDLE)), decision)
    }

    @Test
    fun `FR_DIG_5_blocked_when_not_charging`() {
        val decision = ProseDigestGate.evaluate(
            signals = FakeProseDigestDeviceSignals(idle = true, charging = false),
            tier = Tier.T3,
            enabled = true,
            isCapturing = false,
        )

        assertEquals(ProseDigestGateDecision.Blocked(setOf(ProseDigestBlockReason.NOT_CHARGING)), decision)
    }

    @Test
    fun `FR_DIG_5_blocked_when_capturing`() {
        val decision = ProseDigestGate.evaluate(
            signals = readySignals(),
            tier = Tier.T3,
            enabled = true,
            isCapturing = true,
        )

        assertEquals(ProseDigestGateDecision.Blocked(setOf(ProseDigestBlockReason.CAPTURING)), decision)
    }

    @Test
    fun `AC_138_blocked_when_tier_is_below_t3`() {
        for (tier in listOf(Tier.T0, Tier.T1, Tier.T2)) {
            val decision = ProseDigestGate.evaluate(
                signals = readySignals(),
                tier = tier,
                enabled = true,
                isCapturing = false,
            )

            assertEquals(
                ProseDigestGateDecision.Blocked(setOf(ProseDigestBlockReason.TIER_BELOW_T3)),
                decision,
                "expected tier $tier to be blocked",
            )
        }
    }

    @Test
    fun `FR_DIG_3b_blocked_when_disabled`() {
        val decision = ProseDigestGate.evaluate(
            signals = readySignals(),
            tier = Tier.T3,
            enabled = false,
            isCapturing = false,
        )

        assertEquals(ProseDigestGateDecision.Blocked(setOf(ProseDigestBlockReason.DISABLED)), decision)
    }

    @Test
    fun `blocked reasons accumulate when several conjuncts fail at once`() {
        val decision = ProseDigestGate.evaluate(
            signals = FakeProseDigestDeviceSignals(idle = false, charging = false),
            tier = Tier.T0,
            enabled = false,
            isCapturing = true,
        )

        assertEquals(
            ProseDigestGateDecision.Blocked(
                setOf(
                    ProseDigestBlockReason.NOT_IDLE,
                    ProseDigestBlockReason.NOT_CHARGING,
                    ProseDigestBlockReason.CAPTURING,
                    ProseDigestBlockReason.TIER_BELOW_T3,
                    ProseDigestBlockReason.DISABLED,
                ),
            ),
            decision,
        )
    }

    @Test
    fun `FR_DIG_5_default_isCapturing_reads_the_real_CaptureState_when_not_supplied`() {
        CaptureState.capturing("session-1")

        val blockedWhileCapturing = ProseDigestGate.evaluate(signals = readySignals(), tier = Tier.T3, enabled = true)
        assertEquals(
            ProseDigestGateDecision.Blocked(setOf(ProseDigestBlockReason.CAPTURING)),
            blockedWhileCapturing,
        )

        CaptureState.idle(clearSession = true)

        val runsWhileIdle = ProseDigestGate.evaluate(signals = readySignals(), tier = Tier.T3, enabled = true)
        assertEquals(ProseDigestGateDecision.Run, runsWhileIdle)
    }
}
