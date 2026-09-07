package org.ort.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class ResolvedConfigTest {

    @Test
    fun `FR_CFG_1 merge precedence is defaults then profile then overrides`() {
        val defaults = ConfigLayer.of("capture.preRollMs" to 1200, "segment.minSpeechMs" to 250)
        val profile = ConfigLayer.of("capture.preRollMs" to 1500)
        val overrides = ConfigLayer.of("capture.preRollMs" to 2000)

        assertEquals(1500, ResolvedConfig.resolve(defaults, profile).capturePreRollMs)
        assertEquals(2000, ResolvedConfig.resolve(defaults, profile, overrides).capturePreRollMs)
        assertEquals(250, ResolvedConfig.resolve(defaults, profile, overrides).segmentMinSpeechMs)
    }

    @Test
    fun `FR_CFG_2 typed fields fall back to their documented defaults`() {
        val cfg = ResolvedConfig.resolve(ConfigLayer.EMPTY)
        assertEquals(ResolvedConfig.DEF_PRE_ROLL_MS, cfg.capturePreRollMs)
        assertEquals(ResolvedConfig.DEF_NO_SPEECH_PROB_MAX, cfg.asrNoSpeechProbMax)
        assertEquals(ResolvedConfig.DEF_PASS_RETRY_LIMIT, cfg.passRetryLimit)
    }

    @Test
    fun `configHash is stable across key insertion order`() {
        val a = ResolvedConfig.resolve(ConfigLayer.of("asr.a" to 1, "asr.b" to 2))
        val b = ResolvedConfig.resolve(ConfigLayer.of("asr.b" to 2, "asr.a" to 1))
        assertEquals(a.configHash(PassId.B_OFFLINE), b.configHash(PassId.B_OFFLINE))
        assertEquals(a.configHash, b.configHash)
    }

    @Test
    fun `a change outside a pass's subset does not move that pass's configHash`() {
        val base = ResolvedConfig.builtInDefaults()
        val a = ResolvedConfig.resolve(base)
        val b = ResolvedConfig.resolve(base, ConfigLayer.of("segment.minSpeechMs" to 999))

        // segmentation is deliberately in no pass subset (it is tier-invariant, upstream of passes)
        assertEquals(a.configHash(PassId.B_OFFLINE), b.configHash(PassId.B_OFFLINE))
        assertEquals(a.configHash(PassId.D_RESOLVE), b.configHash(PassId.D_RESOLVE))
        // but the whole-snapshot hash does move
        assertNotEquals(a.configHash, b.configHash)
    }

    @Test
    fun `an asr change moves the asr pass hash but not the resolver hash`() {
        val base = ResolvedConfig.builtInDefaults()
        val a = ResolvedConfig.resolve(base)
        val b = ResolvedConfig.resolve(base, ConfigLayer.of("asr.noSpeechProbMax" to 0.5))
        assertNotEquals(a.configHash(PassId.B_OFFLINE), b.configHash(PassId.B_OFFLINE))
        assertEquals(a.configHash(PassId.D_RESOLVE), b.configHash(PassId.D_RESOLVE))
    }

    @Test
    fun `every pass has a config-relevance entry`() {
        PassId.entries.forEach { ConfigRelevance.prefixesFor(it) }
    }
}
