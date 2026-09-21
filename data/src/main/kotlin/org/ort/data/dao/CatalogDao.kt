package org.ort.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import org.ort.core.AttributionState
import org.ort.data.entity.AssetEntity
import org.ort.data.entity.CalibrationEntity
import org.ort.data.entity.CallsignCandidateEntity
import org.ort.data.entity.ContributionItemEntity
import org.ort.data.entity.CorrectionEntity
import org.ort.data.entity.LatticeSlotEntity
import org.ort.data.entity.LexiconVersionEntity
import org.ort.data.entity.OperatorLocationEntity
import org.ort.data.entity.OverCountsByAttributionState
import org.ort.data.entity.PhoneticLatticeEntity
import org.ort.data.entity.StationEntity
import org.ort.data.entity.StationSummaryEntity
import org.ort.data.entity.ThreadEntity
import org.ort.data.entity.VoiceprintEntity

/**
 * Basic CRUD for the functional spec §8 entities that no build-plan P5 acceptance criterion
 * exercises directly — grouped to match [org.ort.data.entity.CatalogEntities]. Later prompts
 * (lexicon ranking, identity, digest) add the query shapes their features actually need.
 */
@Dao
public interface CatalogDao {

    @Insert
    public suspend fun insert(entity: PhoneticLatticeEntity)

    @Query("SELECT * FROM phonetic_lattice WHERE transmissionId = :transmissionId")
    public suspend fun latticesFor(transmissionId: String): List<PhoneticLatticeEntity>

    @Insert
    public suspend fun insert(entity: CallsignCandidateEntity)

    @Query("SELECT * FROM callsign_candidate WHERE transmissionId = :transmissionId ORDER BY `rank`")
    public suspend fun candidatesFor(transmissionId: String): List<CallsignCandidateEntity>

    @Insert
    public suspend fun insert(entity: StationEntity)

    @Query("SELECT * FROM station WHERE id = :id")
    public suspend fun getStation(id: String): StationEntity?

    @Query(
        "UPDATE station SET lastHeardAt = :observedAt, transmissionCount = transmissionCount + 1, " +
            "overCountsByAttributionState = :overCounts WHERE id = :id",
    )
    public suspend fun touchStationObservation(id: String, observedAt: Long, overCounts: String)

    /**
     * Register R-1132, D56 (FR-SPK-1, FR-SPK-10, FR-LEX-25..27, FR-DIG-7, constitution I): the
     * write path that gives a station its first row, or updates the one it already has, the
     * moment a real over resolves a callsign worth remembering — "at `AMBIGUOUS` or better", per
     * D56's own wording. [callsign] is the station's own id (the convention every existing
     * [StationEntity] fixture already uses — `id == callsign`); this method never chooses the
     * callsign itself, only records that [callsign] was heard again at [state].
     *
     * Never overwrites [StationEntity.userName], `.notes`, or any of the other columns an
     * operator or a later enrichment pass owns — [touchStationObservation] only ever touches
     * `lastHeardAt`, `transmissionCount` and `overCountsByAttributionState`, and a fresh
     * [insert] leaves every one of those columns at its honest "nothing known yet" default
     * (constitution I: never fabricated). `firstHeardAt` is set once, on birth, and never moves —
     * it is the record's actual birthday, not its most recent sighting.
     */
    @Transaction
    public suspend fun recordStationObservation(callsign: String, state: AttributionState, observedAt: Long) {
        val existing = getStation(callsign)
        if (existing == null) {
            insert(
                StationEntity(
                    id = callsign,
                    callsign = callsign,
                    firstHeardAt = observedAt,
                    lastHeardAt = observedAt,
                    transmissionCount = 1,
                    isUserPinned = false,
                    notes = null,
                    userName = null,
                    frequenciesHeard = null,
                    activityByHourDow = null,
                    potaRefs = null,
                    spokenGrids = null,
                    ituRegionFromPrefix = null,
                    overCountsByAttributionState = OverCountsByAttributionState.EMPTY.increment(state).serialize(),
                ),
            )
        } else {
            touchStationObservation(
                id = callsign,
                observedAt = observedAt,
                overCounts = OverCountsByAttributionState.parse(existing.overCountsByAttributionState)
                    .increment(state)
                    .serialize(),
            )
        }
    }

    @Insert
    public suspend fun insert(entity: VoiceprintEntity)

    @Query("SELECT * FROM voiceprint WHERE boundStationId = :stationId")
    public suspend fun voiceprintsForStation(stationId: String): List<VoiceprintEntity>

    /**
     * D45: every voiceprint row, bound or not — the "every voiceprint" query
     * [org.ort.app.fieldreport.bundle.VoiceprintEmbeddingsProducer] previously had no way to make,
     * so a voiceprint never bound to a station ([VoiceprintEntity.boundStationId] `== null`) was
     * silently unreachable by walking [voiceprintsForStation] over every known station — that walk
     * can, by construction, never find a row with no station at all. Closes the gap
     * [VoiceprintEmbeddingsProducer]'s own doc comment named as real follow-up work (register
     * R-1010).
     */
    @Query("SELECT * FROM voiceprint")
    public suspend fun allVoiceprints(): List<VoiceprintEntity>

    @Insert
    public suspend fun insert(entity: ThreadEntity)

    @Query("SELECT * FROM thread WHERE id = :id")
    public suspend fun getThread(id: String): ThreadEntity?

    @Insert
    public suspend fun insert(entity: ContributionItemEntity)

    @Query("SELECT * FROM contribution_item WHERE state = 'PENDING'")
    public suspend fun pendingContributions(): List<ContributionItemEntity>

    @Insert
    public suspend fun insert(entity: LexiconVersionEntity)

    @Query("SELECT * FROM lexicon_version WHERE assetId = :assetId ORDER BY importedAt DESC")
    public suspend fun versionsFor(assetId: String): List<LexiconVersionEntity>

    @Insert
    public suspend fun insert(entity: CorrectionEntity)

    @Query("SELECT * FROM correction WHERE transmissionId = :transmissionId ORDER BY correctedAt")
    public suspend fun correctionsFor(transmissionId: String): List<CorrectionEntity>

    @Insert
    public suspend fun insert(entity: CalibrationEntity)

    @Query("SELECT * FROM calibration WHERE modelId = :modelId AND tier = :tier ORDER BY fittedAt DESC LIMIT 1")
    public suspend fun latestCalibration(modelId: String, tier: String): CalibrationEntity?

    @Insert
    public suspend fun insert(entity: AssetEntity)

    @Query("SELECT * FROM asset WHERE id = :id")
    public suspend fun getAsset(id: String): AssetEntity?

    @Insert
    public suspend fun insert(entity: OperatorLocationEntity)

    @Query("SELECT * FROM operator_location WHERE profileId = :profileId")
    public suspend fun getOperatorLocation(profileId: String): OperatorLocationEntity?

    @Insert
    public suspend fun insert(entity: StationSummaryEntity)

    @Query("SELECT * FROM station_summary WHERE stationId = :stationId ORDER BY windowStart DESC")
    public suspend fun summariesFor(stationId: String): List<StationSummaryEntity>

    @Insert
    public suspend fun insert(entity: LatticeSlotEntity)

    /**
     * Register R-320 (FR-UI-8, `Detail-Why.dc.html`'s per-slot list, "D05"): every slot for
     * every candidate of [transmissionId], candidates in the same rank order
     * [candidatesFor] already returns, slots within a candidate in lattice order.
     */
    @Query(
        "SELECT ls.* FROM lattice_slot ls " +
            "JOIN callsign_candidate cc ON cc.id = ls.candidateId " +
            "WHERE ls.transmissionId = :transmissionId " +
            "ORDER BY cc.`rank`, ls.`index`",
    )
    public suspend fun slotDetailsFor(transmissionId: String): List<LatticeSlotEntity>

    /**
     * Register R-182 (FR-UI-4, D01/D03's transcript-highlight span): the *winning*
     * ([CallsignCandidateEntity.selected]) candidate's overall `[start, end)` character range
     * into the Pass B transcript — the union of every slot's own `charStart`/`charEnd` that has
     * one (a text-anchored lattice's slots — see [LatticeSlotEntity]'s own doc comment; an
     * acoustic lattice's slots carry no char span at all, so both come back `null`, honestly,
     * never a fabricated `0`). `MIN`/`MAX` over zero rows (no winning candidate, or one with no
     * char-anchored slots) already return SQL `NULL` for both, so this never needs a nullable
     * return type for "no such transmission" versus "no span for this one" — both look the same
     * to a caller, which is the correct thing for a highlight to fail open on.
     */
    @Query(
        "SELECT MIN(ls.charStart) AS spanStart, MAX(ls.charEnd) AS spanEnd FROM lattice_slot ls " +
            "JOIN callsign_candidate cc ON cc.id = ls.candidateId " +
            "WHERE ls.transmissionId = :transmissionId AND cc.selected = 1",
    )
    public suspend fun winningCandidateCharSpan(transmissionId: String): WinningCandidateCharSpan

    /**
     * Register R-471 (D03's `Detail-Ambiguous.dc.html`, FR-UI-4): the same real span as
     * [winningCandidateCharSpan], for the top-ranked ([CallsignCandidateEntity.rank] `= 0`)
     * candidate instead of the *selected* one — an AMBIGUOUS over has no [CallsignCandidateEntity.selected]
     * row at all (nothing is chosen yet), so [winningCandidateCharSpan] always comes back
     * `null`/`null` for one, leaving D03's transcript with no highlight even when Pass B's own
     * lattice was text-anchored. A resolved over's rank-0 candidate is its selected one by
     * construction, so this is a strict widening, not a behaviour change, for CONFIRMED/INFERRED.
     */
    @Query(
        "SELECT MIN(ls.charStart) AS spanStart, MAX(ls.charEnd) AS spanEnd FROM lattice_slot ls " +
            "JOIN callsign_candidate cc ON cc.id = ls.candidateId " +
            "WHERE ls.transmissionId = :transmissionId AND cc.`rank` = 0",
    )
    public suspend fun topRankedCandidateCharSpan(transmissionId: String): WinningCandidateCharSpan
}

/** [CatalogDao.winningCandidateCharSpan]'s projection — a half-open `[spanStart, spanEnd)` range, or both `null`. */
public data class WinningCandidateCharSpan(val spanStart: Int?, val spanEnd: Int?)
