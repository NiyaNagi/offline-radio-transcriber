package org.ort.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import org.ort.data.entity.CorrectionEntity

/**
 * Build-plan P16's write path for [CorrectionEntity] (FR-UI-6, FR-SPK-7): one-tap correction of
 * an attribution, and the `CORRECTED` lock so a correction is never silently re-propagated over.
 * A new DAO file, not an edit to [CatalogDao]'s existing basic insert/read for [CorrectionEntity]
 * — kept conflict-free with concurrent sessions, per the prompt's own instruction to add a new
 * DAO file rather than touch an existing one.
 *
 * [recordCorrection] is the *only* write path that sets `transmission.corrected = 1`.
 * [TransmissionDao.updateAttribution] (the machine-resolution write path P12 built) now refuses
 * to write once that bit is set — see its own doc comment — so a later propagation pass cannot
 * silently overwrite a user's correction. That refusal, not this DAO, is what makes the lock
 * structural rather than a convention two call sites have to remember (constitution VII).
 *
 * Q8's tiered correction: [FIELD_STATION] records a correction that came from picking a resolved
 * candidate or a known station — a source that could in principle feed the priors back.
 * [FIELD_STATION_UNVERIFIED] records free text — Q8 requires this be *marked*, not quietly
 * treated as ground truth, so a future prior-feeding pass can tell the two apart from the
 * correction log alone. Both lock the attribution identically: the lock is about not silently
 * overwriting what the user just said, which applies whether or not the value is verified.
 */
@Dao
public interface CorrectionDao {

    @Insert
    public suspend fun insert(entity: CorrectionEntity)

    @Query("SELECT * FROM correction WHERE transmissionId = :transmissionId ORDER BY correctedAt")
    public suspend fun correctionsFor(transmissionId: String): List<CorrectionEntity>

    @Query("SELECT corrected FROM transmission WHERE id = :transmissionId")
    public suspend fun isCorrected(transmissionId: String): Boolean?

    /**
     * Always succeeds — a deliberate user correction may always supersede an earlier attribution,
     * including an earlier correction, and re-setting the lock is idempotent. Always produces the
     * exact shape [org.ort.core.Attribution.withCorrection] does: `INFERRED`, no confidence, no
     * propagation source (`CONFIRMED` means heard in *this* transmission — constitution I — and a
     * correction typed or picked after the fact never claims that).
     */
    @Query(
        "UPDATE transmission SET attributionState = 'INFERRED', stationId = :stationId, " +
            "attributionConfidence = NULL, attributionSourceTransmissionId = NULL, corrected = 1 " +
            "WHERE id = :transmissionId",
    )
    public suspend fun applyCorrectedAttribution(transmissionId: String, stationId: String)

    /** Inserts the audit row and applies the locked attribution in one transaction. */
    @Transaction
    public suspend fun recordCorrection(correction: CorrectionEntity) {
        insert(correction)
        applyCorrectedAttribution(correction.transmissionId, correction.newValue)
    }

    public companion object {
        public const val FIELD_STATION: String = "stationId"
        public const val FIELD_STATION_UNVERIFIED: String = "stationId_unverified"
    }
}
