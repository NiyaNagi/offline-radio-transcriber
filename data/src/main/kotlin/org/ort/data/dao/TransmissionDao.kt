package org.ort.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.ort.core.AttributionState
import org.ort.core.Tier
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

    /**
     * Register R-1055 (spec): a curated set of ids, across every session — never scoped to one
     * session's own rows the way [listBySession] is. The Log's own `LogFilterSelection
     * .transmissionIds` narrowing (a digest item's "The N overs", an Improve "review changes"
     * result, D11's affected-overs link) names specific overs by id; those overs may belong to an
     * earlier, already-ended session while a *different* session is live and capturing right now
     * (the normal overnight case: capture runs while the operator reviews an earlier night), so
     * scoping this read to whichever session happens to be live would silently find none of them.
     * An id in [ids] this table has no row for is simply absent from the result — never an error,
     * and never a placeholder row — so a caller can tell exactly which requested ids resolved by
     * comparing the result's own size against [ids]'s.
     */
    @Query("SELECT * FROM transmission WHERE id IN (:ids) ORDER BY samplePosition")
    public suspend fun listByIds(ids: List<String>): List<TransmissionEntity>

    /**
     * Register R-1055 (spec): the identical cross-session reasoning [listByIds] documents, for a
     * station's own overs (`LogFilterSelection.stationId`, ST02's "a station's own Overs · Log") —
     * a station heard across many nights must not read as empty merely because tonight's live
     * session has not heard it yet.
     */
    @Query("SELECT * FROM transmission WHERE stationId = :stationId ORDER BY samplePosition")
    public suspend fun listByStationId(stationId: String): List<TransmissionEntity>

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

    /**
     * Register R-204 follow-up (FR-REP-2, FR-REP-9): stamps the tier a just-finished Pass B/C run
     * for [id] actually processed it at — see
     * [org.ort.data.entity.TransmissionEntity.processedTier]'s own doc comment for who calls this
     * and when. Unconditional (no `corrected`-style guard): which tier last processed a record is
     * a fact about the pipeline run, not an attribution a human correction should ever block.
     */
    @Query("UPDATE transmission SET processedTier = :tier WHERE id = :id")
    public suspend fun setProcessedTier(id: String, tier: Tier)

    /**
     * Register R-1067 round 4 (coordinator review, constitution I/FR-REP-11): [setReprocessCandidate]
     * and [setProcessedTier] used to be two separate statements at [id]'s own reprocess-outcome call
     * site (`ReprocessRunner.recordOutcome`) — a real device trace (round-2 evidence, a kill between
     * an interrupted attempt and its resume) found exactly three transmissions left with
     * `isReprocessCandidate = 0` but `processedTier` still `NULL` and their attribution/transcript
     * never actually revised: the candidate flag had been cleared while the tier stamp — and, by
     * implication, the real Pass B write it is meant to certify — never landed. One `UPDATE`
     * statement is atomic by SQLite's own single-statement guarantee (no partial application is
     * possible, killed mid-write or not) where two separate calls are not. Unconditional, same
     * reasoning as [setProcessedTier] itself: which tier last cleared a record's candidacy is a fact
     * about the pipeline run.
     */
    @Query("UPDATE transmission SET isReprocessCandidate = 0, processedTier = :tier WHERE id = :id")
    public suspend fun markProcessedAtTier(id: String, tier: Tier)

    /**
     * Register R-1032 (constitution VI: "no number without ... execution provider"): the real
     * execution provider a Pass B/C run [id] actually ran under — see
     * [org.ort.data.entity.TransmissionEntity.executionProvider]'s own doc comment. Written by
     * [org.ort.pipeline.passb.DataPassBResultSink.record] for every outcome (accepted, rejected, or
     * failed — even a failed attempt genuinely ran, or genuinely did not, on this provider; "none"
     * is the honest value when no engine was available at all), the moment the provider is known —
     * before this existed, the fingerprint computed the real value and it was thrown away, and the
     * column's only writer was the literal `executionProvider = null` at segment-persist time,
     * before any pass had run. Unconditional, no `corrected`-style guard: which provider most
     * recently ran this record is a fact about the pipeline run, exactly the same reasoning
     * [setProcessedTier] already applies, never an attribution a human correction should block.
     */
    @Query("UPDATE transmission SET executionProvider = :provider WHERE id = :id")
    public suspend fun setExecutionProvider(id: String, provider: String)

    /**
     * Register R-1133 (FR-ASR-5, FR-ASR-6; AC-6; constitution I, VI): stamps
     * [org.ort.data.entity.TransmissionEntity.inertControls] — see that column's own doc comment
     * for the full reasoning and for why [inertControls] is a plain-text signature, not a hash.
     * Written by [org.ort.pipeline.passb.DataPassBResultSink.record] for `Accepted` and
     * `Rejected` outcomes only, the same "a genuine attempt" gate
     * [org.ort.data.entity.TransmissionEntity.processedTier]'s own doc comment states for
     * [setProcessedTier] — a `Failed` outcome never reached this point. Unconditional, no
     * `corrected`-style guard: which controls were live for a pass run is a fact about the
     * pipeline attempt, never an attribution a human correction should block.
     */
    @Query("UPDATE transmission SET inertControls = :inertControls WHERE id = :id")
    public suspend fun setInertControls(id: String, inertControls: String)

    /**
     * Every transmission not yet processed at any tier at or above the caller's target — i.e.
     * still a genuine reprocessing candidate. Never processed at all
     * ([org.ort.data.entity.TransmissionEntity.processedTier] `IS NULL`) always qualifies,
     * alongside any row whose last completed tier is one of [belowTiers]. [belowTiers] is
     * supplied by the caller (compare [org.ort.core.Tier.ordinal] against the target tier) rather
     * than computed here: `processedTier` is a `TEXT` column holding the tier's name, so SQLite
     * itself has no notion of tier *order* to compare against.
     */
    @Query("SELECT id FROM transmission WHERE processedTier IS NULL OR processedTier IN (:belowTiers)")
    public suspend fun idsBelowProcessedTier(belowTiers: List<Tier>): List<String>
}
