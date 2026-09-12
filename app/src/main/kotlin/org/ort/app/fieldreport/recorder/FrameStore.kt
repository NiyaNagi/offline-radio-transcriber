package org.ort.app.fieldreport.recorder

import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * FR-OBS-7: app-private storage for screen frames, "bounded in both count and total bytes — the
 * oldest frame is dropped when a bound is reached, never silently retained past it".
 *
 * Frames are named by a monotonically increasing sequence number zero-padded for lexicographic
 * order (`0000000001.png`, ...), so "oldest" is always "lexicographically first" — no separate
 * manifest file to keep in sync, and no reliance on filesystem mtime (coarse on some filesystems,
 * and not what determined insertion order if two frames land in the same tick).
 *
 * [store] is the only mutator; [frames] and [totalBytes] are read-only views a test or a future
 * bundle builder can call without risking a write. This class has no concept of a per-category
 * upload toggle (over-audio / voiceprints / screen frames, FR-OBS-9) — see `FrameStoreTest`'s
 * `AC_148_...` test for exactly what that separation proves and what it does not: **whether a
 * stored frame ever reaches an uploaded bundle is a decision this class deliberately has no way
 * to make**, so nothing here can accidentally bypass whatever consent gate the field-report
 * bundle builder (out of this package's ownership) puts in front of [frames].
 */
public class FrameStore(
    private val dir: File,
    private val maxFrames: Int = DEFAULT_MAX_FRAMES,
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
) {
    private val sequence = AtomicLong(0)

    /** Stores [bytes] as the newest frame, evicting the oldest frame(s) first if adding it would
     * exceed [maxFrames] or [maxTotalBytes] — including when [bytes] alone is larger than
     * [maxTotalBytes], which empties the store entirely rather than keeping an over-budget file. */
    public fun store(bytes: ByteArray) {
        dir.mkdirs()
        val name = "%010d.png".format(sequence.incrementAndGet())
        File(dir, name).writeBytes(bytes)
        enforceBounds()
    }

    /** Every stored frame, oldest first. */
    public fun frames(): List<File> = sortedFiles()

    /** The sum of every stored frame's size, in bytes. */
    public fun totalBytes(): Long = sortedFiles().sumOf { it.length() }

    private fun enforceBounds() {
        var files = sortedFiles()
        while (files.size > maxFrames) {
            files.first().delete()
            files = files.drop(1)
        }
        while (files.isNotEmpty() && files.sumOf { it.length() } > maxTotalBytes) {
            files.first().delete()
            files = files.drop(1)
        }
    }

    private fun sortedFiles(): List<File> = (dir.listFiles()?.toList() ?: emptyList()).sortedBy { it.name }

    public companion object {
        /** A generous cap on the *number* of frames a single field session accumulates — this
         * matters less than [DEFAULT_MAX_TOTAL_BYTES] in practice (a few hundred small PNGs stay
         * well under the byte cap first) but a count bound is what FR-OBS-7 asks for explicitly,
         * independent of size. */
        public const val DEFAULT_MAX_FRAMES: Int = 200

        /** FR-OBS-7's byte bound. A ~390px-wide PNG of a mostly-dark, mostly-flat instrument
         * screen (`design-guide.md` §1: "dark, dense, monospaced") compresses to tens of
         * kilobytes; 8 MiB comfortably holds a full field session's worth without approaching the
         * size of a single retained-audio segment this same bundle might also carry. */
        public const val DEFAULT_MAX_TOTAL_BYTES: Long = 8L * 1024 * 1024
    }
}
