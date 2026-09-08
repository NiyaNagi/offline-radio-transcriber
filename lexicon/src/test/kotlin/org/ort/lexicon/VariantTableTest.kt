package org.ort.lexicon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VariantTableTest {

    private val table = VariantTable.bundled()

    @Test
    fun `FR_LEX_4 the variant table maps every spoken form it knows to a unit`() {
        assertTrue(table.forms.size > 100, "expected a substantial variant table")
        table.forms.forEach { form ->
            assertTrue(table.resolve(form) != null, "no unit for known form '$form'")
        }
    }

    @Test
    fun `FR_LEX_4 every letter and digit has at least one spoken form`() {
        (PhoneticUnit.LETTERS + PhoneticUnit.DIGITS).forEach { unit ->
            assertTrue(table.formsFor(unit).isNotEmpty(), "no spoken form for $unit")
        }
    }

    @Test
    fun `FR_LEX_4 an unknown spoken form is rejected, never guessed`() {
        assertNull(table.resolve("banana"))
        assertNull(table.resolve("wibble"))
        val ex = assertThrows(UnknownSpokenFormException::class.java) { table.require("banana") }
        assertEquals("banana", ex.form)
    }

    @Test
    fun `NATO, letter-name and legacy forms normalise to the same unit`() {
        listOf("kilo", "kay", "king", "kilowatt").forEach { assertEquals(PhoneticUnit.K, table.require(it)) }
        listOf("x-ray", "X RAY", "xray").forEach { assertEquals(PhoneticUnit.X, table.require(it)) }
        listOf("niner", "nine").forEach { assertEquals(PhoneticUnit.N9, table.require(it)) }
    }

    @Test
    fun `the table is a versioned asset`() {
        assertEquals("lexicon-phonetic-variants", table.version.assetId)
        assertTrue(table.version.version.isNotBlank())
    }

    @Test
    fun `FR_A11Y_6 a regional or legacy variant set is addable as plain data, with no code or translation change`() {
        // A hypothetical regional variant absent from the bundled table (e.g. a legacy British
        // phonetic word for "V"). Adding it is only ever a new TSV row (technical design §9.1):
        // no code change, and definitely no localization/translation pass, since this is content.
        val extended = VariantTable.parse(
            """
            #<spoken form>	<PhoneticUnit>
            victor	V
            vimto	V
            """.trimIndent(),
            org.ort.core.AssetRef("test-regional-variants", "1"),
        )
        assertEquals(PhoneticUnit.V, extended.require("vimto"))
        assertEquals(PhoneticUnit.V, extended.require("victor"))
    }

    @Test
    fun `FR_A11Y_6 the variant set is independent of the JVM default locale`() {
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR")) // the classic I/i trap
            val reloaded = VariantTable.bundled()
            assertEquals(PhoneticUnit.K, reloaded.require("kilo"))
            assertEquals(PhoneticUnit.X, reloaded.require("X RAY"))
        } finally {
            java.util.Locale.setDefault(original)
        }
    }
}
