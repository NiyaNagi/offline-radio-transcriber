package org.ort.capture.android.archive

import java.io.File
import java.io.RandomAccessFile

/** One bounded chunk of the continuous archive, indexed by the sample position of its first sample. */
public data class ArchiveChunkMeta(val startSample: Long, val sampleCount: Int, val file: File)

/**
 * technical design §6.1's continuous-archive mode (default off, FR-SEG-9 → AC-96). Writes
 * bounded PCM16LE chunks indexed by sample position, so the raw stream can be replayed
 * sample-for-sample later. Re-segmentation with different VAD parameters is `:pipeline`'s job —
 * `:capture-android` may not depend on `:segment` (module graph, technical design §2) — so this
 * writer's contract stops at faithfully storing and replaying, via [ArchiveReader].
 */
public class ContinuousArchiveWriter(
    private val directory: File,
    private val chunkSamples: Int = DEFAULT_CHUNK_SAMPLES,
) {
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
        directory.mkdirs()
        val file = File(directory, "chunk-$bufferStart.pcm")
        RandomAccessFile(file, "rw").use { raf ->
            val bytes = ByteArray(slice.size * 2)
            for (i in slice.indices) {
                bytes[i * 2] = (slice[i].toInt() and 0xFF).toByte()
                bytes[i * 2 + 1] = ((slice[i].toInt() shr 8) and 0xFF).toByte()
            }
            raf.write(bytes)
        }
        chunks.add(ArchiveChunkMeta(bufferStart, slice.size, file))
        buffer = buffer.copyOfRange(count, buffer.size)
        bufferStart += count
    }

    public companion object {
        /** 30 seconds at 16 kHz — bounded so no single chunk is unreasonably large. */
        public const val DEFAULT_CHUNK_SAMPLES: Int = 16_000 * 30
    }
}

/** Replays an archived stream in sample-position order (`:pipeline` re-segments over this). */
public class ArchiveReader(private val index: List<ArchiveChunkMeta>) {

    public fun readAll(): Sequence<Pair<Long, ShortArray>> = sequence {
        for (meta in index.sortedBy { it.startSample }) {
            val bytes = meta.file.readBytes()
            val samples = ShortArray(bytes.size / 2)
            for (i in samples.indices) {
                val lo = bytes[i * 2].toInt() and 0xFF
                val hi = bytes[i * 2 + 1].toInt()
                samples[i] = ((hi shl 8) or lo).toShort()
            }
            yield(meta.startSample to samples)
        }
    }
}
