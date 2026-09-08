package org.ort.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import org.ort.data.entity.CorrectionEntity
import org.ort.data.entity.PriorAdjustmentEntity
import org.ort.data.entity.StationIdentityHistoryEntity
import org.ort.data.entity.VoiceprintBindingHistoryEntity
import org.ort.data.entity.VoiceprintBindingSource

/**
 * The write paths `:data` was missing for what `Station-Identity.dc.html` and
 * `Detail-Propagated.dc.html` require (register R-073, R-052; FR-SPK-10, FR-UI-6). A new DAO
 * file, not edits to [CatalogDao]'s existing basic CRUD for [org.ort.data.entity.StationEntity]
 * and [org.ort.data.entity.VoiceprintEntity] — the same reasoning [CorrectionDao]'s doc comment
 * gives for its own new-file precedent: each write here pairs a mutation with a history row in
 * one transaction, a different shape from [CatalogDao]'s plain insert/read, and keeping it
 * separate avoids two builders conflicting on one file.
 *
 * Every method here follows [TranscriptDao.supersede]'s precedent: nothing already on a row is
 * overwritten without first being copied to a `*_history` row, or carried forward as a
 * superseded, non-current version (`prior_adjustment`) — so a previous given name, note,
 * voiceprint binding or prior weight always stays reachable. Constitution III: "nothing is
 * deleted quietly."
 */
@Dao
public interface StationIdentityDao {

    // ---- R-073: station given name and note (`Station-Identity.dc.html`'s "Given by you") ----

    @Insert
    public suspend fun insertStationIdentityHistory(entity: StationIdentityHistoryEntity)

    @Query("UPDATE station SET userName = :name WHERE id = :stationId")
    public suspend fun setUserName(stationId: String, name: String?)

    @Query("UPDATE station SET notes = :note WHERE id = :stationId")
    public suspend fun setNotes(stationId: String, note: String?)

    @Query("SELECT * FROM station_identity_history WHERE stationId = :stationId ORDER BY changedAt")
    public suspend fun stationIdentityHistoryFor(stationId: String): List<StationIdentityHistoryEntity>

    /**
     * Renames a station (FR-SPK-25: user-supplied, never inferred, never contributed). Records
     * [history] before writing, so the previous name is reachable via [stationIdentityHistoryFor]
     * even though `station.userName` only ever holds the current one.
     */
    @Transaction
    public suspend fun renameStation(history: StationIdentityHistoryEntity) {
        require(history.field == FIELD_NAME) {
            "renameStation(...) only writes the $FIELD_NAME field; got ${history.field}"
        }
        insertStationIdentityHistory(history)
        setUserName(history.stationId, history.newValue)
    }

    /** Adds or edits a station's note (`Station-Identity.dc.html`'s "Add"/"Edit"), versioned the same way. */
    @Transaction
    public suspend fun updateStationNote(history: StationIdentityHistoryEntity) {
        require(history.field == FIELD_NOTE) {
            "updateStationNote(...) only writes the $FIELD_NOTE field; got ${history.field}"
        }
        insertStationIdentityHistory(history)
        setNotes(history.stationId, history.newValue)
    }

    // ---- R-052: voiceprint rebinding (`Detail-Propagated.dc.html`'s "1 voiceprint now belongs to ...") ----

    @Insert
    public suspend fun insertVoiceprintBindingHistory(entity: VoiceprintBindingHistoryEntity)

    @Query(
        "UPDATE voiceprint SET boundStationId = :stationId, bindingConfidence = :confidence, " +
            "bindingSource = :source, lastConfirmedAt = :confirmedAt WHERE id = :voiceprintId",
    )
    public suspend fun setBinding(
        voiceprintId: String,
        stationId: String?,
        confidence: Double?,
        source: VoiceprintBindingSource?,
        confirmedAt: Long?,
    )

    @Query("SELECT * FROM voiceprint_binding_history WHERE voiceprintId = :voiceprintId ORDER BY changedAt")
    public suspend fun voiceprintBindingHistoryFor(voiceprintId: String): List<VoiceprintBindingHistoryEntity>

    /**
     * Rebinds a voiceprint cluster to a station — the write "voiceprint reassigned" on the
     * propagated screen needs and, until now, had no path to. [history] must carry the binding
     * as it stood *before* this call; [bindVoiceprintToStation] copies it to
     * `voiceprint_binding_history` before overwriting `voiceprint.boundStationId`, so the earlier
     * binding is never lost — only superseded (constitution III).
     */
    @Transaction
    public suspend fun bindVoiceprintToStation(history: VoiceprintBindingHistoryEntity) {
        insertVoiceprintBindingHistory(history)
        setBinding(
            history.voiceprintId,
            history.newStationId,
            history.newBindingConfidence,
            history.newBindingSource,
            history.changedAt,
        )
    }

    // ---- R-052: prior weight updates (`Detail-Propagated.dc.html`'s "2 priors updated") ----

    @Query(
        "UPDATE prior_adjustment SET isCurrent = 0 WHERE stationId = :stationId AND name = :name AND isCurrent = 1",
    )
    public suspend fun clearCurrentPriorWeight(stationId: String, name: String)

    @Insert
    public suspend fun insertPriorAdjustment(entity: PriorAdjustmentEntity)

    @Query(
        "SELECT * FROM prior_adjustment WHERE stationId = :stationId AND name = :name AND isCurrent = 1 LIMIT 1",
    )
    public suspend fun currentPriorWeight(stationId: String, name: String): PriorAdjustmentEntity?

    @Query("SELECT * FROM prior_adjustment WHERE stationId = :stationId AND name = :name ORDER BY updatedAt")
    public suspend fun priorWeightHistoryFor(stationId: String, name: String): List<PriorAdjustmentEntity>

    /**
     * Installs [adjustment] as the new current weight for its `(stationId, name)` prior
     * (FR-LEX-9's named priors — e.g. the repeater-match and recency/conversation-context priors
     * a correction can move), superseding whatever was current — the same append-only shape as
     * [TranscriptDao.supersede]. The superseded row is never deleted, so a prior's weight history
     * stays auditable (constitution I: "every machine conclusion MUST be inspectable").
     */
    @Transaction
    public suspend fun updatePriorWeight(adjustment: PriorAdjustmentEntity) {
        require(adjustment.isCurrent) {
            "updatePriorWeight(...) always installs its argument as the new current weight"
        }
        clearCurrentPriorWeight(adjustment.stationId, adjustment.name)
        insertPriorAdjustment(adjustment)
    }

    // ---- R-073: splitting a voiceprint cluster (`Station-Identity.dc.html`'s "Split") ----

    @Insert
    public suspend fun insertCorrection(entity: CorrectionEntity)

    @Query("SELECT stationId FROM transmission WHERE id = :transmissionId")
    public suspend fun stationIdFor(transmissionId: String): String?

    @Query(
        "UPDATE transmission SET voiceprintId = :voiceprintId, stationId = NULL, " +
            "attributionState = 'UNKNOWN', corrected = 1 WHERE id = :transmissionId",
    )
    public suspend fun moveToVoiceprintAsUnidentified(transmissionId: String, voiceprintId: String)

    @Query("SELECT COUNT(*) FROM transmission WHERE voiceprintId = :voiceprintId")
    public suspend fun countByVoiceprint(voiceprintId: String): Int

    @Query("UPDATE voiceprint SET memberCount = :count WHERE id = :voiceprintId")
    public suspend fun setMemberCount(voiceprintId: String, count: Int)

    /**
     * Splits [members] away from [fromVoiceprintId] into [intoVoiceprintId] — the write
     * `Station-Identity.dc.html`'s "Split" affordance needs: "pick the overs that are not Dave
     * and they become a new unidentified voice. Every affected over is marked corrected and its
     * old attribution kept." [intoVoiceprintId] must already exist as a fresh, unbound
     * [org.ort.data.entity.VoiceprintEntity] — [CatalogDao.insert] creates it; this call only
     * moves membership. The old station attribution is kept reachable as a [CorrectionEntity] row
     * (the mechanism [CorrectionDao] already uses for every other attribution change) rather than
     * inventing a second history shape for the same fact.
     */
    @Transaction
    public suspend fun splitVoiceprint(
        fromVoiceprintId: String,
        intoVoiceprintId: String,
        members: List<VoiceprintSplitMember>,
        splitAt: Long,
    ) {
        for (member in members) {
            val previousStationId = stationIdFor(member.transmissionId)
            insertCorrection(
                CorrectionEntity(
                    id = member.correctionId,
                    transmissionId = member.transmissionId,
                    field = FIELD_VOICEPRINT_SPLIT,
                    previousValue = previousStationId,
                    // CorrectionEntity.newValue is non-null everywhere else it is used (it always
                    // names a callsign); a split has no new callsign to name, so this constant
                    // marks "became unidentified" rather than overloading null.
                    newValue = UNIDENTIFIED,
                    correctedAt = splitAt,
                ),
            )
            moveToVoiceprintAsUnidentified(member.transmissionId, intoVoiceprintId)
        }
        setMemberCount(fromVoiceprintId, countByVoiceprint(fromVoiceprintId))
        setMemberCount(intoVoiceprintId, countByVoiceprint(intoVoiceprintId))
    }

    public companion object {
        public const val FIELD_NAME: String = "userName"
        public const val FIELD_NOTE: String = "notes"
        public const val FIELD_VOICEPRINT_SPLIT: String = "voiceprintId_split"

        /** [CorrectionEntity.newValue] for a [splitVoiceprint] correction row — see its use there. */
        public const val UNIDENTIFIED: String = ""
    }
}

/** One transmission moving away from its old voiceprint cluster in [StationIdentityDao.splitVoiceprint]. */
public data class VoiceprintSplitMember(val transmissionId: String, val correctionId: String)
