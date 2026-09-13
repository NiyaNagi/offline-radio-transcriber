package org.ort.pipeline.label

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ort.core.Clock
import org.ort.core.SystemClock
import org.ort.data.OrtDatabase
import org.ort.data.entity.LabelCertainty
import org.ort.data.entity.LabelOutcome
import org.ort.data.entity.TransmissionLabelEntity

/**
 * Every field of a label except its identity ([TransmissionLabelEntity.transmissionId]) and its
 * provenance ([TransmissionLabelEntity.labelledAtMillis]) — bundled so [TransmissionLabelRepository
 * .label] takes one parameter for "what was recorded" rather than eight (detekt's own
 * `LongParameterList`, functionThreshold 9), the same "bundle the request" shape
 * `org.ort.app.export.ExportRequest` already uses for the same reason.
 *
 * [callsignCertainty] must be set exactly when [truthCallsign] is non-blank — the protocol's own
 * rule ("A callsign that is even partially audible is labelled ... certainty" vs. "a negative
 * example ... gets `truthCallsign: (empty)`, distinct from `uncertain`/`partial`") — enforced in
 * [TransmissionLabelRepository.label], not here (a data class cannot `require()` across two of its
 * own fields at construction without duplicating the check at every call site).
 */
public data class TransmissionLabelFields(
    public val markedForTraining: Boolean,
    public val outcome: LabelOutcome? = null,
    public val doubled: Boolean = false,
    public val truthCallsign: String? = null,
    public val callsignCertainty: LabelCertainty? = null,
    public val tacticalCallsign: String? = null,
    public val note: String? = null,
    public val rating: String? = null,
)

/**
 * FR-OBS-4, Q16 (`docs/reference/labelling-protocol.md`), constitution VI (who/when provenance):
 * the one write/read path `Recording-Session.dc.html`'s (RC02) Label action, its "mark for
 * training" toggle and its per-over "training · good" badge all use.
 *
 * **Never an attribution.** Neither [label] nor [setMarkedForTraining] ever reads or writes
 * `TransmissionEntity.attributionState`/`.stationId`/`.attributionConfidence`, and neither calls
 * [org.ort.data.dao.TransmissionDao.updateAttribution] or
 * [org.ort.data.dao.CorrectionDao.recordCorrection] — see [TransmissionLabelEntity]'s own doc
 * comment for why. A label recorded here can never promote a transmission's attribution state,
 * `CONFIRMED` included (AGENTS.md's own non-negotiable rule).
 */
public object TransmissionLabelRepository {

    public suspend fun get(db: OrtDatabase, transmissionId: String): TransmissionLabelEntity? =
        withContext(Dispatchers.IO) { db.transmissionLabelDao().getByTransmissionId(transmissionId) }

    /**
     * Writes (or replaces) [transmissionId]'s full label in one call, from [fields] (see its own
     * doc comment for the protocol rule this enforces).
     *
     * The written [TransmissionLabelEntity.labelledAtMillis] (constitution VI provenance) is
     * always the real wall-clock moment of *this* write — a re-label overwrites the previous
     * timestamp, the same "current value" convention every other mutable column in this schema
     * follows; [org.ort.data.entity.CorrectionEntity] keeps a separate history table instead only
     * because a correction's *prior* value is itself evidence FR-SPK-7's propagation relies on,
     * which does not apply here.
     */
    public suspend fun label(
        db: OrtDatabase,
        transmissionId: String,
        fields: TransmissionLabelFields,
        clock: Clock = SystemClock,
    ): TransmissionLabelEntity = withContext(Dispatchers.IO) {
        require(!fields.truthCallsign.isNullOrBlank() == (fields.callsignCertainty != null)) {
            "callsignCertainty must be set exactly when truthCallsign is present -- " +
                "docs/reference/labelling-protocol.md's own certain/uncertain/partial rule"
        }
        val entity = TransmissionLabelEntity(
            transmissionId = transmissionId,
            markedForTraining = fields.markedForTraining,
            outcome = fields.outcome,
            doubled = fields.doubled,
            truthCallsign = fields.truthCallsign,
            callsignCertainty = fields.callsignCertainty,
            tacticalCallsign = fields.tacticalCallsign,
            note = fields.note,
            rating = fields.rating,
            labelledAtMillis = clock.wallMillis(),
        )
        db.transmissionLabelDao().upsert(entity)
        entity
    }

    /**
     * RC02 draws "mark for training" and "label" as two separate taps — this flips
     * [markedForTraining] alone, preserving every other already-recorded field exactly as it was
     * (or creating a bare, otherwise-unlabelled row if none existed yet).
     */
    public suspend fun setMarkedForTraining(
        db: OrtDatabase,
        transmissionId: String,
        markedForTraining: Boolean,
        clock: Clock = SystemClock,
    ): TransmissionLabelEntity = withContext(Dispatchers.IO) {
        val existing = db.transmissionLabelDao().getByTransmissionId(transmissionId)
        val entity = (
            existing ?: TransmissionLabelEntity(
                transmissionId = transmissionId,
                markedForTraining = markedForTraining,
                labelledAtMillis = clock.wallMillis(),
            )
            ).copy(markedForTraining = markedForTraining, labelledAtMillis = clock.wallMillis())
        db.transmissionLabelDao().upsert(entity)
        entity
    }
}
