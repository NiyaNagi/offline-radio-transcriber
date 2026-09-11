package org.ort.rig.descriptor

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A USB vendor/product id (FR-RIG-3) is a hardware fact everyone writes and reads in hex
 * (`"0x0451"`), never decimal — every USB device table in existence uses that convention, and a
 * descriptor JSON file someone edits by hand should match it rather than forcing a manual
 * hex-to-decimal conversion. [Int] itself is base-agnostic; only the JSON text representation
 * differs from the default numeric encoding, so this is a text-format concern, not a domain type.
 *
 * Accepts an optional `0x`/`0X` prefix on decode; always emits a lowercase, zero-padded-to-4-digit
 * `0x` form on encode (`[TransportSpec]`'s own values only round-trip through this for tests —
 * production only ever reads bundled descriptor JSON, never writes it).
 */
internal object HexIntSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("HexInt", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Int) {
        encoder.encodeString("0x%04x".format(value))
    }

    override fun deserialize(decoder: Decoder): Int {
        val raw = decoder.decodeString().trim()
        val hex = raw.removePrefix("0x").removePrefix("0X")
        return hex.toInt(16)
    }
}
