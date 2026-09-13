package org.ort.pipeline.reprocess

import java.io.File

/**
 * register R-1067 (FR-REP-11): the durable checkpoint [ReprocessWorker] reads and writes so a
 * worker attempt restarted after process death (WorkManager's own automatic retry of interrupted
 * work — never scheduling this class does itself) resumes the transmission ids its previous
 * attempt had not yet finished, rather than either restarting the whole run from zero (wasted,
 * though idempotent — see [ReprocessRunner]'s own kdoc on `WorkQueue`/Pass B supersession) or,
 * worse, ever reporting an id this attempt never actually processed as done.
 *
 * One file per [WorkId], named by it — `androidx.work.ListenableWorker.getId()` is stable across
 * every retry of the *same* enqueued unique-work request (WorkManager reuses the WorkSpec id), and
 * a fresh operator-started run after a prior one finished or was cancelled always gets a new id, so
 * a leftover file from a finished/cancelled run is never read again ([clear] deletes it on the
 * paths that do not need a resume; an orphan left behind by a genuine process death is inert until
 * WorkManager retries that exact id, which is exactly when it must exist).
 *
 * Deliberately a flat text file under the caller's own `filesDir`, never a `:data` table — this
 * package does not own `:data`'s schema, and a worker's own resume checkpoint is disposable process
 * bookkeeping, not a durable product record (constitution III's "never delete quietly" governs
 * transcripts and attributions, not this).
 *
 * Not thread-safe by design — [ReprocessWorker] is the only caller, and calls it from its own
 * single sequential collection of [ReprocessRunner.run]'s `Flow`, never concurrently.
 */
internal class ReprocessRunState(filesDir: File, workId: String) {

    private val dir = File(filesDir, DIR_NAME)
    private val file = File(dir, "$workId.txt")

    /**
     * The ids still to process for this work id: [allIds] itself on the very first call (also
     * persisted as the starting checkpoint), or whatever an earlier, interrupted attempt for the
     * *same* [workId] left behind on every subsequent call — a real resume, never a guess.
     */
    fun remainingOrInit(allIds: List<String>): List<String> {
        if (file.exists()) return readIds()
        write(allIds.size, allIds)
        return allIds
    }

    /** The total handed to [remainingOrInit] on this work id's first call — stable across a
     * resume, so [ReprocessWorker] can report honest cumulative progress against the run's real
     * original size rather than [ReprocessRunner.run]'s own per-attempt count, which restarts at 0
     * for whatever subset a resumed attempt is handed. */
    fun originalTotal(fallback: Int): Int = if (file.exists()) readTotal() ?: fallback else fallback

    /** Removes [transmissionId] from the durable remaining set — called once for every id
     * [ReprocessRunner.run] reports as done (completed, rejected or terminally failed; never for
     * one still in flight when a stop arrives, which is the entire point). */
    fun markDone(transmissionId: String) {
        val total = originalTotal(0)
        write(total, readIds() - transmissionId)
    }

    /** The ids not yet marked done for this work id. */
    fun remaining(): List<String> = if (file.exists()) readIds() else emptyList()

    /** Deletes this work id's checkpoint — called once it will never be resumed: the run finished
     * (successfully or with a permanent failure) or the operator explicitly cancelled it. Never
     * called when a stop was not this class's own choice (see [ReprocessWorker]) — that is exactly
     * the case a resume must still find this file. */
    fun clear() {
        file.delete()
    }

    private fun readIds(): List<String> = file.readLines().drop(1).filter { it.isNotEmpty() }

    private fun readTotal(): Int? = file.readLines().firstOrNull()?.toIntOrNull()

    private fun write(total: Int, ids: List<String>) {
        dir.mkdirs()
        val tmp = File(dir, "${file.name}.tmp")
        tmp.writeText((listOf(total.toString()) + ids).joinToString("\n"))
        // Atomic on both the Android filesystem and a plain JVM one (same mechanism
        // `ReprocessRunState`'s sibling `ModelFileVerifier`/`BundledAssetInstaller` already rely on
        // for their own install records) -- a crash between these two lines leaves either the old
        // file or nothing, never a half-written one `readIds`/`readTotal` could misparse.
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private companion object {
        const val DIR_NAME = "reprocess-run-state"
    }
}
