package org.ort.app.ui.data

import android.content.Context
import org.ort.core.Attribution
import org.ort.core.AttributionState
import org.ort.core.PassId
import org.ort.core.TransmissionState
import org.ort.core.Ulid
import org.ort.data.OrtDatabase
import org.ort.data.canTransition
import org.ort.data.entity.PriorAdjustmentEntity
import org.ort.data.entity.TranscriptEntity
import org.ort.data.entity.TranscriptPass
import org.ort.data.entity.TransmissionEntity
import org.ort.data.entity.VoiceprintBindingHistoryEntity
import org.ort.data.entity.VoiceprintBindingSource
import org.ort.data.entity.VoiceprintEntity
import org.ort.data.entity.WorkAttemptEntity
import org.ort.data.entity.WorkAttemptOutcome
import org.ort.data.entity.WorkQueueState
import org.ort.data.inWriteTransaction
import org.ort.data.requireLegalTransition
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
 *
 * **R-153, F18 `Fail-Pass.dc.html`, FR-RUN-9.** [passFailure] reads the real terminally-`FAILED`
 * [org.ort.data.entity.WorkQueueItemEntity] for a transmission, if any; [retryFailedPass] gives it
 * a fresh, genuinely durable run. See each function's own doc for the schema gap this cannot
 * honestly represent (per-attempt history) and why there is no scheduler to trigger synchronously.
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
    /** R-191: `Detail-Propagated.dc.html`'s subtitle clause ("picked from the resolver's
     * candidates · 06:20") — the real [CorrectionRequest.tier]/`.correctedAtMillis` this
     * propagation was applied with. Defaulted so every call site built before R-191 compiles
     * unchanged (`undoAll`'s own synthetic outcome never surfaces on screen — see that function). */
    val tier: CorrectionTier? = null,
    val correctedAtMillis: Long? = null,
) {
    public val voiceprintReassigned: Boolean get() = voiceprintRebind != null
    public val priorsUpdatedCount: Int get() = priorAdjustments.size
}

/**
 * R-055, `Detail-Revisions.dc.html`: one version card. [who] is pass + model, plus "corrected" when
 * this transmission has at least one [org.ort.data.entity.CorrectionEntity] recorded against it.
 * [stationId]/[corrected] are R-194's own addition — the transmission's real, *current* attribution,
 * shown the same on every version's card: `:data` keeps no per-version attribution history (only the
 * transcript text is versioned), so this is the one real fact available, not a fabricated one
 * matching each version's own moment.
 */
public data class TranscriptVersionViewState(
    val id: String,
    val text: String,
    val timeLabel: String,
    val who: String,
    val isCurrent: Boolean,
    val stationId: String? = null,
    val corrected: Boolean = false,
)

/**
 * R-185 (halt), `Detail-Correct-B.dc.html`: one station this phone has heard, matched against a
 * Tier B search query. [evidence] is built only from real, computed facts (never the board's own
 * "Tuesday net regular" day-of-week narrative, which this package cannot honestly derive from what
 * `:data` exposes without a real per-day-of-week query) — see [CorrectionPolling.searchHeardStations]'s
 * own doc comment for exactly what is and is not represented.
 */
public data class StationSearchRow(
    val stationId: String,
    val evidence: String,
    val hasVoiceOnFile: Boolean,
    val userName: String?,
)

/** R-185: [rows] already filtered to [query]; [matchCount]/[totalCount] are `Detail-Correct-B.dc.html`'s
 * own "Matches · N of M" — every station this phone has ever heard, not a callsign database. */
public data class StationSearchOutcome(val rows: List<StationSearchRow>, val matchCount: Int, val totalCount: Int)

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
     * R-185 (halt), `Detail-Correct-B.dc.html`. "A station heard before" **used to be true lexicon
     * search** — audit F-018 (see this file's own class doc for R-052) replaced Tier B's original
     * "stations known" search with [ReaderPolling.searchLexicon] (a callsign search over the
     * bundled ITU allocation table, `:pipeline`'s `LexiconLookup`), reasoning Q8 names only three
     * tiers and the known-stations version was a substitute for one that did not exist yet. This
     * round's validator (and `Detail-Correct-B.dc.html` itself: "Only stations this phone has heard
     * appear here — it is not a callsign database") makes clear that reasoning does not hold: Tier
     * B and Tier C need to search genuinely different things — Tier B *this device's own heard
     * history*, Tier C *the grammar/ITU table* — not the same lexicon lookup wearing two labels.
     * F-018's lexicon search stays exactly where it landed ([ReaderPolling.searchLexicon]); this is
     * Tier B's own, separate read, back where the board has always shown it.
     *
     * **No `:data` query for this exists** (`:data` is not this package's to extend) — every
     * station this device has heard is only reachable by walking every transmission, exactly the
     * pattern already established elsewhere in this codebase (`org.ort.app.ui.data.StationPolling`,
     * WP8's file, derives its own station list the same way rather than a dedicated query). Real,
     * not fabricated, for every field returned: [StationSearchRow.evidence] is built from the
     * actual count of transmissions carrying that `stationId` (never the board's own
     * "on this repeater"/"Tuesday net regular" narrative — this package has no frequency- or
     * day-of-week-specific query to honestly back that), and [StationSearchRow.hasVoiceOnFile]
     * from [org.ort.data.dao.CatalogDao.voiceprintsForStation] actually returning a row.
     */
    public suspend fun searchHeardStations(context: Context, query: String): StationSearchOutcome {
        val db = OrtDatabase.create(context.applicationContext)
        val heardCounts: Map<String, Int> = db.transmissionDao().listAll()
            .mapNotNull { it.stationId }
            .groupingBy { it }
            .eachCount()
        val q = query.trim().uppercase(Locale.ROOT)
        val matchingIds = if (q.isBlank()) {
            heardCounts.keys
        } else {
            heardCounts.keys.filter { it.uppercase(Locale.ROOT).startsWith(q) }
        }
        val rows = matchingIds.sorted().map { stationId ->
            val heardCount = heardCounts.getValue(stationId)
            val hasVoice = db.catalogDao().voiceprintsForStation(stationId).isNotEmpty()
            val station = db.catalogDao().getStation(stationId)
            val heardClause = if (heardCount == 1) "heard once" else "heard $heardCount times"
            val voiceClause = if (hasVoice) "voice on file" else "no voice on file yet"
            StationSearchRow(
                stationId = stationId,
                evidence = "$heardClause · $voiceClause",
                hasVoiceOnFile = hasVoice,
                userName = station?.userName,
            )
        }
        return StationSearchOutcome(rows = rows, matchCount = rows.size, totalCount = heardCounts.size)
    }

    /**
     * Register R-425, `Detail-Ambiguous.dc.html`: real, per-candidate evidence for the two-up
     * chooser — before this fix, both rows read the identical "a known station · United States"
     * whenever the two candidates shared an ITU country, which they usually do (adjacent
     * callsigns). [heardCount]/[lastHeardLabel] are this device's own real hear history for that
     * exact callsign ([org.ort.data.dao.TransmissionDao.listAll], the same source
     * [searchHeardStations] already reads for an identical fact); [voiceOnFile] is real
     * [org.ort.data.dao.CatalogDao.voiceprintsForStation] evidence, further narrowed to whether
     * *this* over's own voiceprint — not just any voiceprint — is the one bound to that candidate,
     * the strongest real signal this schema can offer for "this candidate is who was actually
     * speaking" short of the phonetics themselves. Never the board's own fabricated
     * "Tuesday net regular" narrative (this package has no day-of-week query to honestly back it —
     * the same named gap [searchHeardStations]'s own doc comment already discloses).
     */
    public data class AmbiguousCandidateEvidenceViewState(
        val heardCount: Int,
        val lastHeardLabel: String?,
        val voiceOnFile: Boolean,
    )

    public suspend fun ambiguousCandidateEvidence(
        context: Context,
        transmissionId: String,
        callsigns: List<String>,
    ): Map<String, AmbiguousCandidateEvidenceViewState> {
        val db = OrtDatabase.create(context.applicationContext)
        val all = db.transmissionDao().listAll()
        val currentVoiceprintId = db.transmissionDao().getById(transmissionId)?.voiceprintId
        return callsigns.associateWith { callsign ->
            val heard = all.filter { it.stationId == callsign }
            val lastHeard = heard.maxByOrNull { it.startedAtUtc }
            val boundVoiceprints = db.catalogDao().voiceprintsForStation(callsign)
            AmbiguousCandidateEvidenceViewState(
                heardCount = heard.size,
                lastHeardLabel = lastHeard?.let { ReaderTransmissionViewStateMapper.timeLabel(it.startedAtUtc) },
                voiceOnFile = currentVoiceprintId != null && boundVoiceprints.any { it.id == currentVoiceprintId },
            )
        }
    }

    /**
     * R-183, `Detail.dc.html`: an INFERRED explanation names the source over's real time ("to
     * 02:14:07, where the callsign was heard clearly"), not the generic "to the source over" this
     * package fell back to before this fix. Real, honest, and non-throwing for a source id that
     * does not resolve — a stale/deleted source, or a source id from before it was recorded
     * ([sourceTransmissionId] is always the raw stored string, no cross-module `TransmissionId.parse`
     * required here since this reads the *same* `transmission` table directly by primary key).
     */
    public suspend fun sourceOverTimeLabel(context: Context, sourceTransmissionId: String?): String? {
        if (sourceTransmissionId == null) return null
        val db = OrtDatabase.create(context.applicationContext)
        val source = db.transmissionDao().getById(sourceTransmissionId) ?: return null
        return ReaderTransmissionViewStateMapper.timeLabelFor(
            TransmissionDetail(
                id = source.id,
                startedAtUtcMillis = source.startedAtUtc,
                frequencyHz = source.frequencyHz,
                durationMs = source.durationMs,
                signalStrength = source.signalStrength,
                attribution = org.ort.core.Attribution.unknown(),
                currentTranscriptText = null,
                supersededTranscriptTexts = emptyList(),
                hasAudio = false,
            ),
        )
    }

    /**
     * R-189 (halt): the real root cause behind two separately-filed reports — a detail rendering
     * UNKNOWN after `Undo all` even though the row genuinely reverted, *and* the same reversion
     * roughly 2s after applying a fresh typed correction, no `Undo` involved. Both go through the
     * identical write path, [org.ort.data.dao.CorrectionDao.recordCorrection] →
     * `applyCorrectedAttribution`, which — correctly, per [Attribution.withCorrection]'s own
     * contract — sets `attributionState = 'INFERRED'` with `attributionConfidence = NULL` (there is
     * no calibrated number for "a human said so"). [org.ort.app.ui.data.ReaderPolling]'s own
     * attribution derivation (WP4's file, not this package's to edit) requires a non-null
     * confidence for every INFERRED row and silently downgrades anything missing one to
     * [Attribution.unknown] — so the very next poll after a correction (or an undo-as-correction)
     * read the row back as UNKNOWN, discarding the real, just-written `stationId` along with it.
     *
     * Fixed here, not in `ReaderPolling.kt`: this package's own read path re-derives the attribution
     * directly from the real `transmission.corrected`/`stationId` columns whenever `corrected` is
     * set — "current attribution = latest correction if any, else the resolver's" (the coordinator's
     * own framing, verbatim) — returning [fallback] (whatever `ReaderPolling` already computed, which
     * is correct for every *uncorrected* row) unchanged otherwise. [Attribution.withCorrection] is
     * used exactly the way [org.ort.data.dao.CorrectionDao.applyCorrectedAttribution]'s own write
     * shapes it — never a confidence this row does not have.
     *
     * **Register R-321: confirmed still live, not deleted.** A freshly *applied* correction (not
     * yet undone) still goes through `applyCorrectedAttribution`'s own unconditional
     * `INFERRED`/`NULL`-confidence write — unchanged by R-321, which only touches [undoAll]'s own
     * *restore* path — so `ReaderPolling`'s downgrade-to-UNKNOWN bug this function patches is still
     * reachable on every fresh correction. It is also still reachable *after* an undo whose
     * restored snapshot was itself already `corrected` (a correction applied on top of an earlier,
     * still-standing one, then undone back to that earlier one) — `restoreAttribution` can and does
     * write that exact `corrected = true`/`INFERRED`/`NULL`-confidence shape back,
     * since it is what the snapshot genuinely recorded ([org.ort.data.dao.CorrectionDao.restoreAttribution]).
     */
    public suspend fun currentAttribution(
        context: Context,
        transmissionId: String,
        fallback: Attribution,
    ): Attribution {
        val db = OrtDatabase.create(context.applicationContext)
        val entity = db.transmissionDao().getById(transmissionId) ?: return fallback
        val stationId = entity.stationId
        return if (entity.corrected && stationId != null) {
            Attribution.unknown().withCorrection(stationId)
        } else {
            fallback
        }
    }

    /**
     * R-188: `Detail-Unknown.dc.html`'s "Transcript confidence 0.61" caption, generalised to every
     * state that carries a current transcript — [org.ort.data.entity.TranscriptEntity.confidence]
     * is the real per-transcript figure Pass B (or Pass A) recorded, read directly since neither
     * `TransmissionDetail` nor `TransmissionDetailViewState` carries it through today. Deliberately
     * **not** the board's own "· weak signal, cut off" qualitative clause — this package has no
     * honest way to derive "weak signal" or "cut off" from a bare confidence number, and constitution
     * I forbids inventing one. `null` for a transmission with no current transcript row at all, or
     * whose row recorded no confidence (e.g. a corrected/restored version — see
     * [org.ort.data.dao.TranscriptDao.supersede]'s own callers).
     */
    public suspend fun currentTranscriptConfidence(context: Context, transmissionId: String): Double? {
        val db = OrtDatabase.create(context.applicationContext)
        return db.transcriptDao().getCurrent(transmissionId)?.confidence
    }

    /**
     * Register R-320 (schema v5), `Detail-Why.dc.html` section 1: [ReaderPolling.transmissionDetail]'s
     * own [InspectionViewState] never carries [org.ort.data.entity.LatticeSlotEntity] rows through
     * (`ReaderPolling.kt` is WP4's file — this package's row does not extend that call) — this
     * re-derives the identical [InspectionViewState] a caller already has, with real slot detail
     * attached via [CatalogDao.slotDetailsFor], for [TransmissionDetailContent] to overwrite the
     * base poll's own `inspection` with. A second, small, redundant read of `latticesFor`/
     * `candidatesFor` — the same trade-off [currentAttribution]'s own extra `transmissionDao().getById`
     * already accepts, rather than widening `ReaderPolling.kt`'s own signature.
     */
    public suspend fun inspectionWithSlots(context: Context, transmissionId: String): InspectionViewState {
        val db = OrtDatabase.create(context.applicationContext)
        return InspectionViewStateMapper.from(
            db.catalogDao().latticesFor(transmissionId),
            db.catalogDao().candidatesFor(transmissionId),
            db.catalogDao().slotDetailsFor(transmissionId),
        )
    }

    /**
     * Register R-182 (schema v5), D01/D03's transcript highlight: the winning candidate's real
     * `[start, end)` character span into the current transcript, from
     * [CatalogDao.winningCandidateCharSpan] — `null` when either bound is `null` (no winning
     * candidate, or one whose lattice was not text-anchored, e.g. every acoustic lattice; see
     * [org.ort.lexicon.SlotDetail]'s own doc comment for exactly when a span exists), never a
     * fabricated `0` standing in for "unknown".
     */
    public suspend fun winningCharSpan(context: Context, transmissionId: String): IntRange? {
        val db = OrtDatabase.create(context.applicationContext)
        val span = db.catalogDao().winningCandidateCharSpan(transmissionId)
        val start = span.spanStart
        val end = span.spanEnd
        return if (start != null && end != null) start until end else null
    }

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
            tier = request.tier,
            correctedAtMillis = request.correctedAtMillis,
        )
    }

    /**
     * `Detail-Propagated.dc.html`'s `Undo all` — restores every affected transmission's *exact*
     * pre-correction attribution (register R-321, fixed): [org.ort.data.dao.CorrectionDao.recordCorrection]
     * (used here before this fix) always writes the shape a genuine human correction has —
     * `INFERRED`, no confidence, no source, `corrected = 1` — so an undo recorded that way lied
     * about what the over's attribution really was before anyone touched it (an AMBIGUOUS over's
     * `Undo all` left it reading INFERRED-and-corrected forever, never returning to its chooser).
     * [org.ort.data.dao.CorrectionDao.recordCorrection] now stamps the transmission's real
     * `attributionState`/`attributionConfidence`/`attributionSourceTransmissionId`/`corrected` —
     * read *before* the correction it is about to apply — onto the very [org.ort.data.entity.CorrectionEntity] row it
     * inserts (`previousAttributionState` etc.), so this only has to look that snapshot back up and
     * hand it to the new, unconditional [org.ort.data.dao.CorrectionDao.restoreAttribution], which
     * (unlike `applyCorrectedAttribution`) accepts an arbitrary state and a `null` `stationId` —
     * closing the "no station before the correction" gap this function's own doc used to name as
     * unrepresentable. Matched by [outcome]'s own `correctedAtMillis` (R-191) and `newValue`, so a
     * transmission this propagation was not the most recent correction for is never misread.
     * `previousCorrected == null`/no matching row at all (an older correction, from before this
     * schema, whose snapshot was never captured) falls back to the honest, disclosed default this
     * package always used before this fix (`UNKNOWN`, uncorrected) rather than fabricating one.
     *
     * **Still writes its own audit [org.ort.data.entity.CorrectionEntity]**, exactly as before this
     * fix (constitution III: undo is itself a further, kept correction, not merely implied by the
     * attribution changing) — via a plain [org.ort.data.dao.CorrectionDao.insert], not
     * `recordCorrection` (which would call `applyCorrectedAttribution` and immediately re-break the
     * very attribution [restoreAttribution] just wrote). Only when [AffectedOverViewState.oldCallsign]
     * is non-null: [org.ort.data.entity.CorrectionEntity.newValue] is a non-null column, so a
     * restore back to *no* station (a genuinely unattributed original) has no correction-log row to
     * fit — the real attribution write below still happens unconditionally either way; only the
     * audit trail for that one honest edge case is a named gap, not a fabricated row.
     */
    public suspend fun undoAll(context: Context, outcome: PropagationOutcome, atMillis: Long) {
        val db = OrtDatabase.create(context.applicationContext)
        outcome.affected.forEach { row ->
            val original = db.correctionDao().correctionsFor(row.transmissionId).lastOrNull {
                it.newValue == row.newCallsign &&
                    (outcome.correctedAtMillis == null || it.correctedAt == outcome.correctedAtMillis)
            }
            row.oldCallsign?.let { old ->
                db.correctionDao().insert(
                    CorrectionRequest(
                        transmissionId = row.transmissionId,
                        previousStationId = row.newCallsign,
                        newStationId = old,
                        tier = CorrectionTier.PICK_CANDIDATE,
                        correctedAtMillis = atMillis,
                    ).toEntity(),
                )
            }
            db.correctionDao().restoreAttribution(
                transmissionId = row.transmissionId,
                state = original?.previousAttributionState ?: AttributionState.UNKNOWN,
                stationId = row.oldCallsign,
                confidence = original?.previousAttributionConfidence,
                sourceTransmissionId = original?.previousAttributionSourceTransmissionId,
                corrected = original?.previousCorrected ?: false,
            )
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
        val transmission = db.transmissionDao().getById(transmissionId)
        return ordered.map { version ->
            TranscriptVersionViewState(
                id = version.id,
                text = version.text,
                timeLabel = timeLabel(version.createdAt),
                who = who(version, corrected && version.isCurrent),
                isCurrent = version.isCurrent,
                stationId = transmission?.stationId,
                corrected = transmission?.corrected ?: false,
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
     * R-153, F18 `Fail-Pass.dc.html`, FR-RUN-9: the real terminally-`FAILED`
     * [org.ort.data.entity.WorkQueueItemEntity] for [transmissionId], if one exists — `null` for
     * every other transmission, never a fabricated failure. There is no `:data` query that finds a
     * failed item by transmission alone (`WorkQueueDao.findByTransmissionAndPass` needs the pass),
     * so this checks every [PassId] in pipeline order and returns the first `FAILED` match — a
     * transmission has at most one, since a queue item leaves the active-state set the moment it
     * either completes or is marked terminally failed (technical design §7.1's partial unique
     * index). Callers gate this behind `processingState == FAILED`
     * ([org.ort.app.ui.screens.TransmissionDetailContent]) so a healthy transmission never pays for
     * eight empty lookups.
     */
    public suspend fun passFailure(context: Context, transmissionId: String): PassFailureViewState? {
        val db = OrtDatabase.create(context.applicationContext)
        for (pass in PassId.entries) {
            val item = db.workQueueDao().findByTransmissionAndPass(transmissionId, pass.name)
                .firstOrNull { it.state == WorkQueueState.FAILED }
            if (item != null) {
                return PassFailureViewState(
                    passId = pass,
                    passLabel = passLabel(pass),
                    lastError = item.lastError?.takeIf { it.isNotBlank() } ?: "no error text recorded",
                    attempts = item.attemptCount,
                    attemptLog = db.workQueueDao().attemptsFor(item.id).map(::attemptViewState),
                )
            }
        }
        return null
    }

    /** Register R-426: the real per-attempt row, humanized — see [PassAttemptViewState]'s own doc
     * comment for why a `TIMEOUT` outcome reads "Timed out" rather than its raw stored `"timeout"`
     * reason. */
    private fun attemptViewState(attempt: WorkAttemptEntity): PassAttemptViewState = PassAttemptViewState(
        timeLabel = ReaderTransmissionViewStateMapper.timeLabel(attempt.finishedAtMillis),
        reasonLabel = when (attempt.outcome) {
            WorkAttemptOutcome.TIMEOUT -> "Timed out"
            WorkAttemptOutcome.FAILED -> attempt.reason.replaceFirstChar { it.titlecase() }
        },
    )

    /** Guide §9's "pass" vocabulary, sentence case, never the raw [PassId] enum name. */
    private fun passLabel(pass: PassId): String = when (pass) {
        PassId.ENH -> "The enhancement pass"
        PassId.A_STREAM -> "Pass A"
        PassId.B_OFFLINE -> "Pass B"
        PassId.FUSE -> "The fusion pass"
        PassId.C_SPOT -> "Pass C"
        PassId.D_RESOLVE -> "Pass D"
        PassId.E_IDENTITY -> "The identity pass"
        PassId.F_DIGEST -> "The digest pass"
    }

    /**
     * R-153/FR-RUN-9: `Retry this pass` — genuinely gives the failed item a fresh run
     * ([org.ort.data.dao.WorkQueueDao.requeueToReady]) and returns the transmission `FAILED` →
     * `PROCESSING`, both in one transaction, the same pairing
     * [org.ort.data.WorkQueue.requeueFailed] uses internally. **Scoped to exactly this
     * transmission's item** — [org.ort.data.WorkQueue.requeueFailed] matches every `FAILED` item
     * for a pass across the whole queue, which is too broad for a per-over button (it would retry
     * every other transmission's stuck items too).
     *
     * There is no `:pipeline` scheduler or `WorkManager` job to trigger a synchronous re-run today
     * (searched `pipeline/src/main/kotlin` for `WorkManager`/`Scheduler`; the only hit is a comment
     * in `RealCaptureService.kt` noting the drain is in-process only, M8/M10 work) — this write is
     * therefore the real re-queue this build has, not a no-op standing in for one: it genuinely
     * flips durable queue state, and the next drain (this session's capture, or the next launch's
     * `WorkQueue.recoverStaleLeases`/lease loop) picks it up. Returns `false`, changing nothing, if
     * no matching `FAILED` item exists any more (e.g. it was already retried and failed again under
     * a different pass, or completed) — never throws for a stale button press.
     */
    public suspend fun retryFailedPass(context: Context, transmissionId: String, pass: PassId): Boolean {
        val db = OrtDatabase.create(context.applicationContext)
        return db.inWriteTransaction {
            val failed = db.workQueueDao().findByTransmissionAndPass(transmissionId, pass.name)
                .firstOrNull { it.state == WorkQueueState.FAILED }
                ?: return@inWriteTransaction false
            db.workQueueDao().requeueToReady(failed.id)
            if (db.transmissionDao().canTransition(transmissionId, TransmissionState.PROCESSING)) {
                db.transmissionDao().requireLegalTransition(transmissionId, TransmissionState.PROCESSING)
            }
            true
        }
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
