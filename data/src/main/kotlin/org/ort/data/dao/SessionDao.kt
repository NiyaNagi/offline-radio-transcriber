package org.ort.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.TerminationReason

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
}
