package org.ort.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import org.ort.core.AttributionState
import org.ort.data.entity.CorrectionEntity
import org.ort.data.entity.OverCountsByAttributionState
import org.ort.data.entity.StationEntity

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

    /**
     * The transmission row's real attribution fields, read as they stand *before* a correction
     * overwrites them (register R-321) — [recordCorrection] stamps this onto the audit row it
     * inserts, so a later `Undo all` can restore exactly this, not a guess reconstructed from
     * [CorrectionEntity.previousValue] alone (which only ever carried the previous *callsign*).
     */
    @Query(
        "SELECT attributionState, attributionConfidence, attributionSourceTransmissionId, corrected " +
            "FROM transmission WHERE id = :transmissionId",
    )
    public suspend fun attributionSnapshot(transmissionId: String): TransmissionAttributionSnapshot?

    /**
     * Inserts the audit row and applies the locked attribution in one transaction.
     *
     * Register R-321: [correction] arrives with its `previousAttribution*`/[previousCorrected]
     * fields unset (the caller only knows the previous *callsign* —
     * [org.ort.data.entity.CorrectionEntity.previousValue], already set); this DAO — not the
     * caller — reads [attributionSnapshot] for [correction]'s transmission *before* applying the
     * new attribution and stamps the real prior state onto the row it inserts, so it is
     * unconditionally trustworthy: a caller cannot forget to pass it, and it cannot go stale
     * between being computed and being written, since both happen in the same transaction here.
     *
     * Register R-1132, D56: "an operator correction always creates or rebinds the record" — the
     * other half of D56, alongside Pass B closure ([CatalogDao.recordStationObservation]). A
     * correction always produces [AttributionState.INFERRED]
     * ([applyCorrectedAttribution]/[org.ort.core.Attribution.withCorrection]'s own contract),
     * which already clears D56's "`AMBIGUOUS` or better" bar, so [recordStationObservation] is
     * called unconditionally here, for both a verified pick and unverified free text
     * ([FIELD_STATION_UNVERIFIED]) — [correction].[CorrectionEntity.newValue] is already what
     * this same call writes onto `transmission.stationId` via [applyCorrectedAttribution], so the
     * two tables agree about what the operator just said, whether or not it was verified against
     * the lexicon (Q8's "marked unverified" is carried on the correction row's own `field`, not
     * by withholding the station record).
     */
    @Transaction
    public suspend fun recordCorrection(correction: CorrectionEntity) {
        val before = attributionSnapshot(correction.transmissionId)
        insert(
            correction.copy(
                previousAttributionState = before?.attributionState,
                previousAttributionConfidence = before?.attributionConfidence,
                previousAttributionSourceTransmissionId = before?.attributionSourceTransmissionId,
                previousCorrected = before?.corrected,
            ),
        )
        applyCorrectedAttribution(correction.transmissionId, correction.newValue)
        if (correction.newValue.isNotBlank()) {
            recordStationObservation(correction.newValue, AttributionState.INFERRED, correction.correctedAt)
        }
    }

    // ---- R-1132, D56: the same station-birth rule CatalogDao.recordStationObservation applies
    // at Pass B closure, reached here too because Room's per-Dao interfaces do not let one Dao
    // call into another's abstract methods -- the "station" table already has more than one
    // writer (StationIdentityDao's UPDATE queries alongside CatalogDao's own insert/read), and
    // this follows that established precedent rather than restructuring the Dao boundary. The
    // *rule* -- how counts merge -- has exactly one implementation, OverCountsByAttributionState;
    // only this thin get-then-insert-or-update shell is repeated, the same way `correctionsFor`
    // above is already duplicated verbatim in CatalogDao.
    //
    // Register R-1134: this shell no longer computes or stores a count at all -- see
    // CatalogDao.getStation's own doc comment for why overCountsByAttributionState is derived on
    // read, from the transmission rows themselves, rather than incremented here. A correction's
    // own transmission-row write (applyCorrectedAttribution, in the same recordCorrection
    // transaction) is what CatalogDao.overCountRowsFor actually reads back on the next getStation
    // call; this shell's only remaining job is to make sure a station row exists to attach a
    // firstHeardAt/lastHeardAt to.

    @Query("SELECT * FROM station WHERE id = :id")
    public suspend fun getStationForCorrection(id: String): StationEntity?

    @Insert
    public suspend fun insertStationForCorrection(entity: StationEntity)

    @Query("UPDATE station SET lastHeardAt = :observedAt, transmissionCount = transmissionCount + 1 WHERE id = :id")
    public suspend fun touchStationObservationForCorrection(id: String, observedAt: Long)

    @Transaction
    public suspend fun recordStationObservation(callsign: String, state: AttributionState, observedAt: Long) {
        require(state != AttributionState.UNKNOWN) {
            "recordStationObservation(...) must never be called for UNKNOWN -- D56's 'AMBIGUOUS or " +
                "better' bar is the caller's own responsibility to gate on before reaching here"
        }
        val existing = getStationForCorrection(callsign)
        if (existing == null) {
            insertStationForCorrection(
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
            touchStationObservationForCorrection(id = callsign, observedAt = observedAt)
        }
    }

    /**
     * Register R-321: restores [transmissionId]'s attribution to exactly [state]/[stationId]/
     * [confidence]/[sourceTransmissionId]/[corrected] — the shape [attributionSnapshot] captured
     * before some earlier correction overwrote it, read back from that correction's own
     * `previousAttribution*`/`previousCorrected` columns by the caller (`Undo all`,
     * `app/.../ui/data/CorrectionPolling.kt`). Unconditional, like [applyCorrectedAttribution]:
     * this *is* the deliberate human "undo" action the `CORRECTED` lock exists to survive, not a
     * machine re-propagation the lock is supposed to block — the same reasoning that makes
     * [applyCorrectedAttribution] itself unconditional applies here.
     */
    @Query(
        "UPDATE transmission SET attributionState = :state, stationId = :stationId, " +
            "attributionConfidence = :confidence, attributionSourceTransmissionId = :sourceTransmissionId, " +
            "corrected = :corrected WHERE id = :transmissionId",
    )
    public suspend fun restoreAttribution(
        transmissionId: String,
        state: AttributionState,
        stationId: String?,
        confidence: Double?,
        sourceTransmissionId: String?,
        corrected: Boolean,
    )

    /**
     * P28 follow-up (FR-ANL-2): the one read `:app`'s tier-1 "correction rate by field" aggregate
     * (`org.ort.app.analytics.QualityStatsAggregator`) needs — every correction's own [field]
     * name, and nothing else. Deliberately narrower than `SELECT *`: even a caller that misused
     * this could not leak a [CorrectionEntity.previousValue]/[CorrectionEntity.newValue] (both
     * callsigns, forbidden in a tier-1 payload) through it, because they were never read.
     */
    @Query("SELECT field FROM correction")
    public suspend fun listAllFields(): List<String>

    public companion object {
        public const val FIELD_STATION: String = "stationId"
        public const val FIELD_STATION_UNVERIFIED: String = "stationId_unverified"
    }
}

/** [CorrectionDao.attributionSnapshot]'s projection — the four transmission columns register R-321 preserves. */
public data class TransmissionAttributionSnapshot(
    val attributionState: AttributionState,
    val attributionConfidence: Double?,
    val attributionSourceTransmissionId: String?,
    val corrected: Boolean,
)
