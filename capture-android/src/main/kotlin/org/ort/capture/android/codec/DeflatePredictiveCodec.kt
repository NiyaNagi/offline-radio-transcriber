package org.ort.capture.android.codec

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * **Deviation from spec, documented per build-plan P8's instruction.** A real JNI binding to
 * libFLAC cannot be built or tested in this sandbox (no NDK toolchain, no way to run native code
 * under Robolectric). This is a pure-Kotlin, genuinely lossless substitute: an order-1 linear
 * predictor removes inter-sample redundancy the way FLAC's own predictors do, then a
 * general-purpose entropy coder (`java.util.zip`'s raw DEFLATE, which is itself lossless)
 * compresses the residual. It is FLAC-*shaped* in spirit, not FLAC — the files are not
 * interoperable with libFLAC and no compression-ratio claim is made.
 *
 * The substitution is safe only because [FlacStore] never trusts it: every encode is
 * immediately decoded and compared byte-for-byte against the staged source before that staged
 * PCM is allowed to be deleted (constitution III — retained audio must remain sufficient to
 * re-run every pass).
 */
public class DeflatePredictiveCodec(private val level: Int = Deflater.BEST_COMPRESSION) : LosslessCodec {

    override val name: String = "ort-predictive-deflate/v1"

    override fun encode(pcm: ByteArray): ByteArray {
        val residual = predict(pcm)
        val deflater = Deflater(level, true)
        deflater.setInput(residual)
        deflater.finish()
        val out = ByteArrayOutputStream(residual.size / 2 + HEADER_BYTES)
        val buf = ByteArray(BUFFER_SIZE)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            out.write(buf, 0, n)
        }
        deflater.end()
        return intToBytes(pcm.size) + out.toByteArray()
    }

    override fun decode(encoded: ByteArray): ByteArray {
        val originalSize = bytesToInt(encoded, 0)
        val inflater = Inflater(true)
        inflater.setInput(encoded, HEADER_BYTES, encoded.size - HEADER_BYTES)
        val residual = ByteArray(originalSize)
        var offset = 0
        val buf = ByteArray(BUFFER_SIZE)
        while (offset < originalSize && !inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0 && inflater.needsInput()) break
            System.arraycopy(buf, 0, residual, offset, n)
            offset += n
        }
        inflater.end()
        return unpredict(residual)
    }

    // Order-1 delta over 16-bit little-endian samples. A trailing odd byte (malformed input)
    // passes through unchanged in both directions, so the transform stays exactly reversible.
    private fun predict(pcm: ByteArray): ByteArray {
        val out = pcm.copyOf()
        var prev = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val sample = sampleAt(pcm, i)
            val delta = (sample - prev).toShort()
            writeSample(out, i, delta.toInt())
            prev = sample
            i += 2
        }
        return out
    }

    private fun unpredict(residual: ByteArray): ByteArray {
        val out = residual.copyOf()
        var prev = 0
        var i = 0
        while (i + 1 < residual.size) {
            val delta = sampleAt(residual, i)
            val sample = (prev + delta).toShort()
            writeSample(out, i, sample.toInt())
            prev = sample.toInt()
            i += 2
        }
        return out
    }

    private fun sampleAt(b: ByteArray, i: Int): Int =
        (((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xFF))).toShort().toInt()

    private fun writeSample(b: ByteArray, i: Int, value: Int) {
        b[i] = (value and 0xFF).toByte()
        b[i + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun intToBytes(v: Int) =
        byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun bytesToInt(b: ByteArray, off: Int) =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    private companion object {
        const val HEADER_BYTES = 4
        const val BUFFER_SIZE = 8192
    }
}
