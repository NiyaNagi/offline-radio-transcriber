package org.ort.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.TerminationReason

/**
 * FR-CAP-13, AC-129 (schema v7): the minimal shape a later reader (WPC2's `RealCaptureService`,
 * WPE's settings screens, WPF's status/digest surfaces) needs to distinguish a session's capture
 * mode and route without loading the whole [SessionEntity] — see [SessionDao.getCaptureInfo].
 */
public data class SessionCaptureInfo(
    val id: String,
    val captureMode: String?,
    val audioRouteKind: String?,
    val audioRouteLabel: String?,
    val bluetoothProfile: String?,
    val rigTransport: String?,
    /** E2-A07 (schema v10) — see [org.ort.data.entity.SessionEntity.rigDescriptorId]'s own kdoc. */
    val rigDescriptorId: String? = null,
    /** E2-A07 (schema v10) — see [org.ort.data.entity.SessionEntity.audioRouteVerified]'s own kdoc. */
    val audioRouteVerified: Boolean? = null,
    /** E2-A07 (schema v10) — see [org.ort.data.entity.SessionEntity.audioNativeRateHz]'s own kdoc. */
    val audioNativeRateHz: Int? = null,
)

@Dao
public interface SessionDao {

    @Insert
    public suspend fun insert(entity: SessionEntity)

    @Query("SELECT * FROM session WHERE id = :id")
    public suspend fun getById(id: String): SessionEntity?

    @Query("SELECT * FROM session ORDER BY startedAt DESC")
    public suspend fun listAll(): List<SessionEntity>

    /**
     * register R-173: every path that ends a session (a clean stop, an unclean stop, the storage
     * floor, a route-mismatch halt, or the previous session found still open at next launch) must
     * write [SessionEntity.endedAt] — before this existed, nothing ever did, so a real session read
     * "still running" forever (`Now-Idle`, `Sessions`). [terminationReason] is nullable because not
     * every ending path can name one precisely yet (see callers).
     */
    @Query("UPDATE session SET endedAt = :endedAt, terminationReason = :terminationReason WHERE id = :id")
    public suspend fun setEnded(id: String, endedAt: Long, terminationReason: TerminationReason?)

    /**
     * FR-RUN-16 (register R-173): the on-next-launch recovery variant of [setEnded] — guarded by
     * `endedAt IS NULL` so a session some other, more precise path already closed (e.g.
     * `RealCaptureService.stopCaptureInternal`, which knows the real stop moment) is never
     * clobbered by a stale "last heartbeat" timestamp discovered later, on the following launch.
     */
    @Query(
        "UPDATE session SET endedAt = :endedAt, terminationReason = :terminationReason " +
            "WHERE id = :id AND endedAt IS NULL",
    )
    public suspend fun closeIfStillOpen(id: String, endedAt: Long, terminationReason: TerminationReason?)

    /**
     * FR-CAP-13, AC-129: the fields that distinguish room audio from a radio session, without
     * reading anything else about it — never reads audio, matching AC-129's own criterion. E2-A07
     * added the trailing three columns; a pre-v10 row reads them honestly as `NULL`.
     */
    @Query(
        "SELECT id, captureMode, audioRouteKind, audioRouteLabel, bluetoothProfile, rigTransport, " +
            "rigDescriptorId, audioRouteVerified, audioNativeRateHz FROM session WHERE id = :id",
    )
    public suspend fun getCaptureInfo(id: String): SessionCaptureInfo?

    /**
     * E2-A07: [org.ort.data.entity.SessionEntity.audioRouteVerified], written once the OS's first
     * real read either confirms the selected route or a mismatch halts capture —
     * [RealCaptureService][org.ort.pipeline.capture.RealCaptureService] is the only writer, and
     * only ever the first time either outcome resolves for a given session (see its own report).
     */
    @Query("UPDATE session SET audioRouteVerified = :verified WHERE id = :id")
    public suspend fun setAudioRouteVerified(id: String, verified: Boolean)

    /**
     * WPARC (FR-SEG-9, AC-96): marks this session's continuous archive as kept/re-segmentable —
     * written once, when the session ends, only if at least one archive chunk was actually
     * persisted (constitution I: never fabricate an archive that does not exist).
     */
    @Query("UPDATE session SET archiveState = 'KEPT' WHERE id = :id")
    public suspend fun setArchiveKept(id: String)

    /**
     * WPARC (FR-STO-3d, AC-150, AC-151): marks this session's archive as pruned — the row (and
     * [org.ort.data.entity.SessionEntity.archiveRemovedAtMillis]) stays, never deleted quietly.
     * Only ever applied to a session whose [org.ort.data.entity.SessionEntity.archiveState] was
     * `"KEPT"` — the caller ([org.ort.pipeline.archive.ArchivePruner]) is responsible for
     * oldest-first ordering.
     */
    @Query("UPDATE session SET archiveState = 'REMOVED', archiveRemovedAtMillis = :removedAtMillis WHERE id = :id")
    public suspend fun setArchiveRemoved(id: String, removedAtMillis: Long)

    /**
     * WPDATA (FR-STO-3e, D40, P9): marks this session's over audio as deleted by the operator —
     * the row (and [org.ort.data.entity.SessionEntity.overAudioRemovedAtMillis]) stays, never
     * deleted quietly. Unlike [setArchiveRemoved] there is no automatic caller: FR-STO-3e forbids
     * any automatic deletion of over audio, so the only writer is an explicit operator action
     * ([org.ort.pipeline.archive.SessionAudioDeletionService]).
     */
    @Query("UPDATE session SET overAudioRemovedAtMillis = :removedAtMillis WHERE id = :id")
    public suspend fun setOverAudioRemoved(id: String, removedAtMillis: Long)
}
