package org.ort.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.ort.core.TransmissionState
import org.ort.data.entity.TransmissionEntity

@Dao
public interface TransmissionDao {

    @Insert
    public suspend fun insert(entity: TransmissionEntity)

    @Query("SELECT * FROM transmission WHERE id = :id")
    public suspend fun getById(id: String): TransmissionEntity?

    @Query("SELECT * FROM transmission WHERE id = :id")
    public fun observeById(id: String): Flow<TransmissionEntity?>

    @Query("SELECT * FROM transmission WHERE sessionId = :sessionId ORDER BY samplePosition")
    public suspend fun listBySession(sessionId: String): List<TransmissionEntity>

    @Query("SELECT * FROM transmission")
    public suspend fun listAll(): List<TransmissionEntity>

    /** Crash recovery (FR-RUN-8 → AC-47): every transmission left `PROCESSING` at launch. */
    @Query("SELECT * FROM transmission WHERE processingState = 'PROCESSING'")
    public suspend fun findAllProcessing(): List<TransmissionEntity>

    /** Raw state write. Callers go through [org.ort.data.requireLegalTransition] first. */
    @Query("UPDATE transmission SET processingState = :state WHERE id = :id")
    public suspend fun setProcessingState(id: String, state: TransmissionState)

    @Query("UPDATE transmission SET isReprocessCandidate = :value WHERE id = :id")
    public suspend fun setReprocessCandidate(id: String, value: Boolean)
}
