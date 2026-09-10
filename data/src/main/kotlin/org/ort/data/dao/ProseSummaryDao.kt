package org.ort.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.ort.data.entity.ProseSummaryEntity

/** FR-DIG-3, FR-DIG-11 — read/write path for generated per-thread topic summaries (schema v8). */
@Dao
public interface ProseSummaryDao {

    /** Replaces any existing summary for the same thread — one summary per thread, never a history. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsert(entity: ProseSummaryEntity)

    @Query("SELECT * FROM prose_summary WHERE threadId = :threadId")
    public suspend fun getByThreadId(threadId: String): ProseSummaryEntity?

    @Query("SELECT * FROM prose_summary WHERE threadId IN (:threadIds)")
    public suspend fun getByThreadIds(threadIds: List<String>): List<ProseSummaryEntity>

    @Query("SELECT * FROM prose_summary")
    public suspend fun listAll(): List<ProseSummaryEntity>
}
