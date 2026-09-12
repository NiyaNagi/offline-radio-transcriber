package org.ort.pipeline.archive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ort.core.Clock
import org.ort.core.SystemClock
import org.ort.data.OrtDatabase
import org.ort.pipeline.capture.measureDirectoryBytes
import java.io.File

/**
 * FR-SEG-9, FR-STO-3d, D39: one session's continuous-archive contribution to the archive budget,
 * oldest-first. Only sessions whose [org.ort.data.entity.SessionEntity.archiveState] is `"KEPT"`
 * count — a session with no archive, or already `"REMOVED"`, contributes `0` and is never
 * re-pruned.
 */
public data class ArchiveSessionSummary(
    public val sessionId: String,
    public val startedAtMillis: Long,
    public val bytes: Long,
)

/** FR-SEG-9, FR-STO-3d: this session's continuous-archive state — the read API the UI package
 * (Recordings, the storage screen) consumes. */
public enum class ArchiveState { NONE, KEPT, REMOVED }

/** FR-SEG-9, AC-151: [resegmentable] is true only while the archive is actually present — a
 * `REMOVED` session's row (and [removedAtMillis]) stays listed, but it can no longer be
 * re-segmented (the archive it would replay is gone, deleted per FR-STO-3d). */
public data class SessionArchiveState(
    public val sessionId: String,
    public val state: ArchiveState,
    public val removedAtMillis: Long?,
) {
    public val resegmentable: Boolean get() = state == ArchiveState.KEPT
}

/**
 * FR-STO-3d/D39 (register): every session with a kept archive, oldest-first — the ordering
 * [pruneArchiveIfOverBudget] deletes in. Mirrors
 * [org.ort.pipeline.capture.collectSessionStorageSummaries]'s own shape and reasoning for over
 * audio, applied to the separate `archive/<sessionId>` directory instead of `audio/<sessionId>`.
 */
public suspend fun collectArchiveSessionSummaries(db: OrtDatabase, filesDir: File): List<ArchiveSessionSummary> =
    withContext(Dispatchers.IO) {
        db.sessionDao().listAll()
            .filter { it.archiveState == "KEPT" }
            .sortedBy { it.startedAt }
            .map { session ->
                ArchiveSessionSummary(
                    sessionId = session.id,
                    startedAtMillis = session.startedAt,
                    bytes = measureDirectoryBytes(File(filesDir, "$ARCHIVE_DIR_NAME/${session.id}")),
                )
            }
    }

/** FR-SEG-9: every session's real archive state — the per-session read API the UI package
 * consumes (Recordings, a session's own detail screen). `NONE` for a pre-WPARC row or a session
 * captured with the archive off (constitution I: never fabricate a `KEPT`/`REMOVED` that never
 * happened). */
public suspend fun archiveSessionStates(db: OrtDatabase): List<SessionArchiveState> = withContext(Dispatchers.IO) {
    db.sessionDao().listAll().map { session ->
        SessionArchiveState(
            sessionId = session.id,
            state = when (session.archiveState) {
                "KEPT" -> ArchiveState.KEPT
                "REMOVED" -> ArchiveState.REMOVED
                else -> ArchiveState.NONE
            },
            removedAtMillis = session.archiveRemovedAtMillis,
        )
    }
}

/**
 * FR-STO-3d, AC-150, AC-151, D39: deletes the oldest `KEPT` archive directories, one at a time,
 * until total archive usage is at or under [budgetBytes] — **never** touches `audio/<sessionId>`
 * (over audio; the product, never pruned to make room for the archive, the training material this
 * budget exists to bound). Each pruned session's row is marked `REMOVED` with the real wall-clock
 * moment *before* its directory is deleted, so an interrupted run at worst leaves a `REMOVED` row
 * whose directory still happens to exist on disk — never the reverse (a deleted archive whose
 * removal was never recorded, P9's "nothing is deleted quietly"). Returns the pruned session ids,
 * oldest-first.
 */
public suspend fun pruneArchiveIfOverBudget(
    db: OrtDatabase,
    filesDir: File,
    budgetBytes: Long,
    clock: Clock = SystemClock,
): List<String> = withContext(Dispatchers.IO) {
    val summaries = collectArchiveSessionSummaries(db, filesDir).toMutableList()
    var totalBytes = summaries.sumOf { it.bytes }
    val pruned = mutableListOf<String>()
    while (totalBytes > budgetBytes && summaries.isNotEmpty()) {
        val oldest = summaries.removeAt(0)
        db.sessionDao().setArchiveRemoved(oldest.sessionId, clock.wallMillis())
        File(filesDir, "$ARCHIVE_DIR_NAME/${oldest.sessionId}").deleteRecursively()
        totalBytes -= oldest.bytes
        pruned.add(oldest.sessionId)
    }
    pruned
}

/** The archive's own directory, sibling to `audio/` — see this package's report for why a
 * separate directory (never under `audio/`) is what keeps the two budgets independently
 * accountable (FR-STO-3). */
public const val ARCHIVE_DIR_NAME: String = "archive"
