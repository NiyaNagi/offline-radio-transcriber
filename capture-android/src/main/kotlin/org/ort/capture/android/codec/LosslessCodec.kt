package org.ort.capture.android.codec

/** A reversible codec over raw PCM16LE bytes. [FlacStore] never trusts this by construction. */
public interface LosslessCodec {
    public val name: String
    public fun encode(pcm: ByteArray): ByteArray
    public fun decode(encoded: ByteArray): ByteArray
}
