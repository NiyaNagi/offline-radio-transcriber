package org.ort.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.ort.data.entity.TransmissionLabelEntity

/** FR-OBS-4 — read/write path for one transmission's operator-recorded label (schema v13). */
@Dao
public interface TransmissionLabelDao {

    /** Replaces any existing label for the same transmission — one label per transmission, never
     * a history (a label is corrected by re-labelling, unlike [org.ort.data.entity.CorrectionEntity],
     * which keeps history because a correction's *prior* value is itself evidence). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsert(entity: TransmissionLabelEntity)

    @Query("SELECT * FROM transmission_label WHERE transmissionId = :transmissionId")
    public suspend fun getByTransmissionId(transmissionId: String): TransmissionLabelEntity?

    @Query("SELECT * FROM transmission_label WHERE transmissionId IN (:transmissionIds)")
    public suspend fun getByTransmissionIds(transmissionIds: List<String>): List<TransmissionLabelEntity>

    /** `Recordings.dc.html`'s (RC01) "Labelled" chip/per-session badge: how many of this session's
     * overs are marked for training right now. */
    @Query(
        "SELECT COUNT(*) FROM transmission_label tl JOIN transmission t ON tl.transmissionId = t.id " +
            "WHERE t.sessionId = :sessionId AND tl.markedForTraining = 1",
    )
    public suspend fun countMarkedForTrainingBySession(sessionId: String): Int
}
