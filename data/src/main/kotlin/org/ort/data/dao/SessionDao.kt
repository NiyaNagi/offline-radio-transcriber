package org.ort.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import org.ort.data.entity.SessionEntity

@Dao
public interface SessionDao {

    @Insert
    public suspend fun insert(entity: SessionEntity)

    @Query("SELECT * FROM session WHERE id = :id")
    public suspend fun getById(id: String): SessionEntity?

    @Query("SELECT * FROM session ORDER BY startedAt DESC")
    public suspend fun listAll(): List<SessionEntity>
}
