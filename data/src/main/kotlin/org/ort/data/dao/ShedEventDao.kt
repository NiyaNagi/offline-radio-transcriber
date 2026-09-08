package org.ort.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import org.ort.data.entity.ShedEventEntity

/** FR-RUN-3, FR-RUN-5 — surfacing and observing shed events (F-021). */
@Dao
public interface ShedEventDao {

    @Insert
    public suspend fun insert(entity: ShedEventEntity)

    @Query("SELECT * FROM shed_event WHERE sessionId = :sessionId ORDER BY atWallMillis")
    public suspend fun listBySession(sessionId: String): List<ShedEventEntity>

    @Query("SELECT * FROM shed_event WHERE sessionId = :sessionId ORDER BY atWallMillis DESC LIMIT 1")
    public suspend fun latestForSession(sessionId: String): ShedEventEntity?
}
