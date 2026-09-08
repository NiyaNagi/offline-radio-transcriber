package org.ort.app.ui.data

import android.content.Context
import org.ort.core.Ulid
import org.ort.data.OrtDatabase
import org.ort.data.entity.PriorAdjustmentEntity
import org.ort.data.entity.TranscriptEntity
import org.ort.data.entity.TranscriptPass
import org.ort.data.entity.TransmissionEntity
import org.ort.data.entity.VoiceprintBindingHistoryEntity
import org.ort.data.entity.VoiceprintBindingSource
import org.ort.data.entity.VoiceprintEntity
import java.util.Locale

/**
 * ui-conformance WP6 (R-052, R-058), `Detail-Correct-A/B/C.dc.html`, `Detail-Propagated.dc.html`,
 * `Flow-Correct.dc.html`. This is the correction-propagation read/write path: **its own** reads
 * and writes through [OrtDatabase] directly, never through [ReaderPolling] — `ReaderPolling.kt` is
 * WP4's file (ui-conformance-plan §D's builder rule), and this package's row explicitly says so.
 * [ReaderTransmissionViewStateMapper.timeLabelFor] is called (a pure formatting function, not a
 * read) purely to keep the affected-row time label identical to every other screen's, rather than
 * a second, drifting format.
 *
 * **Propagation scope** (`Flow-Correct.dc.html`: "this over only, or every over matched to the
 * same voice"): every transmission sharing the corrected transmission's `voiceprintId` — or its
 * `stationId` when there is no voiceprint — gets its own [org.ort.data.entity.CorrectionEntity].
 * Nothing is ever deleted; `undoAll` is itself a further correction, kept exactly like every
 * other one (constitution III).
 *
 * **R-052 complete.** `:data`'s [org.ort.data.dao.StationIdentityDao] (schema v3) closes the gap
 * this file's earlier revision reported: [applyCorrection] now really rebinds the voiceprint
 * ([org.ort.data.dao.StationIdentityDao.bindVoiceprintToStation], old binding kept via
 * [VoiceprintBindingHistoryEntity]) and really versions the two named priors
 * `Detail-Propagated.dc.html` counts ([org.ort.data.dao.StationIdentityDao.updatePriorWeight], the
 * superseded weight kept via [PriorAdjustmentEntity.isCurrent]) — but only for a **verified**
 * correction ([CorrectionTier.PICK_CANDIDATE]/[CorrectionTier.SEARCH_LEXICON]) applied with
 * [CorrectionScope.EVERY_OVER_SAME_VOICE]: `Detail-Correct-C.dc.html`'s own words for
 * [CorrectionTier.FREE_TEXT] are "cannot feed the priors or the voice match", and a `this over
 * only` scope must not rebind a voiceprint shared by overs the operator deliberately left alone.
 * [PropagationOutcome.voiceprintReassigned]/`.priorsUpdatedCount` are now real, derived from
 * [PropagationOutcome.voiceprintRebind]/`.priorAdjustments` rather than fixed at `false`/`0`.
 */
public enum class CorrectionScope { THIS_OVER_ONLY, EVERY_OVER_SAME_VOICE }

/** One row of `Detail-Propagated.dc.html`'s "the N overs, as they read now" list. */
public data class AffectedOverViewState(
    val transmissionId: String,
    val timeLabel: String,
    val transcriptExcerpt: String,
    val oldCallsign: String?,
    val newCallsign: String,
)

/**
 * R-052: what [CorrectionPolling.applyCorrection] recorded for the voiceprint, and everything
 * [CorrectionPolling.undoAll] needs to reverse it as a further, kept binding (never a delete).
 */
public data class VoiceprintRebindOutcome(
    val voiceprintId: String,
    val previousStationId: String?,
    val previousBindingConfidence: Double?,
    val previousBindingSource: VoiceprintBindingSource?,
    val newStationId: String,
)

/** R-052: one named prior's real before/after weight — [org.ort.data.dao.StationIdentityDao.updatePriorWeight]
 * kept the "before" row reachable; this is what [CorrectionPolling.undoAll] restores it from. */
public data class PriorAdjustmentOutcome(
    val stationId: String,
    val name: String,
    val previousWeight: Double,
    val newWeight: Double,
)

/**
 * `Detail-Propagated.dc.html`'s "what changed" tile, plus what [CorrectionPolling.undoAll] needs.
 * [voiceprintReassigned]/[priorsUpdatedCount] are derived from [voiceprintRebind]/[priorAdjustments]
 * — real counts of what was actually written, never a fixed placeholder (constitution I).
 */
public data class PropagationOutcome(
    val newCallsign: String,
    val previousCallsign: String?,
    val overCount: Int,
    val voiceprintRebind: VoiceprintRebindOutcome?,
    val priorAdjustments: List<PriorAdjustmentOutcome>,
    val deletedCount: Int,
    val affected: List<AffectedOverViewState>,
) {
    public val voiceprintReassigned: Boolean get() = voiceprintRebind != null
    public val priorsUpdatedCount: Int get() = priorAdjustments.size
}

/** R-055, `Detail-Revisions.dc.html`: one version card. [who] is pass + model, plus "corrected" when
 * this transmission has at least one [org.ort.data.entity.CorrectionEntity] recorded against it. */
public data class TranscriptVersionViewState(
    val id: String,
    val text: String,
    val timeLabel: String,
    val who: String,
    val isCurrent: Boolean,
)

public object CorrectionPolling {

    /**
     * R-052: the two named priors a verified, propagated correction reinforces —
     * `Detail-Propagated.dc.html`'s own example ("priors updated — on this repeater and recent
     * corrections"). [PRIOR_ADJUSTMENT_INCREMENT] is a disclosed policy constant (how strongly one
     * human correction moves a station's stored weight), not a measured figure — there is no
     * corpus-fitted value for it anywhere in spec or design; `+0.15` mirrors
     * `StationIdentityDaoTest`'s own worked example (`0.2` → `0.35`) so the two independently-chosen
     * numbers in this codebase agree rather than silently drifting apart. A prior with no earlier
     * row starts from `0.0` — FR-LEX-31's own invariant elsewhere in this codebase: cold start
     * contributes exactly zero, not an absent field, for an additive log-odds-style weight.
     */
    private const val PRIOR_ON_THIS_REPEATER = "on_this_repeater"
    private const val PRIOR_RECENT_CORRECTIONS = "recent_corrections"
    private const val PRIOR_ADJUSTMENT_INCREMENT = 0.15

    /** `Flow-Correct.dc.html`'s "the count is shown before applying" — computed without writing. */
    public suspend fun affectedOverCount(context: Context, transmissionId: String, scope: CorrectionScope): Int =
        affectedTransmissions(OrtDatabase.create(context.applicationContext), transmissionId, scope).size

    /**
     * Applies [request] to every transmission [scope] selects, each as its own
     * [org.ort.data.dao.CorrectionDao.recordCorrection] (its own audit row, its own locked
     * attribution — never one write standing in for many). When [scope] is
     * [CorrectionScope.EVERY_OVER_SAME_VOICE], the target carries a `voiceprintId`, and [request]
     * is verified (not [CorrectionTier.FREE_TEXT]), this also rebinds that voiceprint to the
     * corrected station and versions the two named priors — see this file's class doc for why
     * those three conditions gate it.
     */
    public suspend fun applyCorrection(
        context: Context,
        request: CorrectionRequest,
        scope: CorrectionScope,
    ): PropagationOutcome {
        val db = OrtDatabase.create(context.applicationContext)
        val target = db.transmissionDao().getById(request.transmissionId)
        val targets = affectedTransmissions(db, request.transmissionId, scope)
        val affected = targets.map { row ->
            val entity = request.copy(
                transmissionId = row.id,
                previousStationId = row.stationId,
            ).toEntity()
            db.correctionDao().recordCorrection(entity)
            affectedRow(db, row, oldCallsign = row.stationId, newCallsign = request.newStationId)
        }

        val voiceprintId = target?.voiceprintId
        val verified = request.tier != CorrectionTier.FREE_TEXT
        val rebind = if (scope == CorrectionScope.EVERY_OVER_SAME_VOICE && verified && voiceprintId != null) {
            rebindVoiceprint(db, voiceprintId, targets, request.newStationId, request.correctedAtMillis)
        } else {
            null
        }
        val priorAdjustments = if (rebind != null) {
            listOf(
                bumpPriorWeight(db, request.newStationId, PRIOR_ON_THIS_REPEATER, request.correctedAtMillis, request),
                bumpPriorWeight(db, request.newStationId, PRIOR_RECENT_CORRECTIONS, request.correctedAtMillis, request),
            )
        } else {
            emptyList()
        }

        return PropagationOutcome(
            newCallsign = request.newStationId,
            previousCallsign = request.previousStationId,
            overCount = affected.size,
            voiceprintRebind = rebind,
            priorAdjustments = priorAdjustments,
            deletedCount = 0,
            affected = affected,
        )
    }

    /**
     * `Detail-Propagated.dc.html`'s `Undo all` — reverts every affected transmission back to its
     * recorded [AffectedOverViewState.oldCallsign] as a **new** correction (never a delete), then
     * reverses the voiceprint rebind and the prior adjustments the same way: a further, kept write
     * restoring the previous value, never a delete. A row whose [AffectedOverViewState.oldCallsign]
     * was `null` (it had no station before the correction) is left alone:
     * [org.ort.data.dao.CorrectionDao.applyCorrectedAttribution]'s fixed SQL requires a non-null
     * station, so a true revert-to-unattributed is not representable through the write path this
     * package can call without editing `:data` — named here rather than silently skipped.
     */
    public suspend fun undoAll(context: Context, outcome: PropagationOutcome, atMillis: Long) {
        val db = OrtDatabase.create(context.applicationContext)
        outcome.affected.forEach { row ->
            val old = row.oldCallsign ?: return@forEach
            val entity = CorrectionRequest(
                transmissionId = row.transmissionId,
                previousStationId = row.newCallsign,
                newStationId = old,
                tier = CorrectionTier.PICK_CANDIDATE,
                correctedAtMillis = atMillis,
            ).toEntity()
            db.correctionDao().recordCorrection(entity)
        }

        outcome.voiceprintRebind?.let { rebind ->
            db.stationIdentityDao().bindVoiceprintToStation(
                VoiceprintBindingHistoryEntity(
                    id = Ulid.generate().toString(),
                    voiceprintId = rebind.voiceprintId,
                    previousStationId = rebind.newStationId,
                    previousBindingConfidence = 1.0,
                    previousBindingSource = VoiceprintBindingSource.MANUAL,
                    newStationId = rebind.previousStationId,
                    newBindingConfidence = rebind.previousBindingConfidence,
                    newBindingSource = rebind.previousBindingSource,
                    changedAt = atMillis,
                ),
            )
        }
        outcome.priorAdjustments.forEach { adjustment ->
            db.stationIdentityDao().updatePriorWeight(
                PriorAdjustmentEntity(
                    id = Ulid.generate().toString(),
                    stationId = adjustment.stationId,
                    name = adjustment.name,
                    weight = adjustment.previousWeight,
                    reason = "undo",
                    isCurrent = true,
                    updatedAt = atMillis,
                ),
            )
        }
    }

    /**
     * R-058: `Confirm` records that a human agreed — an audit row alone, via
     * [org.ort.data.dao.CorrectionDao.insert], never [org.ort.data.dao.CorrectionDao.recordCorrection]
     * (which would force `attributionState = INFERRED` and set the `corrected` lock — see
     * [CorrectionTier.CONFIRM]'s doc comment for why that would be wrong here).
     */
    public suspend fun confirm(context: Context, transmissionId: String, stationId: String, atMillis: Long) {
        val db = OrtDatabase.create(context.applicationContext)
        val entity = CorrectionRequest(
            transmissionId = transmissionId,
            previousStationId = stationId,
            newStationId = stationId,
            tier = CorrectionTier.CONFIRM,
            correctedAtMillis = atMillis,
        ).toEntity()
        db.correctionDao().insert(entity)
    }

    /**
     * R-055, `Detail-Revisions.dc.html`: every version, current first then newest-superseded-first
     * — nothing hidden, nothing deleted (constitution III). [TranscriptDao.getAllVersions] already
     * returns every row; this only orders and labels them.
     */
    public suspend fun revisions(context: Context, transmissionId: String): List<TranscriptVersionViewState> {
        val db = OrtDatabase.create(context.applicationContext)
        val corrected = db.correctionDao().correctionsFor(transmissionId).isNotEmpty()
        val versions = db.transcriptDao().getAllVersions(transmissionId)
        val (current, superseded) = versions.partition { it.isCurrent }
        val ordered = current + superseded.sortedByDescending { it.createdAt }
        return ordered.map { version ->
            TranscriptVersionViewState(
                id = version.id,
                text = version.text,
                timeLabel = timeLabel(version.createdAt),
                who = who(version, corrected && version.isCurrent),
                isCurrent = version.isCurrent,
            )
        }
    }

    /**
     * R-055: `Restore` — a **new** current transcript row carrying [versionId]'s text, per this
     * package's row ("a new current transcript row; nothing deleted"). Uses
     * [org.ort.data.dao.TranscriptDao.supersede], the same append-only write every pass already
     * uses — restoring is not a special case at the schema level.
     */
    public suspend fun restore(context: Context, transmissionId: String, versionId: String, atMillis: Long) {
        val db = OrtDatabase.create(context.applicationContext)
        val source = db.transcriptDao().getAllVersions(transmissionId).firstOrNull { it.id == versionId } ?: return
        db.transcriptDao().supersede(
            TranscriptEntity(
                id = Ulid.generate().toString(),
                transmissionId = transmissionId,
                pass = TranscriptPass.REPROCESS,
                text = source.text,
                modelId = source.modelId,
                modelVersion = source.modelVersion,
                quantization = source.quantization,
                decodeParams = source.decodeParams,
                noSpeechProb = source.noSpeechProb,
                confidence = source.confidence,
                isCurrent = true,
                createdAt = atMillis,
            ),
        )
    }

    /**
     * R-052: finds the real [VoiceprintEntity] [voiceprintId] names — there is no `getById` for
     * voiceprint in any `:data` DAO this package can call, so this searches
     * [org.ort.data.dao.CatalogDao.voiceprintsForStation] over every distinct station [targets]
     * (the transmissions this propagation already touched) currently carries, which is real data,
     * not a guess. Genuinely unbound (never matched any of those stations) is a real, honest
     * outcome too — `previousStationId = null` — not a failure.
     */
    private suspend fun rebindVoiceprint(
        db: OrtDatabase,
        voiceprintId: String,
        targets: List<TransmissionEntity>,
        newStationId: String,
        atMillis: Long,
    ): VoiceprintRebindOutcome {
        val previous = findVoiceprint(db, voiceprintId, targets.mapNotNull { it.stationId }.distinct())
        db.stationIdentityDao().bindVoiceprintToStation(
            VoiceprintBindingHistoryEntity(
                id = Ulid.generate().toString(),
                voiceprintId = voiceprintId,
                previousStationId = previous?.boundStationId,
                previousBindingConfidence = previous?.bindingConfidence,
                previousBindingSource = previous?.bindingSource,
                newStationId = newStationId,
                newBindingConfidence = 1.0,
                newBindingSource = VoiceprintBindingSource.MANUAL,
                changedAt = atMillis,
            ),
        )
        return VoiceprintRebindOutcome(
            voiceprintId = voiceprintId,
            previousStationId = previous?.boundStationId,
            previousBindingConfidence = previous?.bindingConfidence,
            previousBindingSource = previous?.bindingSource,
            newStationId = newStationId,
        )
    }

    private suspend fun findVoiceprint(
        db: OrtDatabase,
        voiceprintId: String,
        candidateStationIds: List<String>,
    ): VoiceprintEntity? {
        for (stationId in candidateStationIds) {
            val match = db.catalogDao().voiceprintsForStation(stationId).firstOrNull { it.id == voiceprintId }
            if (match != null) return match
        }
        return null
    }

    private suspend fun bumpPriorWeight(
        db: OrtDatabase,
        stationId: String,
        name: String,
        atMillis: Long,
        request: CorrectionRequest,
    ): PriorAdjustmentOutcome {
        val current = db.stationIdentityDao().currentPriorWeight(stationId, name)
        val previousWeight = current?.weight ?: 0.0
        val newWeight = previousWeight + PRIOR_ADJUSTMENT_INCREMENT
        db.stationIdentityDao().updatePriorWeight(
            PriorAdjustmentEntity(
                id = Ulid.generate().toString(),
                stationId = stationId,
                name = name,
                weight = newWeight,
                reason = "corrected to ${request.newStationId} (${request.tier})",
                isCurrent = true,
                updatedAt = atMillis,
            ),
        )
        return PriorAdjustmentOutcome(
            stationId = stationId,
            name = name,
            previousWeight = previousWeight,
            newWeight = newWeight,
        )
    }

    private fun who(version: TranscriptEntity, correctedNow: Boolean): String {
        val passLabel = when (version.pass) {
            TranscriptPass.A -> "Pass A · live partial"
            TranscriptPass.B -> "Pass B · machine attribution"
            TranscriptPass.REPROCESS -> "Reprocessed"
        }
        val model = "${version.modelId} ${version.modelVersion}".trim()
        return if (correctedNow) "$passLabel · $model · corrected" else "$passLabel · $model"
    }

    private fun timeLabel(utcMillis: Long): String {
        val totalSeconds = utcMillis / 1000
        val hours = (totalSeconds / 3600) % 24
        val minutes = (totalSeconds / 60) % 60
        val seconds = totalSeconds % 60
        return "%02d:%02d:%02d".format(Locale.ROOT, hours, minutes, seconds)
    }

    private suspend fun affectedTransmissions(
        db: OrtDatabase,
        transmissionId: String,
        scope: CorrectionScope,
    ): List<TransmissionEntity> {
        val target = db.transmissionDao().getById(transmissionId) ?: return emptyList()
        if (scope == CorrectionScope.THIS_OVER_ONLY) return listOf(target)
        val all = db.transmissionDao().listAll()
        return when {
            target.voiceprintId != null -> all.filter { it.voiceprintId == target.voiceprintId }
            target.stationId != null -> all.filter { it.stationId == target.stationId }
            else -> listOf(target)
        }
    }

    private suspend fun affectedRow(
        db: OrtDatabase,
        target: TransmissionEntity,
        oldCallsign: String?,
        newCallsign: String,
    ): AffectedOverViewState = AffectedOverViewState(
        transmissionId = target.id,
        timeLabel = ReaderTransmissionViewStateMapper.timeLabelFor(
            TransmissionDetail(
                id = target.id,
                startedAtUtcMillis = target.startedAtUtc,
                frequencyHz = target.frequencyHz,
                durationMs = target.durationMs,
                signalStrength = target.signalStrength,
                attribution = org.ort.core.Attribution.unknown(),
                currentTranscriptText = null,
                supersededTranscriptTexts = emptyList(),
                hasAudio = false,
            ),
        ),
        transcriptExcerpt = db.transcriptDao().getCurrent(target.id)?.text.orEmpty(),
        oldCallsign = oldCallsign,
        newCallsign = newCallsign,
    )
}
