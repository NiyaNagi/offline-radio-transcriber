package org.ort.captureapi

import java.util.concurrent.atomic.AtomicLong

/**
 * A single-producer / single-consumer lock-free ring of PCM16 samples (technical design §5.3).
 *
 * The producer is the `capture-io` thread, which must never allocate or block; [write] does
 * neither. The buffer's job is [snapshotPreRoll] — copying the audio from *before* the VAD
 * trigger point so a callsign spoken in the first syllable is not clipped (FR-CAP-4 → AC-3).
 *
 * Overrun — the writer lapping an un-draining reader — is **detectable, never silent**
 * ([hasOverrun], [droppedSamples]): a gap the system can report beats an overwrite it cannot.
 */
public class RingBuffer(requestedCapacitySamples: Int) {

    /** Actual capacity: the requested size rounded up to a power of two. */
    public val capacity: Int = nextPowerOfTwo(requestedCapacitySamples)

    private val buffer = ShortArray(capacity)
    private val mask = (capacity - 1).toLong()

    /** Total samples ever written. Only the producer advances it. */
    private val writeCursor = AtomicLong(0)

    /** Total samples ever consumed via [read]. Only the consumer advances it. */
    private val readCursor = AtomicLong(0)

    init {
        require(requestedCapacitySamples > 0) { "capacity must be positive" }
    }

    /** Samples written so far. */
    public val written: Long get() = writeCursor.get()

    /**
     * Append [len] samples from [src] starting at [offset]. Producer-only. No allocation, no
     * locks, no bounds surprise — samples older than [capacity] are overwritten, which is what
     * [hasOverrun] exists to surface.
     */
    public fun write(src: ShortArray, offset: Int = 0, len: Int = src.size - offset) {
        require(offset >= 0 && len >= 0 && offset + len <= src.size) { "write range out of bounds" }
        val w = writeCursor.get()
        for (i in 0 until len) {
            buffer[((w + i) and mask).toInt()] = src[offset + i]
        }
        writeCursor.lazySet(w + len)
    }

    /** Samples written but not yet [read]. */
    public fun available(): Long = writeCursor.get() - readCursor.get()

    /**
     * `true` once the writer has produced more than [capacity] samples ahead of the reader —
     * meaning data the reader has not consumed has already been overwritten (NFR-4).
     */
    public fun hasOverrun(): Boolean = available() > capacity

    /** How many un-read samples were lost to overwrite; 0 when [hasOverrun] is false. */
    public fun droppedSamples(): Long = (available() - capacity).coerceAtLeast(0)

    /**
     * Consume up to [dst].size samples into [dst]; returns the count actually copied. If the
     * reader has fallen behind by more than [capacity], the read fast-forwards to the oldest
     * still-resident sample first (the lost span is reported by [droppedSamples]).
     */
    public fun read(dst: ShortArray): Int {
        val w = writeCursor.get()
        var r = readCursor.get()
        if (w - r > capacity) r = w - capacity
        val n = minOf(dst.size.toLong(), w - r).toInt()
        for (i in 0 until n) {
            dst[i] = buffer[((r + i) and mask).toInt()]
        }
        readCursor.lazySet(r + n)
        return n
    }

    /**
     * Copy the most recent [millisBack] ms of audio (at [sampleRate]) that is still resident,
     * ending at the current write position. The returned array is at most `millisBack·rate/1000`
     * long and shorter when less has been written or the window exceeds [capacity].
     *
     * A window longer than [capacity] cannot be fully served — [preRollOverrun] reports that.
     */
    public fun snapshotPreRoll(millisBack: Int, sampleRate: Int): ShortArray {
        require(millisBack >= 0 && sampleRate > 0)
        val requested = millisBack.toLong() * sampleRate / MILLIS_PER_SECOND
        val w = writeCursor.get()
        val serveable = minOf(requested, capacity.toLong(), w)
        val start = w - serveable
        val out = ShortArray(serveable.toInt())
        for (i in out.indices) {
            out[i] = buffer[((start + i) and mask).toInt()]
        }
        return out
    }

    /** `true` if a [snapshotPreRoll] of [millisBack] ms would be truncated by the buffer size. */
    public fun preRollOverrun(millisBack: Int, sampleRate: Int): Boolean =
        millisBack.toLong() * sampleRate / MILLIS_PER_SECOND > capacity

    public companion object {
        private const val MILLIS_PER_SECOND = 1000L

        internal fun nextPowerOfTwo(n: Int): Int {
            var v = n - 1
            v = v or (v shr 1)
            v = v or (v shr 2)
            v = v or (v shr 4)
            v = v or (v shr 8)
            v = v or (v shr 16)
            return v + 1
        }
    }
}
