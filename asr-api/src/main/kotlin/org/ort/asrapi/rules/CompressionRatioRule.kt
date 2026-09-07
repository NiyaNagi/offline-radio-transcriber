package org.ort.asrapi.rules

import org.ort.asrapi.AsrResult
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * Rule 6 (§8.2): Whisper-style compression-ratio anomaly check. A degenerate hallucination
 * ("the the the the...") compresses far better than real speech text; a gzip ratio above
 * [ceiling] rejects. Default 2.4, Whisper's convention — see [NoSpeechProbRule]'s doc for why
 * this remains unrefitted pending a real development noise tape.
 */
public class CompressionRatioRule(private val ceiling: Double = DEFAULT_CEILING) : PostDecodeRejectionRule {
    override val id: RejectionRuleId = RejectionRuleId.COMPRESSION_RATIO

    override fun evaluate(result: AsrResult): RejectionVerdict {
        if (result.text.isBlank()) return RejectionVerdict.Accept
        val ratio = compressionRatio(result.text)
        return if (ratio > ceiling) {
            RejectionVerdict.Reject(id, "compression ratio=%.2f exceeds ceiling=%.2f".format(ratio, ceiling))
        } else {
            RejectionVerdict.Accept
        }
    }

    private fun compressionRatio(text: String): Double {
        val raw = text.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out, Deflater(Deflater.BEST_COMPRESSION)).use { it.write(raw) }
        val compressedSize = out.size().coerceAtLeast(1)
        return raw.size.toDouble() / compressedSize.toDouble()
    }

    public companion object {
        public const val DEFAULT_CEILING: Double = 2.4
    }
}
