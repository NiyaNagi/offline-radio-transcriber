package org.ort.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import org.ort.data.entity.CaptureGapEntity

@Dao
public interface CaptureGapDao {

    @Insert
    public suspend fun insert(entity: CaptureGapEntity)

    @Query("SELECT * FROM capture_gap WHERE sessionId = :sessionId ORDER BY startedAt")
    public suspend fun listBySession(sessionId: String): List<CaptureGapEntity>
}
