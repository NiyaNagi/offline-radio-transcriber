package org.ort.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.ort.core.AttributionState
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

    /**
     * Writes a resolved [org.ort.core.Attribution]'s fields (build-plan P12, defect 3: Pass B's
     * resolver output previously had nowhere to land). The attribution's non-optional state
     * (constitution I) is always written; [stationId]/[confidence]/[sourceTransmissionId] are
     * null exactly when the state does not carry them (`AMBIGUOUS`/`UNKNOWN`).
     *
     * `AND corrected = 0` (build-plan P16, FR-SPK-7): a machine re-resolution must never silently
     * overwrite a user's correction. This is the structural half of the `CORRECTED` lock —
     * [org.ort.data.dao.CorrectionDao.recordCorrection] is the only way to change the attribution
     * of a transmission once `corrected` is set, and it does so deliberately, not through here.
     */
    @Query(
        "UPDATE transmission SET attributionState = :state, stationId = :stationId, " +
            "attributionConfidence = :confidence, attributionSourceTransmissionId = :sourceTransmissionId " +
            "WHERE id = :id AND corrected = 0",
    )
    public suspend fun updateAttribution(
        id: String,
        state: AttributionState,
        stationId: String?,
        confidence: Double?,
        sourceTransmissionId: String?,
    )

    /**
     * Records why a rejection-pipeline control refused a segment, at the persisted layer (AC-8:
     * a rejection is a result that stays reachable, not just in the in-memory
     * [org.ort.asrapi.RejectedSegmentLog] build-plan P10 built).
     */
    @Query("UPDATE transmission SET rejectionReason = :reason WHERE id = :id")
    public suspend fun setRejectionReason(id: String, reason: String)
}
