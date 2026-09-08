package org.ort.app.ui.data

import android.content.Context
import org.ort.core.Ulid
import org.ort.data.OrtDatabase
import org.ort.data.entity.TranscriptEntity
import org.ort.data.entity.TranscriptPass
import org.ort.data.entity.TransmissionEntity
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
 * **What this honestly does *not* do**, named rather than silently skipped:
 * - [PropagationOutcome.voiceprintReassigned] is always `false`. `Detail-Propagated.dc.html` shows
 *   "1 · voiceprint now belongs to KA7LWH", but [org.ort.data.dao.CatalogDao] (the only `:data`
 *   entry point to [org.ort.data.entity.VoiceprintEntity]) offers `insert` and a read by
 *   `boundStationId` — no update path for `VoiceprintEntity.boundStationId` exists for this
 *   package to call without adding one to a `:data` DAO this package does not own. Reported in
 *   this package's CHANGELOG as a stop-and-report, not implemented as a silent no-op.
 * - [PropagationOutcome.priorsUpdatedCount] is always `0`, for the same reason one level up: no
 *   `:app`-reachable write path updates a prior's stored weight. Real zero, not an absent field —
 *   guide §9: "an unknown confidence is absent, not zero" does not apply here, since this really
 *   is zero (nothing was updated), not unmeasured.
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

/** `Detail-Propagated.dc.html`'s "what changed" tile, plus what [CorrectionPolling.undoAll] needs. */
public data class PropagationOutcome(
    val newCallsign: String,
    val previousCallsign: String?,
    val overCount: Int,
    val voiceprintReassigned: Boolean,
    val priorsUpdatedCount: Int,
    val deletedCount: Int,
    val affected: List<AffectedOverViewState>,
)

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

    /** `Flow-Correct.dc.html`'s "the count is shown before applying" — computed without writing. */
    public suspend fun affectedOverCount(context: Context, transmissionId: String, scope: CorrectionScope): Int =
        affectedTransmissions(OrtDatabase.create(context.applicationContext), transmissionId, scope).size

    /**
     * Applies [request] to every transmission [scope] selects, each as its own
     * [org.ort.data.dao.CorrectionDao.recordCorrection] (its own audit row, its own locked
     * attribution — never one write standing in for many).
     */
    public suspend fun applyCorrection(
        context: Context,
        request: CorrectionRequest,
        scope: CorrectionScope,
    ): PropagationOutcome {
        val db = OrtDatabase.create(context.applicationContext)
        val targets = affectedTransmissions(db, request.transmissionId, scope)
        val affected = targets.map { target ->
            val entity = request.copy(
                transmissionId = target.id,
                previousStationId = target.stationId,
            ).toEntity()
            db.correctionDao().recordCorrection(entity)
            affectedRow(db, target, oldCallsign = target.stationId, newCallsign = request.newStationId)
        }
        return PropagationOutcome(
            newCallsign = request.newStationId,
            previousCallsign = request.previousStationId,
            overCount = affected.size,
            voiceprintReassigned = false,
            priorsUpdatedCount = 0,
            deletedCount = 0,
            affected = affected,
        )
    }

    /**
     * `Detail-Propagated.dc.html`'s `Undo all` — reverts every affected transmission back to its
     * recorded [AffectedOverViewState.oldCallsign] as a **new** correction (never a delete). A row
     * whose [AffectedOverViewState.oldCallsign] was `null` (it had no station before the
     * correction) is left alone: [org.ort.data.dao.CorrectionDao.applyCorrectedAttribution]'s
     * fixed SQL requires a non-null station, so a true revert-to-unattributed is not representable
     * through the write path this package can call without editing `:data` — named here rather
     * than silently skipped.
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
