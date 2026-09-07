package org.ort.captureapi

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A decoded PCM16 WAV: mono samples plus the rate they were stored at. */
public data class WavAudio(val samples: ShortArray, val sampleRate: Int) {
    override fun equals(other: Any?): Boolean =
        other is WavAudio && sampleRate == other.sampleRate && samples.contentEquals(other.samples)

    override fun hashCode(): Int = 31 * samples.contentHashCode() + sampleRate
}

/**
 * Minimal RIFF/WAVE reader and writer for the one format the pipeline uses — uncompressed
 * PCM16, little-endian, mono or stereo (stereo is downmixed by averaging on read). Enough for
 * fixtures and the file-backed [CaptureSource]; not a general WAV library.
 */
public object WavIo {

    private const val HEADER_BYTES = 44
    private const val PCM_FORMAT = 1
    private const val BITS_PER_SAMPLE = 16

    public fun read(file: File): WavAudio = read(file.readBytes())

    public fun read(bytes: ByteArray): WavAudio {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= HEADER_BYTES) { "not a WAV: too short" }
        require(tag(bb, 0) == "RIFF" && tag(bb, 8) == "WAVE") { "not a RIFF/WAVE file" }

        var pos = 12
        var channels = 1
        var sampleRate = AudioFormat.OUTPUT_SAMPLE_RATE
        var dataOffset = -1
        var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = tag(bb, pos)
            val size = bb.getInt(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    val audioFormat = bb.getShort(body).toInt()
                    require(audioFormat == PCM_FORMAT) { "only uncompressed PCM WAV is supported" }
                    channels = bb.getShort(body + 2).toInt()
                    sampleRate = bb.getInt(body + 4)
                    val bits = bb.getShort(body + 14).toInt()
                    require(bits == BITS_PER_SAMPLE) { "only 16-bit PCM is supported, was $bits" }
                }
                "data" -> {
                    dataOffset = body
                    dataLen = minOf(size, bytes.size - body)
                }
            }
            pos = body + size + (size and 1)
        }
        require(dataOffset >= 0) { "no data chunk" }
        require(channels in 1..2) { "unsupported channel count: $channels" }

        val frameCount = dataLen / (2 * channels)
        val out = ShortArray(frameCount)
        var p = dataOffset
        for (i in 0 until frameCount) {
            if (channels == 1) {
                out[i] = bb.getShort(p)
                p += 2
            } else {
                val l = bb.getShort(p).toInt()
                val r = bb.getShort(p + 2).toInt()
                out[i] = ((l + r) / 2).toShort()
                p += 4
            }
        }
        return WavAudio(out, sampleRate)
    }

    public fun write(file: File, audio: WavAudio) {
        file.writeBytes(encode(audio))
    }

    public fun encode(audio: WavAudio): ByteArray {
        val dataBytes = audio.samples.size * 2
        val bb = ByteBuffer.allocate(HEADER_BYTES + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.US_ASCII))
        bb.putInt(HEADER_BYTES - 8 + dataBytes)
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII))
        bb.putInt(16)
        bb.putShort(PCM_FORMAT.toShort())
        bb.putShort(1)
        bb.putInt(audio.sampleRate)
        bb.putInt(audio.sampleRate * 2)
        bb.putShort(2)
        bb.putShort(BITS_PER_SAMPLE.toShort())
        bb.put("data".toByteArray(Charsets.US_ASCII))
        bb.putInt(dataBytes)
        for (s in audio.samples) bb.putShort(s)
        return bb.array()
    }

    private fun tag(bb: ByteBuffer, at: Int): String {
        val b = ByteArray(4)
        for (i in 0 until 4) b[i] = bb.get(at + i)
        return String(b, Charsets.US_ASCII)
    }
}
