package org.ort.captureapi

import java.security.MessageDigest

/**
 * Records exactly which resampler produced a signal, so a stored accuracy number can be tied
 * to the filter it was measured through (FR-CAP-2a, FR-TST-4 → AC-97). Value-equal iff the
 * two filters compute bit-identical output.
 */
public data class ResamplerIdentity(
    val algorithm: String,
    val version: Int,
    val inputRate: Int,
    val outputRate: Int,
    val interpolation: Int,
    val decimation: Int,
    val halfZeroCrossings: Int,
    /** Hex SHA-256 over the quantized coefficient table — the thing that actually moves output. */
    val coefficientHash: String,
) {
    override fun toString(): String = "$algorithm/v$version $inputRate->$outputRate (L=$interpolation M=$decimation " +
        "taps=${2 * halfZeroCrossings} ${coefficientHash.take(HASH_PREFIX)})"

    private companion object {
        const val HASH_PREFIX = 12
    }
}

/**
 * A deterministic fixed-point windowed-sinc polyphase resampler (technical design §5.1).
 *
 * The conversion is rational: `inputRate / outputRate` is reduced to `M / L`, output sample
 * `n` is drawn from input position `n·M/L`, and each of the `L` fractional phases has its own
 * pre-quantized FIR sub-filter. Coefficients are computed once with [StrictMath] (identical on
 * every JVM), quantized to Q30 and normalised so each phase has unit DC gain — which removes
 * the interpolation-gain question and makes amplitude preservation exact.
 *
 * [resample] is a pure, stateless function of its input: edges are zero-padded, so replaying a
 * signal in one call and in chunks (with the caller carrying history) yields the same samples,
 * and two runs on any machine are byte-identical (AC-89, AC-97).
 */
public class PolyphaseResampler(
    public val inputRate: Int,
    public val outputRate: Int,
    public val halfZeroCrossings: Int = DEFAULT_HALF_ZERO_CROSSINGS,
) {
    init {
        require(inputRate > 0 && outputRate > 0) { "rates must be positive" }
        require(halfZeroCrossings in 1..64) { "halfZeroCrossings out of range: $halfZeroCrossings" }
    }

    private val gcd: Int = gcd(inputRate, outputRate)

    /** Interpolation factor L. */
    public val interpolation: Int = outputRate / gcd

    /** Decimation factor M. */
    public val decimation: Int = inputRate / gcd

    private val tapsPerPhase: Int = 2 * halfZeroCrossings

    /** `phaseTaps[p][k]` — Q30 coefficient for fractional phase `p`, tap `k`. */
    private val phaseTaps: Array<IntArray> = buildPhaseTaps()

    public val identity: ResamplerIdentity by lazy {
        ResamplerIdentity(
            algorithm = ALGORITHM,
            version = VERSION,
            inputRate = inputRate,
            outputRate = outputRate,
            interpolation = interpolation,
            decimation = decimation,
            halfZeroCrossings = halfZeroCrossings,
            coefficientHash = hashCoefficients(),
        )
    }

    /** `true` when input and output rate are equal and [resample] is the identity function. */
    public val isPassthrough: Boolean get() = interpolation == 1 && decimation == 1

    /** Number of output samples [resample] will produce for [inputLength] input samples. */
    public fun outputLength(inputLength: Int): Int {
        if (inputLength <= 0) return 0
        if (isPassthrough) return inputLength
        // largest n with floor(n*M/L) - halfZeroCrossings + 1 <= inputLength-1, i.e. n*M/L reaches the last sample
        return ((inputLength.toLong() - 1) * interpolation / decimation).toInt() + 1
    }

    /** Resample [input] from [inputRate] to [outputRate]. Stateless; edges zero-padded. */
    public fun resample(input: ShortArray): ShortArray {
        if (isPassthrough) return input.copyOf()
        val outLen = outputLength(input.size)
        val out = ShortArray(outLen)
        val half = halfZeroCrossings
        for (n in 0 until outLen) {
            val pos = n.toLong() * decimation
            val inBase = (pos / interpolation).toInt()
            val phase = (pos % interpolation).toInt()
            val taps = phaseTaps[phase]
            var acc = 0L
            for (k in 0 until tapsPerPhase) {
                val idx = inBase + k - half + 1
                if (idx in input.indices) acc += taps[k].toLong() * input[idx]
            }
            var s = acc shr Q_BITS
            if (s > Short.MAX_VALUE) s = Short.MAX_VALUE.toLong()
            if (s < Short.MIN_VALUE) s = Short.MIN_VALUE.toLong()
            out[n] = s.toShort()
        }
        return out
    }

    private fun buildPhaseTaps(): Array<IntArray> {
        val half = halfZeroCrossings
        // Cutoff in cycles per input sample: below input Nyquist, and below the output Nyquist
        // referred back to the input rate when decimating.
        val cutoff = 0.5 * StrictMath.min(1.0, interpolation.toDouble() / decimation)
        return Array(interpolation) { phase ->
            val raw = DoubleArray(tapsPerPhase)
            var sum = 0.0
            for (k in 0 until tapsPerPhase) {
                val x = (k - half + 1) - phase.toDouble() / interpolation
                val w = blackman(x / half)
                val v = 2.0 * cutoff * sinc(2.0 * cutoff * x) * w
                raw[k] = v
                sum += v
            }
            val q = IntArray(tapsPerPhase)
            val scale = (1L shl Q_BITS).toDouble() / sum
            for (k in 0 until tapsPerPhase) {
                q[k] = StrictMath.rint(raw[k] * scale).toInt()
            }
            q
        }
    }

    private fun hashCoefficients(): String {
        val md = MessageDigest.getInstance("SHA-256")
        val header = "$ALGORITHM|$VERSION|$inputRate|$outputRate|$interpolation|$decimation|$halfZeroCrossings"
        md.update(header.toByteArray(Charsets.US_ASCII))
        for (phase in phaseTaps) {
            for (c in phase) {
                md.update(byteArrayOf((c ushr 24).toByte(), (c ushr 16).toByte(), (c ushr 8).toByte(), c.toByte()))
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    public companion object {
        public const val ALGORITHM: String = "windowed-sinc-polyphase-q30"
        public const val VERSION: Int = 1
        public const val DEFAULT_HALF_ZERO_CROSSINGS: Int = 16
        private const val Q_BITS: Int = 30

        private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

        private fun sinc(x: Double): Double {
            if (x == 0.0) return 1.0
            val px = StrictMath.PI * x
            return StrictMath.sin(px) / px
        }

        private fun blackman(u: Double): Double {
            if (u <= -1.0 || u >= 1.0) return 0.0
            val pu = StrictMath.PI * u
            return BLACKMAN_A0 + BLACKMAN_A1 * StrictMath.cos(pu) + BLACKMAN_A2 * StrictMath.cos(2.0 * pu)
        }

        private const val BLACKMAN_A0 = 0.42
        private const val BLACKMAN_A1 = 0.5
        private const val BLACKMAN_A2 = 0.08
    }
}
