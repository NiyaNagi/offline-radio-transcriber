package org.ort.lexicon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** R-320/FR-UI-8, R-182/FR-UI-4: [SlotDetail]'s own invariants, independent of the grammar. */
class SlotDetailTest {

    @Test
    fun `a negative index is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            SlotDetail(index = -1, unit = "K", score = 0.9)
        }
    }

    @Test
    fun `charStart without charEnd is refused, and vice versa`() {
        assertThrows(IllegalArgumentException::class.java) {
            SlotDetail(index = 0, unit = "K", score = 0.9, charStart = 3, charEnd = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SlotDetail(index = 0, unit = "K", score = 0.9, charStart = null, charEnd = 5)
        }
    }

    @Test
    fun `charStart after charEnd is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            SlotDetail(index = 0, unit = "K", score = 0.9, charStart = 5, charEnd = 3)
        }
    }

    @Test
    fun `charStart equal to charEnd is allowed, an empty range, not an error`() {
        val detail = SlotDetail(index = 0, unit = "K", score = 0.9, charStart = 4, charEnd = 4)
        assertEquals(4, detail.charStart)
        assertEquals(4, detail.charEnd)
    }

    @Test
    fun `keptAlternate and char span both default to null`() {
        val detail = SlotDetail(index = 0, unit = "K", score = 0.9)
        assertNull(detail.keptAlternate)
        assertNull(detail.charStart)
        assertNull(detail.charEnd)
    }

    @Test
    fun `LatticeSlot runnerUp is the second-highest-scoring alternative`() {
        val slot = LatticeSlot(
            startMs = 0,
            endMs = 100,
            alts = listOf(UnitScore(PhoneticUnit.W, 0.64f), UnitScore(PhoneticUnit.V, 0.29f)),
        )
        assertEquals(PhoneticUnit.W, slot.top.unit)
        assertEquals(PhoneticUnit.V, slot.runnerUp?.unit)
    }

    @Test
    fun `LatticeSlot runnerUp is null when the slot carries only one alternative`() {
        val slot = LatticeSlot(startMs = 0, endMs = 100, alts = listOf(UnitScore(PhoneticUnit.N7, 0f)))
        assertNull(slot.runnerUp)
    }

    @Test
    fun `LatticeSlot char span must be both null or both set, same as SlotDetail`() {
        assertThrows(IllegalArgumentException::class.java) {
            LatticeSlot(0, 100, listOf(UnitScore(PhoneticUnit.K, 0f)), charStart = 0, charEnd = null)
        }
    }
}
