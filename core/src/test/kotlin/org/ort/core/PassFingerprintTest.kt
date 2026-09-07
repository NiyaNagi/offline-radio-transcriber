package org.ort.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PassFingerprintTest {

    private fun fp(
        pass: PassId = PassId.B_OFFLINE,
        code: Int = 1,
        models: List<AssetRef> = listOf(AssetRef("distil-small", "1")),
        lexicon: AssetRef? = AssetRef("lexicon", "2026.09"),
        calibration: AssetRef? = AssetRef("calib", "1"),
        cfg: String = "abc",
        provider: String = "cpu",
        tier: Tier = Tier.T1,
    ) = PassFingerprint(pass, code, models, lexicon, calibration, cfg, provider, tier)

    @Test
    fun `equal fingerprints have equal canonical strings and vice versa`() {
        assertEquals(fp().canonical(), fp().canonical())
        assertEquals(fp(), fp())
        assertTrue(fp(models = listOf(AssetRef("m", "1"), AssetRef("n", "1"))).canonical().contains("m@1,n@1"))
    }

    @Test
    fun `FR_REP_6 a lexicon bump makes Pass D stale but not Pass B`() {
        val bStored = fp(pass = PassId.B_OFFLINE, lexicon = AssetRef("lexicon", "2026.09"))
        val bCurrent = fp(pass = PassId.B_OFFLINE, lexicon = AssetRef("lexicon", "2026.10"))
        assertFalse(bStored.materiallyDiffersFrom(bCurrent), "Pass B does not read the lexicon")

        val dStored = fp(pass = PassId.D_RESOLVE, lexicon = AssetRef("lexicon", "2026.09"))
        val dCurrent = fp(pass = PassId.D_RESOLVE, lexicon = AssetRef("lexicon", "2026.10"))
        assertTrue(dStored.materiallyDiffersFrom(dCurrent))
        assertEquals(setOf(FingerprintField.LEXICON_VERSION), dStored.changedMaterialFields(dCurrent))
    }

    @Test
    fun `FR_ACC_5 a provider change makes an ASR pass a reprocess candidate`() {
        val stored = fp(provider = "cpu")
        val current = fp(provider = "qnn-htp")
        assertTrue(PassFingerprint.isReprocessCandidate(stored, current))
    }

    @Test
    fun `a code-version bump is always material`() {
        assertTrue(fp(code = 1).materiallyDiffersFrom(fp(code = 2)))
    }

    @Test
    fun `model order does not matter`() {
        val a = fp(models = listOf(AssetRef("a", "1"), AssetRef("b", "1")))
        val b = fp(models = listOf(AssetRef("b", "1"), AssetRef("a", "1")))
        assertFalse(a.materiallyDiffersFrom(b))
        assertEquals(a.canonical(), b.canonical())
    }

    @Test
    fun `comparing fingerprints for different passes is a programming error`() {
        assertThrows(IllegalArgumentException::class.java) {
            fp(pass = PassId.B_OFFLINE).changedMaterialFields(fp(pass = PassId.D_RESOLVE))
        }
    }

    @Test
    fun `every pass has a materiality entry`() {
        PassId.entries.forEach { Materiality.fieldsFor(it) }
    }
}
