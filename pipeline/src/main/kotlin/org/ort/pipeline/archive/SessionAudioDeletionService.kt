package org.ort.pipeline.archive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ort.core.Clock
import org.ort.core.SystemClock
import org.ort.data.OrtDatabase
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.measureDirectoryBytes
import java.io.File

/**
 * `Recording-Session.dc.html`'s (RC02) Delete action: what the operator may ask to have removed
 * — this session's over audio, its raw continuous archive, or both.
 */
public enum class SessionAudioTarget { OVER_AUDIO, RAW_ARCHIVE, BOTH }

/**
 * Typed refusal reasons RC02's confirm sheet states verbatim — never a generic thrown exception
 * the UI has to parse (constitution II: assertions/refusals never depend on message text).
 */
public sealed interface SessionAudioDeletionRefusal {
    /** [sessionId] passed to [SessionAudioDeletionService] does not exist. */
    public data object SessionNotFound : SessionAudioDeletionRefusal

    /** Constitution IV: a session's own live audio must never be deleted out from under it. */
    public data object SessionCapturing : SessionAudioDeletionRefusal

    /** At least one transmission in this session has an active (`READY`/`LEASED`/`DEFERRED`)
     * work-queue item — a pass is either running against this session's audio right now or is
     * queued to, live capture or a reprocess alike ([org.ort.data.dao.WorkQueueDao.countActiveForSession]). */
    public data object ProcessingInProgress : SessionAudioDeletionRefusal
}

/**
 * `Recording-Session.dc.html`'s Delete action itself renders from this **before** the operator
 * taps anything ("frees 0.61 GB") and its confirm sheet repeats the same figure — both real,
 * measured bytes from the files that actually exist on disk right now
 * ([org.ort.pipeline.capture.measureDirectoryBytes]), never an estimate (constitution I, VI).
 */
public data class SessionAudioDeletionPreview(
    public val sessionId: String,
    public val overAudioBytes: Long,
    public val overAudioAlreadyRemoved: Boolean,
    public val archiveBytes: Long,
    public val archiveState: ArchiveState,
) {
    public fun bytesFor(target: SessionAudioTarget): Long = when (target) {
        SessionAudioTarget.OVER_AUDIO -> overAudioBytes
        SessionAudioTarget.RAW_ARCHIVE -> archiveBytes
        SessionAudioTarget.BOTH -> overAudioBytes + archiveBytes
    }
}

public sealed interface SessionAudioDeletionResult {
    public data class Refused(public val reason: SessionAudioDeletionRefusal) : SessionAudioDeletionResult

    /**
     * [overAudioRemovedAtMillis]/[archiveRemovedAtMillis] are `null` exactly when [target] did not
     * cover that half, or that half had nothing to remove in the first place (e.g.
     * [SessionAudioTarget.RAW_ARCHIVE] against a session whose archive was always
     * [ArchiveState.NONE] — never fabricated as a removal that did not happen).
     */
    public data class Deleted(
        public val target: SessionAudioTarget,
        public val bytesFreed: Long,
        public val overAudioRemovedAtMillis: Long?,
        public val archiveRemovedAtMillis: Long?,
    ) : SessionAudioDeletionResult
}

/**
 * FR-STO-3, FR-STO-3b, FR-STO-3e, D40, P9: operator-initiated deletion of one session's retained
 * audio, from `Recording-Session.dc.html`'s (RC02) Delete action. `Recordings.dc.html` (RC01)
 * reads [preview] before the operator opens a session at all (its own storage cards), and RC02's
 * Delete button and confirm sheet both read it again for this one session.
 *
 * **Follows [ArchivePruner]'s own mark-then-delete shape** (see [pruneArchiveIfOverBudget]): the
 * database row is updated to say the audio is gone *before* the directory is actually removed, so
 * a crash between the two steps leaves, at worst, a removed/timestamped row whose directory still
 * happens to exist on disk — never the reverse (a deleted directory with no record of its own
 * removal, which would violate P9's "nothing is deleted quietly"). [delete] is idempotent: calling
 * it again after a crash (or a genuine repeat request) finds the mark already made, does not
 * re-mark (the original removal timestamp is preserved, never overwritten by a retry) and simply
 * re-attempts the file removal, which is itself idempotent ([File.deleteRecursively] returns `true`
 * without error on a directory that is already gone).
 *
 * The raw-archive half of this reuses [org.ort.data.dao.SessionDao.setArchiveRemoved] and
 * [org.ort.data.entity.SessionEntity.archiveState]/`.archiveRemovedAtMillis` — the exact same
 * write path [pruneArchiveIfOverBudget]'s automatic pruning already uses — rather than inventing a
 * second removal record for the same directory; the over-audio half adds the one thing that did
 * not already exist, [org.ort.data.dao.SessionDao.setOverAudioRemoved].
 *
 * **Transcripts, attributions and the session row itself are never touched.** RC02's Delete only
 * ever removes audio (FR-STO-3, P9: "a deletion SHALL leave the session and its overs listed with
 * what was removed and when").
 */
public object SessionAudioDeletionService {

    public suspend fun preview(db: OrtDatabase, filesDir: File, sessionId: String): SessionAudioDeletionPreview? =
        withContext(Dispatchers.IO) {
            val session = db.sessionDao().getById(sessionId) ?: return@withContext null
            val archiveState = archiveStateOf(session.archiveState)
            SessionAudioDeletionPreview(
                sessionId = sessionId,
                overAudioBytes = if (session.overAudioRemovedAtMillis == null) {
                    measureDirectoryBytes(File(filesDir, "$OVER_AUDIO_DIR_NAME/$sessionId"))
                } else {
                    0L
                },
                overAudioAlreadyRemoved = session.overAudioRemovedAtMillis != null,
                archiveBytes = if (archiveState == ArchiveState.KEPT) {
                    measureDirectoryBytes(File(filesDir, "$ARCHIVE_DIR_NAME/$sessionId"))
                } else {
                    0L
                },
                archiveState = archiveState,
            )
        }

    /** `null` means deletion is allowed; otherwise the typed reason to surface verbatim. Checked
     * fresh on every call — neither fact is cached (constitution IV: a route or queue state that
     * changed a moment ago must never be answered from a stale read). */
    public suspend fun canDelete(db: OrtDatabase, sessionId: String): SessionAudioDeletionRefusal? =
        withContext(Dispatchers.IO) {
            db.sessionDao().getById(sessionId) ?: return@withContext SessionAudioDeletionRefusal.SessionNotFound
            if (CaptureState.isCapturing && CaptureState.sessionId == sessionId) {
                return@withContext SessionAudioDeletionRefusal.SessionCapturing
            }
            if (db.workQueueDao().countActiveForSession(sessionId) > 0) {
                return@withContext SessionAudioDeletionRefusal.ProcessingInProgress
            }
            null
        }

    /**
     * Mark-then-delete, per [SessionAudioTarget]. [SessionAudioTarget.BOTH] runs the two halves
     * one after the other, each independently crash-safe, so a crash between them simply leaves
     * one half done and the other exactly as before — the next call (this function again) resumes
     * correctly with no special-cased "resume" path (see class kdoc).
     */
    public suspend fun delete(
        db: OrtDatabase,
        filesDir: File,
        sessionId: String,
        target: SessionAudioTarget,
        clock: Clock = SystemClock,
    ): SessionAudioDeletionResult = withContext(Dispatchers.IO) {
        canDelete(db, sessionId)?.let { return@withContext SessionAudioDeletionResult.Refused(it) }

        var bytesFreed = 0L
        var overAudioRemovedAt: Long? = null
        var archiveRemovedAt: Long? = null

        if (target == SessionAudioTarget.OVER_AUDIO || target == SessionAudioTarget.BOTH) {
            val (bytes, removedAt) = deleteOverAudio(db, filesDir, sessionId, clock)
            bytesFreed += bytes
            overAudioRemovedAt = removedAt
        }
        if (target == SessionAudioTarget.RAW_ARCHIVE || target == SessionAudioTarget.BOTH) {
            val (bytes, removedAt) = deleteArchive(db, filesDir, sessionId, clock)
            bytesFreed += bytes
            archiveRemovedAt = removedAt
        }

        SessionAudioDeletionResult.Deleted(target, bytesFreed, overAudioRemovedAt, archiveRemovedAt)
    }

    /**
     * Bytes are measured *immediately before* the real deletion, regardless of whether the row was
     * already marked by an earlier, crashed attempt — a prior mark alone never freed anything (the
     * files are still on disk until this actually runs), so reporting the freshly-measured figure
     * here is the honest one, not `0` (constitution VI: never report a stale or fabricated number).
     */
    private suspend fun deleteOverAudio(
        db: OrtDatabase,
        filesDir: File,
        sessionId: String,
        clock: Clock,
    ): Pair<Long, Long> {
        val session = db.sessionDao().getById(sessionId)
            ?: error("session $sessionId vanished mid-deletion -- caller must not race a session's own removal")
        val dir = File(filesDir, "$OVER_AUDIO_DIR_NAME/$sessionId")
        val bytesFreed = measureDirectoryBytes(dir)
        val removedAt = session.overAudioRemovedAtMillis ?: clock.wallMillis().also {
            db.sessionDao().setOverAudioRemoved(sessionId, it)
        }
        dir.deleteRecursively()
        return bytesFreed to removedAt
    }

    /** `null` only when this session's archive was always [ArchiveState.NONE] — nothing was ever
     * archived, so nothing is marked and nothing is deleted (constitution I: never fabricate a
     * `REMOVED` state for an archive that never existed). */
    private suspend fun deleteArchive(
        db: OrtDatabase,
        filesDir: File,
        sessionId: String,
        clock: Clock,
    ): Pair<Long, Long?> {
        val session = db.sessionDao().getById(sessionId)
            ?: error("session $sessionId vanished mid-deletion -- caller must not race a session's own removal")
        if (session.archiveState == null) return 0L to null
        val dir = File(filesDir, "$ARCHIVE_DIR_NAME/$sessionId")
        val bytesFreed = measureDirectoryBytes(dir)
        val removedAt = session.archiveRemovedAtMillis ?: clock.wallMillis().also {
            db.sessionDao().setArchiveRemoved(sessionId, it)
        }
        dir.deleteRecursively()
        return bytesFreed to removedAt
    }

    private fun archiveStateOf(raw: String?): ArchiveState = when (raw) {
        "KEPT" -> ArchiveState.KEPT
        "REMOVED" -> ArchiveState.REMOVED
        else -> ArchiveState.NONE
    }

    private const val OVER_AUDIO_DIR_NAME = "audio"
}
