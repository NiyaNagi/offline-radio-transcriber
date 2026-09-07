package org.ort.lexicon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PhoneticUnitTest {

    @Test
    fun `the vocabulary is 26 letters, 10 digits and one separator`() {
        assertEquals(26, PhoneticUnit.LETTERS.size)
        assertEquals(10, PhoneticUnit.DIGITS.size)
        assertEquals(37, PhoneticUnit.entries.size)
    }

    @Test
    fun `fromSymbol round-trips callsign characters and rejects the rest`() {
        assertEquals(PhoneticUnit.K, PhoneticUnit.fromSymbol('k'))
        assertEquals(PhoneticUnit.N7, PhoneticUnit.fromSymbol('7'))
        assertEquals(PhoneticUnit.STROKE, PhoneticUnit.fromSymbol('/'))
        assertNull(PhoneticUnit.fromSymbol('-'))
    }

    @Test
    fun `spell turns a callsign into units, or fails on an unrepresentable character`() {
        assertEquals(
            listOf(PhoneticUnit.K, PhoneticUnit.N7, PhoneticUnit.A, PhoneticUnit.B, PhoneticUnit.C),
            PhoneticUnit.spell("K7ABC"),
        )
        assertThrows(IllegalArgumentException::class.java) { PhoneticUnit.spell("K7-ABC") }
    }
}
