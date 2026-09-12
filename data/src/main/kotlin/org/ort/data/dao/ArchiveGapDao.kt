package org.ort.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import org.ort.data.entity.ArchiveGapEntity

/** WPARC (FR-RUN-12) — see [ArchiveGapEntity]'s own doc comment. */
@Dao
public interface ArchiveGapDao {

    @Insert
    public suspend fun insert(entity: ArchiveGapEntity)

    @Query("SELECT * FROM archive_gap WHERE sessionId = :sessionId ORDER BY startSample")
    public suspend fun listBySession(sessionId: String): List<ArchiveGapEntity>
}
