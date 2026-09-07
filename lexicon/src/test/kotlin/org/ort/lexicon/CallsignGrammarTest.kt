package org.ort.lexicon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CallsignGrammarTest {

    private val grammar = bundledGrammar()

    private fun parse(callsign: String) =
        grammar.parse(PhoneticLattice.ofUnits(PhoneticUnit.spell(callsign), LatticeSource.ACOUSTIC))

    @Test
    fun `AC_10 a structurally valid non-US prefix with zero database hits still resolves as a candidate`() {
        // VK3MMM — Australia, absent from any US ULS dump (there is no database layer here at all)
        val candidates = parse("VK3MMM")
        val vk = candidates.firstOrNull { it.text == "VK3MMM" }
        assertNotNull(vk, "expected VK3MMM among $candidates")
        assertEquals("Australia", vk!!.allocation.entity)
        assertEquals("AU", vk.allocation.iso)
    }

    @Test
    fun `AC_11 an unallocated prefix never produces a candidate and so can never reach CONFIRMED`() {
        // 0X1AA — the leading "0" series is not allocated to anyone
        val candidates = parse("0X1AA")
        assertTrue(candidates.none { it.text == "0X1AA" }, candidates.toString())
        // and the structural filter is genuinely ITU: the parse is well-formed, only the prefix is dead
        assertFalse(ItuPrefixTable.bundled().isLivePrefixPath("0"))
    }

    @Test
    fun `AC_12 NATO, letter-name and legacy pronunciations of one callsign yield one result`() {
        val nato = grammar.parse(SpokenLattice.of("kilo seven alpha bravo charlie"))
        val letterName = grammar.parse(SpokenLattice.of("kay seven ay bee cee"))
        val legacy = grammar.parse(SpokenLattice.of("king seven able baker charlie"))

        assertEquals("K7ABC", nato.first().text)
        assertEquals("K7ABC", letterName.first().text)
        assertEquals("K7ABC", legacy.first().text)
        assertEquals("United States", nato.first().allocation.entity)
    }

    @Test
    fun `FR_LEX_7 modifiers and reciprocal forms parse`() {
        fun textsFor(cs: String) = parse(cs).map { it.text }

        assertTrue("K7ABC/P" in textsFor("K7ABC/P"))
        assertTrue("K7ABC/M" in textsFor("K7ABC/M"))
        assertTrue("K7ABC/VE7" in textsFor("K7ABC/VE7"))
        val rc = parse("VE7/K7ABC").first { it.text == "VE7/K7ABC" }
        assertEquals("VE7", rc.parsed.secondaryPrefix)
        assertEquals("K7ABC", rc.parsed.core)
    }

    @Test
    fun `FR_LEX_8 structural validity against the ITU table is the only hard filter`() {
        // a DX call with no priors, no database, no frequency context — still a candidate
        val zl = parse("ZL4AA")
        assertTrue(zl.any { it.text == "ZL4AA" && it.allocation.iso == "NZ" }, zl.toString())
    }
}
