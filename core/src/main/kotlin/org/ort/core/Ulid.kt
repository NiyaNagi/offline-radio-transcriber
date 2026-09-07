package org.ort.core

import java.security.SecureRandom
import kotlin.random.Random

/**
 * ULID — a 128-bit id as 26 Crockford base32 characters: 48 bits of millisecond timestamp
 * then 80 bits of randomness (technical design §12.1; FR-STO-6 -> AC-80).
 *
 * Why ULID and not a random UUID: ids are **lexicographically sortable by creation time**,
 * generated fully offline, and collision-free across devices, so an export re-imported onto
 * another device cannot alias two different records onto one id.
 *
 * Monotonic within a millisecond: if two ids are minted in the same millisecond the random
 * component of the second is the first's incremented by one, so ordering is stable even at
 * high generation rates (used for [PassResultId] sequences).
 */
public class Ulid private constructor(public val value: String) : Comparable<Ulid> {

    /** Milliseconds since the Unix epoch encoded in the first 10 characters. */
    public val timestampMillis: Long
        get() {
            var ts = 0L
            for (i in 0 until TIMESTAMP_CHARS) {
                ts = ts * ENCODING_BASE + DECODE[value[i].code]
            }
            return ts
        }

    override fun compareTo(other: Ulid): Int = value.compareTo(other.value)

    override fun equals(other: Any?): Boolean = other is Ulid && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    public companion object {
        private const val ENCODING = "0123456789ABCDEFGHJKMNPQRSTVWXYZ" // Crockford base32
        private const val ENCODING_BASE = 32
        private const val TIMESTAMP_CHARS = 10
        private const val RANDOM_CHARS = 16
        private const val ULID_LENGTH = TIMESTAMP_CHARS + RANDOM_CHARS
        private const val MAX_TIMESTAMP = (1L shl 48) - 1

        private val DECODE = IntArray(128) { -1 }.also { table ->
            ENCODING.forEachIndexed { index, c ->
                table[c.code] = index
                table[c.lowercaseChar().code] = index
            }
        }

        private val defaultRandom: Random = SecureRandom().let { sr -> Random(sr.nextLong()) }

        private var lastTimestamp: Long = -1
        private var lastRandom: LongArray = LongArray(RANDOM_CHARS)
        private val monotonicLock = Any()

        /** A fresh, time-ordered, monotonic ULID from the wall clock. */
        public fun generate(clock: Clock = SystemClock, random: Random = defaultRandom): Ulid =
            generate(clock.wallMillis(), random)

        /** Deterministic generation for tests: a fixed timestamp and a seeded [random]. */
        public fun generate(timestampMillis: Long, random: Random): Ulid {
            require(timestampMillis in 0..MAX_TIMESTAMP) {
                "timestamp $timestampMillis outside the 48-bit ULID range"
            }
            val chars = CharArray(ULID_LENGTH)
            encodeTimestamp(timestampMillis, chars)

            synchronized(monotonicLock) {
                if (timestampMillis == lastTimestamp) {
                    incrementRandom(lastRandom)
                } else {
                    lastTimestamp = timestampMillis
                    for (i in 0 until RANDOM_CHARS) lastRandom[i] = random.nextInt(ENCODING_BASE).toLong()
                }
                for (i in 0 until RANDOM_CHARS) {
                    chars[TIMESTAMP_CHARS + i] = ENCODING[lastRandom[i].toInt()]
                }
            }
            return Ulid(String(chars))
        }

        /** Parse and validate an existing ULID string. */
        public fun parse(text: String): Ulid {
            require(text.length == ULID_LENGTH) { "a ULID is $ULID_LENGTH chars, got ${text.length}: $text" }
            text.forEach { c ->
                require(c.code < 128 && DECODE[c.code] >= 0) { "'$c' is not a Crockford base32 character" }
            }
            return Ulid(text.uppercase())
        }

        private fun encodeTimestamp(timestamp: Long, into: CharArray) {
            var t = timestamp
            for (i in TIMESTAMP_CHARS - 1 downTo 0) {
                into[i] = ENCODING[(t % ENCODING_BASE).toInt()]
                t /= ENCODING_BASE
            }
        }

        private fun incrementRandom(digits: LongArray) {
            for (i in digits.indices.reversed()) {
                if (digits[i] < ENCODING_BASE - 1) {
                    digits[i]++
                    return
                }
                digits[i] = 0
            }
            // Overflow of 80 random bits within one millisecond is astronomically unlikely;
            // wrapping to zero keeps ordering monotonic across the rollover.
        }
    }
}
