package org.ort.lexicon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ItuPrefixTableTest {

    private val itu = ItuPrefixTable.bundled()

    @Test
    fun `FR_LEX_8 allocationFor matches the longest table prefix a callsign starts with`() {
        assertEquals("United States", itu.allocationFor("K7ABC")?.entity)
        assertEquals("United Kingdom", itu.allocationFor("2E0ABC")?.entity)
        assertEquals("Canada", itu.allocationFor("VE7CAB")?.entity)
        assertEquals("New Zealand", itu.allocationFor("ZL4AA")?.entity)
    }

    @Test
    fun `FR_LEX_8 an unallocated prefix resolves to nothing`() {
        assertNull(itu.allocationFor("0X1AA"))
        assertNull(itu.allocationFor("1Z9ZZ"))
    }

    @Test
    fun `the trie prunes dead prefix paths and keeps live ones`() {
        assertTrue(itu.isLivePrefixPath("V"))
        assertTrue(itu.isLivePrefixPath("VE"))
        assertTrue(itu.isLivePrefixPath("2E"))
        assertFalse(itu.isLivePrefixPath("0"))
        assertFalse(itu.isLivePrefixPath("QZ"))
    }

    @Test
    fun `FR_LEX_29 the table is a bundled, versioned asset rather than an optional download`() {
        assertEquals("lexicon-itu-prefixes", itu.version.assetId)
        assertTrue(itu.allocations.isNotEmpty())
    }
}
