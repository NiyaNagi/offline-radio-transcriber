package org.ort.pipeline.capture

import org.ort.capture.android.heartbeat.HeartbeatRecord
import org.ort.capture.android.proveit.ProveItAnalyzer
import java.io.File

/**
 * Register R-1115: one row per beat, unlike [org.ort.capture.android.heartbeat.HeartbeatStore]'s
 * single overwritten record. [framesObservedSinceLastBeat] is the fact that store cannot carry at
 * all -- whether audio frames actually reached [RealCaptureService] since the previous beat, so a
 * reader can tell a frames-starved stretch (beats keep landing on schedule, this is `false` on
 * every one of them) from the OS having killed the process outright (no new row lands at all,
 * discovered as a gap against the last row once/if capture ever resumes -- see
 * [heartbeatGapMillisOrNull]). Lives in `:pipeline`, not `:capture-android`: this builder's brief
 * leaves `:capture-android` to other work in flight the same night, and nothing here needs
 * anything `:capture-android`-only -- [HeartbeatRecord] is the one type reused from it, exactly the
 * way [RealCaptureService] already reuses it for the single-record store.
 */
public data class HeartbeatTrailEntry(
    val sessionId: String,
    val wallMillis: Long,
    val monotonicNanos: Long,
    val samplePosition: Long,
    val framesObservedSinceLastBeat: Boolean,
)

/**
 * Constitution III ("nothing is deleted quietly"): what [HeartbeatTrailStore.rotationSummary]
 * reports once the trail has ever been rotated. The rotated-out rows themselves are gone from
 * [HeartbeatTrailStore.recent] -- the whole point of bounding the trail -- but their count and
 * wall-time span are not; they accumulate across every rotation this store has ever performed.
 */
public data class HeartbeatTrailRotation(
    val droppedCount: Int,
    val oldestDroppedWallMillis: Long,
    val newestDroppedWallMillis: Long,
)

/** An append-only, bounded trail of [HeartbeatTrailEntry] rows (R-1115). */
public interface HeartbeatTrailStore {
    public fun append(entry: HeartbeatTrailEntry)

    /** The most recent rows, oldest first, at most [limit] of them. */
    public fun recent(limit: Int = DEFAULT_MAX_ENTRIES): List<HeartbeatTrailEntry>

    /** `null` until this store has ever rotated a row out. */
    public fun rotationSummary(): HeartbeatTrailRotation?

    public fun clear()

    public companion object {
        /** 960 beats at the 30 s cadence [RealCaptureService] ticks on is 8 hours -- NFR-8's own
         * overnight gate -- so a trail this bounded still covers the whole run the gate cares
         * about, not just D8's 30-minute sample of it. */
        public const val DEFAULT_MAX_ENTRIES: Int = 960
    }
}

/**
 * File-backed [HeartbeatTrailStore]: one small append-only text file, rewritten (not appended
 * with a raw file handle) on every beat so a rotation can happen in the same write -- the file
 * never holds more than [maxEntries] rows. `:capture-android` cannot depend on `:data` for the
 * same reason [org.ort.capture.android.heartbeat.FileHeartbeatStore] is not a Room table either
 * (see that class's own kdoc); this is the identical convention, one layer up, for a trail rather
 * than a single record.
 *
 * **Rotation never deletes quietly.** Once [append] would push the file past [maxEntries] rows,
 * the oldest overflow rows are folded into [rotationFile] -- their count and wall-time span,
 * merged with whatever was already recorded there -- before being dropped from the main file, so
 * [rotationSummary] can always answer "how many, and from when" for everything this store has
 * ever rotated out, not just the window [recent] currently holds.
 *
 * **Never touches the capture path.** Every call here is a bounded, synchronous file operation on
 * a small (at most [maxEntries]-row) file, but nothing in this class is ever invoked from the
 * audio-frame path itself: [RealCaptureService]'s own heartbeat ticker calls it from its own
 * coroutine, decoupled from frame arrival (constitution IV) -- see that ticker's kdoc for why that
 * decoupling is also what makes a frames-starved stretch distinguishable from a kill at all.
 *
 * **`@Synchronized`, not because two threads are expected to race here in the steady state, but
 * because [RealCaptureService] has two independent writers of this one instance** -- the
 * frame/transmission-triggered dispatch (fire-and-forget, off the frame path) and the periodic
 * ticker -- and [append]/[recordRotation] are read-modify-write over the same file; without this,
 * two near-simultaneous calls could race and lose a row, which is exactly the "deleted quietly"
 * constitution III forbids, just by a different mechanism than an unbounded file.
 */
public class FileHeartbeatTrailStore(
    private val file: File,
    private val maxEntries: Int = HeartbeatTrailStore.DEFAULT_MAX_ENTRIES,
) : HeartbeatTrailStore {

    private val rotationFile: File get() = File(file.parentFile, "${file.name}.rotated")

    @Synchronized
    override fun append(entry: HeartbeatTrailEntry) {
        file.parentFile?.mkdirs()
        val updated = readLinesSafely() + encode(entry)
        if (updated.size > maxEntries) {
            val overflow = updated.size - maxEntries
            recordRotation(updated.take(overflow))
            writeLines(updated.drop(overflow))
        } else {
            writeLines(updated)
        }
    }

    @Synchronized
    override fun recent(limit: Int): List<HeartbeatTrailEntry> = readLinesSafely().mapNotNull(::decode).takeLast(limit)

    @Synchronized
    override fun rotationSummary(): HeartbeatTrailRotation? {
        if (!rotationFile.isFile) return null
        val parts = readTextSafely(rotationFile)?.trim()?.split(FIELD_DELIMITER) ?: return null
        if (parts.size < ROTATION_FIELD_COUNT) return null
        return try {
            HeartbeatTrailRotation(parts[0].toInt(), parts[1].toLong(), parts[2].toLong())
        } catch (e: NumberFormatException) {
            // A rotation record torn mid-write (a real crash/kill) is exactly as recoverable as no
            // rotation ever having happened -- never a crash reading it back (same discipline
            // FileHeartbeatStore.last() already applies to its own file).
            null
        }
    }

    @Synchronized
    override fun clear() {
        file.delete()
        rotationFile.delete()
    }

    private fun recordRotation(droppedLines: List<String>) {
        val dropped = droppedLines.mapNotNull(::decode)
        if (dropped.isEmpty()) return
        val oldest = dropped.minOf { it.wallMillis }
        val newest = dropped.maxOf { it.wallMillis }
        val prior = rotationSummary()
        val combined = HeartbeatTrailRotation(
            droppedCount = (prior?.droppedCount ?: 0) + dropped.size,
            oldestDroppedWallMillis = prior?.oldestDroppedWallMillis?.coerceAtMost(oldest) ?: oldest,
            newestDroppedWallMillis = prior?.newestDroppedWallMillis?.coerceAtLeast(newest) ?: newest,
        )
        rotationFile.parentFile?.mkdirs()
        val fields = listOf(combined.droppedCount, combined.oldestDroppedWallMillis, combined.newestDroppedWallMillis)
        rotationFile.writeText(fields.joinToString(FIELD_DELIMITER))
    }

    private fun writeLines(lines: List<String>) {
        file.writeText(if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n")
    }

    private fun readLinesSafely(): List<String> = readTextSafely(file)
        ?.lineSequence()
        ?.filter { it.isNotBlank() }
        ?.toList()
        ?: emptyList()

    private fun readTextSafely(target: File): String? {
        if (!target.isFile) return null
        return try {
            target.readText()
        } catch (e: java.io.FileNotFoundException) {
            // AC-5/FR-PLT-1-style TOCTOU race (FileHeartbeatStore.last() applies the identical
            // guard): a scenario reset or clear() can delete the file between the check and the
            // read. An absent trail reads as an empty one, never a crash.
            null
        }
    }

    private fun encode(entry: HeartbeatTrailEntry): String = listOf(
        entry.sessionId,
        entry.wallMillis,
        entry.monotonicNanos,
        entry.samplePosition,
        entry.framesObservedSinceLastBeat,
    ).joinToString(FIELD_DELIMITER)

    private fun decode(line: String): HeartbeatTrailEntry? {
        val parts = line.split(FIELD_DELIMITER)
        if (parts.size < ENTRY_FIELD_COUNT) return null
        return try {
            HeartbeatTrailEntry(
                sessionId = parts[0],
                wallMillis = parts[1].toLong(),
                monotonicNanos = parts[2].toLong(),
                samplePosition = parts[3].toLong(),
                framesObservedSinceLastBeat = parts[4].toBoolean(),
            )
        } catch (e: NumberFormatException) {
            // A row torn mid-write (a real crash/kill mid-append) is neither a clean value nor a
            // clean absence -- skipped, never a crash reading the rest of the trail back
            // (FileHeartbeatStore.last() applies the identical discipline to its one record).
            null
        }
    }

    private companion object {
        const val FIELD_DELIMITER: String = "|"
        const val ENTRY_FIELD_COUNT: Int = 5
        const val ROTATION_FIELD_COUNT: Int = 3
    }
}

/**
 * Register R-1115: the real, production wiring for [ProveItAnalyzer] and
 * [org.ort.pipeline.diagnostics.DiagnosticsLog.logHeartbeatGap] -- before this, neither had a
 * caller anywhere in `:pipeline` or `:app`. [RealCaptureService]'s heartbeat ticker calls this on
 * every beat with the immediately-preceding trail row (which, across a kill and relaunch, is the
 * last beat the PREVIOUS process ever wrote -- [HeartbeatTrailStore] is a file, so it survives
 * exactly the death this is meant to detect) and logs whatever gap [ProveItAnalyzer] -- the same
 * class D8's 30-minute prove-it test and the 8-hour gate both read -- finds between the two.
 *
 * A `null` result means [ProveItAnalyzer] found nothing in excess of [expectedIntervalMillis] plus
 * its own tolerance -- the ordinary case, on every beat but the first of a session and the first
 * after a kill. Extracted as a small top-level function, the same reasoning [buildHeartbeatRecord]
 * already documents for this file: testable without a `ServiceController` harness.
 */
internal fun heartbeatGapMillisOrNull(
    previous: HeartbeatTrailEntry?,
    current: HeartbeatTrailEntry,
    expectedIntervalMillis: Long,
): Long? {
    if (previous == null) return null
    val report = ProveItAnalyzer(expectedIntervalMillis).analyze(
        heartbeats = listOf(
            HeartbeatRecord(previous.sessionId, previous.monotonicNanos, previous.wallMillis, previous.samplePosition),
            HeartbeatRecord(current.sessionId, current.monotonicNanos, current.wallMillis, current.samplePosition),
        ),
        durationMillis = (current.wallMillis - previous.wallMillis).coerceAtLeast(0L),
        processRestarted = previous.sessionId != current.sessionId,
    )
    return if (report.gapCount > 0) report.maxGapMillis else null
}
