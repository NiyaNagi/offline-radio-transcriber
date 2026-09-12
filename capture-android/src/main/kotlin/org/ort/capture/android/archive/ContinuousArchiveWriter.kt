package org.ort.capture.android.archive

import org.ort.capture.android.codec.DeflatePredictiveCodec
import org.ort.capture.android.codec.FlacEncodeResult
import org.ort.capture.android.codec.FlacStore
import org.ort.capture.android.codec.LosslessCodec
import java.io.File

/** One bounded, FLAC-encoded chunk of the continuous archive, indexed by the sample position of
 * its first sample (WPARC: [file] holds the *encoded* bytes — decode with the same [LosslessCodec]
 * this writer used, via [ArchiveReader]). */
public data class ArchiveChunkMeta(val startSample: Long, val sampleCount: Int, val file: File)

/**
 * WPARC (FR-STO-2a): a chunk whose FLAC verification failed — the interval was captured (over
 * audio is unaffected either way) but the continuous archive has a hole here, recorded rather
 * than silently dropped (constitution III, IV) or allowed to block capture.
 */
public data class ArchiveHole(val startSample: Long, val sampleCount: Int, val reason: String)

/**
 * technical design §6.1's continuous-archive mode (FR-SEG-9 → AC-96; default **on** at D39).
 * Writes bounded, FLAC-encoded chunks (reusing [FlacStore] — the same lossless store and
 * decode-and-compare verification `RealSegmentSink` uses for over audio, FR-STO-2a) indexed by
 * sample position, so the raw stream can be replayed sample-for-sample later. Re-segmentation
 * with different VAD parameters is `:pipeline`'s job — `:capture-android` may not depend on
 * `:segment` (module graph, technical design §2) — so this writer's contract stops at faithfully
 * storing and replaying, via [ArchiveReader].
 *
 * **A verification failure never throws and never drops the interval silently.** [FlacStore]
 * itself already keeps the staged PCM when its own decode-and-compare check fails
 * (`FlacEncodeResult.VerificationFailed`) — this writer additionally reports the failed interval
 * through [onHole] (constitution IV: "record that the archive has a hole for that interval, the
 * way a gap is a record") instead of adding it to [chunkIndex], and moves on to the next chunk.
 * The caller ([org.ort.pipeline.capture.ContinuousArchiveAttachment]) runs this off the audio
 * frame thread entirely, so neither path can ever block capture.
 */
public class ContinuousArchiveWriter(
    private val directory: File,
    private val chunkSamples: Int = DEFAULT_CHUNK_SAMPLES,
    private val codec: LosslessCodec = DeflatePredictiveCodec(),
    private val onHole: (ArchiveHole) -> Unit = {},
) {
    private val flacStore = FlacStore(codec)
    private val chunks = mutableListOf<ArchiveChunkMeta>()
    private var buffer = ShortArray(0)
    private var bufferStart = 0L

    public fun append(pcm: ShortArray, framePosition: Long) {
        if (buffer.isEmpty()) bufferStart = framePosition
        buffer += pcm
        while (buffer.size >= chunkSamples) flushChunk(chunkSamples)
    }

    public fun finish() {
        if (buffer.isNotEmpty()) flushChunk(buffer.size)
    }

    public fun chunkIndex(): List<ArchiveChunkMeta> = chunks.toList()

    private fun flushChunk(count: Int) {
        val slice = buffer.copyOfRange(0, count)
        val chunkStart = bufferStart
        directory.mkdirs()
        val staged = File(directory, "chunk-$chunkStart.stage.pcm")
        val encoded = File(directory, "chunk-$chunkStart.flac")
        flacStore.stage(shortsToBytes(slice), staged)

        when (val result = flacStore.encodeAndVerify(staged, encoded)) {
            is FlacEncodeResult.Success -> chunks.add(ArchiveChunkMeta(chunkStart, slice.size, encoded))
            is FlacEncodeResult.VerificationFailed -> onHole(ArchiveHole(chunkStart, slice.size, result.reason))
        }

        buffer = buffer.copyOfRange(count, buffer.size)
        bufferStart += count
    }

    private fun shortsToBytes(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            bytes[i * 2] = (shorts[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = ((shorts[i].toInt() shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    public companion object {
        /** 30 seconds at 16 kHz — bounded so no single chunk is unreasonably large. */
        public const val DEFAULT_CHUNK_SAMPLES: Int = 16_000 * 30
    }
}

/** Replays an archived stream in sample-position order (`:pipeline` re-segments over this),
 * decoding each FLAC-encoded chunk with the same [codec] it was written with. */
public class ArchiveReader(
    private val index: List<ArchiveChunkMeta>,
    private val codec: LosslessCodec = DeflatePredictiveCodec(),
) {

    public fun readAll(): Sequence<Pair<Long, ShortArray>> = sequence {
        for (meta in index.sortedBy { it.startSample }) {
            val pcmBytes = codec.decode(meta.file.readBytes())
            val samples = ShortArray(pcmBytes.size / 2)
            for (i in samples.indices) {
                val lo = pcmBytes[i * 2].toInt() and 0xFF
                val hi = pcmBytes[i * 2 + 1].toInt()
                samples[i] = ((hi shl 8) or lo).toShort()
            }
            yield(meta.startSample to samples)
        }
    }
}
