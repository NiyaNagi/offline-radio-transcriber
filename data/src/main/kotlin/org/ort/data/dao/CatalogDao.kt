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
    public suspend fun getStationRaw(id: String): StationEntity?

    /**
     * Register R-1134 (FR-DIG-7, FR-DIG-8, constitution I, III): the real read path — [getStationRaw]
     * exists only so [recordStationObservation] can check for a station's existence without paying
     * for [overCountRowsFor] on every write. [StationEntity.overCountsByAttributionState] is
     * recomputed here, on every read, from the [org.ort.data.entity.TransmissionEntity] rows it is a
     * fact *about*, rather than trusted from the stored column — FR-DIG-8: "a fact that cannot be
     * recomputed from the log is not a fact." Before this, [touchStationObservation] incremented the
     * column in place and nothing ever decremented it: undoing a correction
     * ([org.ort.data.dao.CorrectionDao.restoreAttribution]) or splitting a voiceprint cluster
     * ([org.ort.data.dao.StationIdentityDao.splitVoiceprint]) both change a transmission's own
     * `attributionState`/`stationId` directly, and neither called an inverse of `increment` — on
     * purpose, since decrementing is a quiet deletion constitution III forbids. Deriving instead of
     * incrementing makes undo automatic: [overCountRowsFor] reads the transmission's *current*
     * `attributionState`/`stationId`, so a row moved back to its pre-correction state (or to
     * `UNKNOWN` by a split) simply stops counting toward this station on the very next read, with no
     * separate "undo the increment" step to remember or forget.
     *
     * [StationEntity.firstHeardAt]/`.lastHeardAt`/`.transmissionCount`/`.userName`/`.notes` are
     * deliberately left exactly as stored — only `overCountsByAttributionState` is overwritten here.
     * `firstHeardAt` is still set once, at birth, and never moved; `userName`/`.notes` are operator
     * content no machine observation may ever overwrite. Neither is a "fact" FR-DIG-7 names as
     * re-derivable, and changing either was out of this row's scope.
     */
    @Transaction
    public suspend fun getStation(id: String): StationEntity? {
        val raw = getStationRaw(id) ?: return null
        val counts = overCountRowsFor(id).associate { it.attributionState to it.count }
        return raw.copy(overCountsByAttributionState = OverCountsByAttributionState(counts).serialize())
    }

    /**
     * Register R-1134: reproduces [recordStationObservation]'s own "which callsign does this over
     * count toward" rule, from stored rows instead of an in-place increment — the union's two arms
     * mirror the two ways a transmission can be counted for [stationId], and never overlap:
     *
     * - a transmission whose own `stationId` already names this station — every `CONFIRMED`/
     *   `INFERRED` over, whether machine-resolved or operator-corrected
     *   ([org.ort.data.dao.CorrectionDao.applyCorrectedAttribution] always writes `stationId` and
     *   `attributionState` together, so the two never disagree about who this counts toward);
     * - a transmission with `stationId IS NULL` (every real `AMBIGUOUS` over today —
     *   `Attribution.stationId` is `null` for `AMBIGUOUS`, register R-1110) whose **top-ranked**
     *   [org.ort.data.entity.CallsignCandidateEntity] (`rank = 0`) names this station — the exact
     *   candidate [DataPassBResultSink.recordStationObservation] itself keys on.
     *
     * Both arms exclude `UNKNOWN` (D56: a station is observed only at "`AMBIGUOUS` or better").
     * Read `AttributionState` values are already the enum's stored name (Room's built-in enum
     * support), so the `!= 'UNKNOWN'` literal comparison matches [AttributionState.UNKNOWN.name].
     *
     * The second arm uses `EXISTS`, never a `JOIN`, against [CallsignCandidateEntity] on purpose:
     * [DataPassBResultSink.persistCandidates]'s own doc comment states a re-run for the same
     * transmission **adds** a new candidate set rather than overwriting the previous one
     * (constitution III — nothing deleted quietly), so more than one `rank = 0` row can exist for
     * one transmission. A `JOIN` would fan out one transmission row into one result row per
     * matching candidate row and over-count it; `EXISTS` counts the transmission at most once
     * regardless of how many historical candidate rows agree with [stationId].
     */
    @Query(
        "SELECT attributionState, COUNT(*) AS count FROM (" +
            "SELECT attributionState FROM transmission " +
            "WHERE stationId = :stationId AND attributionState != 'UNKNOWN' " +
            "UNION ALL " +
            "SELECT t.attributionState FROM transmission t " +
            "WHERE t.stationId IS NULL AND t.attributionState != 'UNKNOWN' AND EXISTS (" +
            "SELECT 1 FROM callsign_candidate cc " +
            "WHERE cc.transmissionId = t.id AND cc.`rank` = 0 AND cc.callsign = :stationId" +
            ")" +
            ") GROUP BY attributionState",
    )
    public suspend fun overCountRowsFor(stationId: String): List<StationOverCount>

    @Query("UPDATE station SET lastHeardAt = :observedAt, transmissionCount = transmissionCount + 1 WHERE id = :id")
    public suspend fun touchStationObservation(id: String, observedAt: Long)

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
     * `lastHeardAt` and `transmissionCount`, and a fresh [insert] leaves every other column at its
     * honest "nothing known yet" default (constitution I: never fabricated). `firstHeardAt` is set
     * once, on birth, and never moves — it is the record's actual birthday, not its most recent
     * sighting.
     *
     * Register R-1134: [state] is no longer used to compute a stored count here — see [getStation]'s
     * own doc comment for why `overCountsByAttributionState` is derived on read instead. It stays in
     * the signature, and is now enforced with [require] rather than merely trusted, because both
     * real call sites ([org.ort.pipeline.passb.DataPassBResultSink], [CorrectionDao.recordCorrection])
     * already gate on `state != UNKNOWN` before calling this — D56's own "AMBIGUOUS or better" bar —
     * so making that a structural check here (constitution VII: "a rule a person must remember is a
     * rule that will eventually be forgotten") costs nothing and catches a future caller that forgets
     * the gate, rather than silently deriving a station with a birth no over actually justified. The
     * inserted row's own `overCountsByAttributionState` is written as [OverCountsByAttributionState
     * .EMPTY]'s serialized form — an honest, constant placeholder, never read as truth: [getStation]
     * always overwrites it with the real, derived value before any caller sees it.
     */
    @Transaction
    public suspend fun recordStationObservation(callsign: String, state: AttributionState, observedAt: Long) {
        require(state != AttributionState.UNKNOWN) {
            "recordStationObservation(...) must never be called for UNKNOWN -- D56's 'AMBIGUOUS or " +
                "better' bar is the caller's own responsibility to gate on before reaching here"
        }
        val existing = getStationRaw(callsign)
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
                    overCountsByAttributionState = OverCountsByAttributionState.EMPTY.serialize(),
                ),
            )
        } else {
            touchStationObservation(id = callsign, observedAt = observedAt)
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

/** [CatalogDao.overCountRowsFor]'s projection — one row per distinct, non-`UNKNOWN` [AttributionState]
 * a station has genuinely been observed at, per register R-1134. A state with no matching row means
 * zero, the same "absent means zero" [OverCountsByAttributionState.countFor] already assumes. */
public data class StationOverCount(val attributionState: AttributionState, val count: Int)
