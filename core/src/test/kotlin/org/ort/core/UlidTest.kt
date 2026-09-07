package org.ort.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class UlidTest {

    @Test
    fun `FR_STO_6 ids sort lexicographically in creation order`() {
        val clock = FixedClock(wall = 1_000)
        val early = Ulid.generate(clock)
        clock.wall = 2_000
        val mid = Ulid.generate(clock)
        clock.wall = 3_000
        val late = Ulid.generate(clock)

        assertTrue(early < mid)
        assertTrue(mid < late)
        assertEquals(listOf(early, mid, late), listOf(late, early, mid).sorted())
    }

    @Test
    fun `ids minted in the same millisecond stay monotonic`() {
        val ts = 1_725_000_000_000L
        val rnd = Random(1)
        val ids = (1..500).map { Ulid.generate(ts, rnd) }
        assertEquals(ids, ids.sorted())
        assertEquals(ids.size, ids.toSet().size, "same-millisecond ids must still be unique")
    }

    @Test
    fun `FR_STO_6 a large batch across time is collision-free`() {
        val clock = FixedClock(wall = 1_000)
        val seen = HashSet<String>()
        repeat(20_000) {
            if (it % 7 == 0) clock.wall += 1
            assertTrue(seen.add(Ulid.generate(clock).value), "duplicate ULID at $it")
        }
    }

    @Test
    fun `timestamp round-trips through the encoding`() {
        val ts = 1_699_999_999_999L
        assertEquals(ts, Ulid.generate(ts, Random(0)).timestampMillis)
    }

    @Test
    fun `parse rejects a malformed id`() {
        assertThrows(IllegalArgumentException::class.java) { Ulid.parse("too-short") }
        assertThrows(IllegalArgumentException::class.java) { Ulid.parse("I".repeat(26)) } // I is not Crockford
    }

    @Test
    fun `typed id wrappers are distinct types over the same ulid`() {
        val u = Ulid.generate(1L, Random(0))
        val session: Any = SessionId(u)
        val transmission: Any = TransmissionId(u)
        assertNotEquals(session, transmission)
        assertEquals(u.value, TransmissionId(u).toString())
    }
}
