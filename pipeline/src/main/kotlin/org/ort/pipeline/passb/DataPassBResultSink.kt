package org.ort.pipeline.passb

import org.ort.asrapi.PassBOutcome
import org.ort.core.AttributionState
import org.ort.core.SystemClock
import org.ort.core.Ulid
import org.ort.data.OrtDatabase
import org.ort.data.entity.CallsignCandidateEntity
import org.ort.data.entity.LatticeSlotEntity
import org.ort.data.entity.PhoneticLatticeEntity
import org.ort.data.entity.TranscriptEntity
import org.ort.data.entity.TranscriptPass
import org.ort.data.inWriteTransaction
import org.ort.lexicon.PhoneticLattice
import org.ort.lexicon.SlotDetail
import org.ort.pipeline.alerts.AlertEvaluationTrigger
import org.ort.pipeline.alerts.AlertMatchInput
import org.ort.pipeline.alerts.NoOpAlertEvaluationTrigger
import org.ort.pipeline.threading.RoomThreadRepository
import org.ort.pipeline.threading.ThreadGroupingCoordinator

/**
 * The real [PassBResultSink] (build-plan P12, defect 3): `PassB`'s own doc comment names
 * "`:data` gaining a write path for attribution fields" as a follow-up left open by P11 — this is
 * that follow-up.
 *
 * A transcript row is written **only** for a [PassBOutcome.Accepted] decode (AC-31: exactly one
 * current version, via [org.ort.data.dao.TranscriptDao.supersede]'s single-transaction
 * clear-then-insert) — nothing here invents transcript text for a segment the rejection pipeline
 * refused. A [PassBOutcome.Rejected] instead records its rule/detail on the transmission's own
 * `rejectionReason` column, so AC-8's "reachable, not hidden" holds at the persisted layer too,
 * not only in the in-memory [org.ort.asrapi.RejectedSegmentLog] build-plan P10 built. A
 * [PassBOutcome.Failed] writes nothing here — it is retryable, and `WorkQueue.failPass` already
 * records the error against the queue row itself (constitution VI: don't duplicate provenance).
 *
 * **audit F-009 (FR-UI-8, FR-LEX-12, constitution I):** the raw [PassBResult.lattice] and every
 * ranked candidate in [PassBResult.ranked] — including each prior's contribution — are persisted
 * here too, into [org.ort.data.dao.CatalogDao]'s `phonetic_lattice`/`callsign_candidate` tables,
 * in the same transaction as the transcript/attribution write. Without this the inspection
 * surface (`InspectionSurface.kt`, `ReaderPolling.kt`) reads those tables and finds them always
 * empty — a machine conclusion no one could actually inspect. Both are `@Insert`-only with a
 * fresh id per call, so a re-run for the same transmission **adds** a new lattice/candidate set
 * rather than overwriting the previous one (constitution III: "nothing is deleted quietly") —
 * the superseded conclusion stays reachable, `createdAt`/insertion order distinguishing it from
 * the latest.
 *
 * **Register R-320, R-182:** each candidate's own [org.ort.lexicon.CallsignCandidate.slotDetails]
 * — per-slot score/kept-alternate for D05, and the char span for D01/D03's transcript highlight —
 * is persisted alongside it, into `:data`'s new `lattice_slot` table
 * ([org.ort.data.entity.LatticeSlotEntity]), in the same transaction. See [persistSlotDetails].
 *
 * **Register R-1032/R-1033 (constitution VI: "no number without ... execution provider"):
 * [result]'s own [PassBResult.fingerprint] is the one production source of both facts, for every
 * caller of this sink — live capture (`RealCaptureService`, always `Tier.T0`) and reprocessing
 * (`ReprocessRunner`, any tier) alike, since both build their real `PassB` through the same
 * [PassBFactory.create]:
 * - **executionProvider** ([org.ort.data.dao.TransmissionDao.setExecutionProvider]) is written for
 *   *every* outcome, including [PassBOutcome.Failed] — even a failed attempt genuinely ran (or
 *   genuinely could not, `"none"`) on this provider; provenance is a fact about the attempt, not
 *   the outcome.
 * - **processedTier** ([org.ort.data.dao.TransmissionDao.setProcessedTier]) is written only for
 *   [PassBOutcome.Accepted] and [PassBOutcome.Rejected] — see
 *   [org.ort.data.entity.TransmissionEntity.processedTier]'s own doc comment for why `Failed` must
 *   leave it untouched (a transmission that did not genuinely run at this tier stays eligible).
 *
 * **Build-plan P24 (FR-SPK-5, AC-163..165):** [threadGrouper] runs, unconditionally, as the very
 * last step of [record] — automatic thread grouping happens **after** Pass B closes a
 * transmission, off the capture path, whatever [result]'s own outcome was (see [threadGrouper]'s
 * own kdoc for why a [PassBOutcome.Failed] retry is harmless rather than double-counted).
 *
 * **Build-plan P31 (FR-ALR-3, FR-ALR-4, AC-194, AC-195):** [alertTrigger] is handed one
 * [org.ort.pipeline.alerts.AlertMatchInput] per call, built from this same, just-resolved
 * [result] plus a fresh read of [result]'s own [org.ort.data.entity.TransmissionEntity.frequencyHz]
 * — never from a Pass A partial, which never reaches this sink at all (AC-194). [AlertEvaluationTrigger.fireAndForget]
 * is not `suspend` and is wrapped in [runCatching] here besides: a hung, slow or throwing alert
 * path must never delay this write transaction or the pass queue behind it (constitution IV,
 * FR-ALR-4, AC-195) — see that interface's own kdoc for how it guarantees that.
 */
public class DataPassBResultSink(
    private val db: OrtDatabase,
    private val threadGrouper: ThreadGroupingCoordinator = ThreadGroupingCoordinator(RoomThreadRepository(db)),
    private val alertTrigger: AlertEvaluationTrigger = NoOpAlertEvaluationTrigger,
) : PassBResultSink {

    override suspend fun record(result: PassBResult): Unit = db.inWriteTransaction {
        // R-1032: unconditional -- the provider a pass ran on is known the moment it ran, whatever
        // it concluded (see this class's own kdoc).
        db.transmissionDao().setExecutionProvider(result.transmissionId, result.fingerprint.provider)
        when (val outcome = result.outcome) {
            is PassBOutcome.Accepted -> {
                db.transcriptDao().supersede(
                    TranscriptEntity(
                        id = Ulid.generate().value,
                        transmissionId = result.transmissionId,
                        pass = TranscriptPass.B,
                        text = outcome.result.text,
                        modelId = outcome.result.modelRef.assetId,
                        modelVersion = outcome.result.modelRef.version,
                        quantization = null,
                        decodeParams = null,
                        noSpeechProb = outcome.result.noSpeechProb,
                        confidence = result.attribution.confidence,
                        isCurrent = true,
                        createdAt = SystemClock.wallMillis(),
                    ),
                )
                db.transmissionDao().updateAttribution(
                    id = result.transmissionId,
                    state = result.attribution.state,
                    stationId = result.attribution.stationId,
                    confidence = result.attribution.confidence,
                    sourceTransmissionId = result.attribution.sourceTransmissionId?.ulid?.value,
                )
                // R-1033: this outcome means Pass B genuinely ran this record at fingerprint.tier
                // -- see TransmissionEntity.processedTier's own doc comment.
                db.transmissionDao().setProcessedTier(result.transmissionId, result.fingerprint.tier)
            }
            is PassBOutcome.Rejected -> {
                db.transmissionDao().setRejectionReason(result.transmissionId, "${outcome.rule}: ${outcome.detail}")
                // R-1033: a rejection is still a genuine run at this tier (rejected is not failed).
                db.transmissionDao().setProcessedTier(result.transmissionId, result.fingerprint.tier)
            }
            is PassBOutcome.Failed -> Unit
        }
        // D56 (register R-1132): the first production write path for a StationEntity -- see
        // recordStationObservation's own kdoc for the gate.
        recordStationObservation(result)
        persistLattice(result)
        persistCandidates(result)
        // P31 (FR-ALR-3, FR-ALR-4, AC-194, AC-195): fires only from this Pass B closure point,
        // never from a Pass A partial (which never reaches this sink). `runCatching` around a
        // non-suspend, fire-and-forget call: see this class's own kdoc for why neither this write
        // transaction nor the pass queue behind it may ever wait on, or be broken by, alert
        // evaluation.
        runCatching { alertTrigger.fireAndForget(alertMatchInputFor(result)) }
        threadGrouper.onTransmissionClosed(result.transmissionId)
    }

    /** [PassBResult] itself carries no frequency (technical design §3.4: a pass is a pure function
     * of the segment audio, not of the row's own rig-set fields) — read fresh, in this same write
     * transaction, from the [org.ort.data.entity.TransmissionEntity] segment-persist already wrote
     * (constitution I: never guessed, `null` when genuinely absent). [AlertMatchInput.transcriptText]
     * is `null` for anything but a [PassBOutcome.Accepted] outcome — a keyword watch has nothing to
     * match against a rejected or failed decode.
     *
     * **Register R-1125:** [AlertMatchInput.resolvedCallsign] is [result]'s own top-ranked
     * candidate's text ([result.ranked]'s first entry — already sorted best-first by
     * [org.ort.lexicon.PriorCombiner.rank]) — never [result.attribution.stationId], which is
     * `null` for `AMBIGUOUS`/`UNKNOWN` (every attribution today without a calibrator, register
     * R-1110). `null` here exactly when Pass B parsed no candidate at all (a rejected/failed
     * outcome, or accepted text with no callsign-shaped words in it). */
    private suspend fun alertMatchInputFor(result: PassBResult): AlertMatchInput = AlertMatchInput(
        transmissionId = result.transmissionId,
        attributionState = result.attribution.state,
        stationId = result.attribution.stationId,
        resolvedCallsign = result.ranked.firstOrNull()?.candidate?.text,
        transcriptText = (result.outcome as? PassBOutcome.Accepted)?.result?.text,
        frequencyHz = db.transmissionDao().getById(result.transmissionId)?.frequencyHz,
    )

    /**
     * D56 (register R-1132, R-1109, R-1124, R-1110; FR-SPK-1, FR-SPK-10, FR-LEX-25..27, FR-DIG-7,
     * constitution I): the first production write path that ever creates a
     * [org.ort.data.entity.StationEntity] row — before this, every construction of that entity in
     * the tree lived in `src/test` or `src/debug`, so the Stations screen, every station-detail
     * surface, and [org.ort.pipeline.passb.DataRankingContextSource]'s own database-presence and
     * recency reads (R-1124) were all permanently cold on a real device.
     *
     * Gated on `result.attribution.state != AttributionState.UNKNOWN` — exactly D56's own
     * "`AMBIGUOUS` or better", never `CONFIRMED`-only: [CallsignResolver] only ever returns
     * `CONFIRMED` with a real, fitted calibrator, which does not exist in production yet
     * (register R-1110) — a bar set at `CONFIRMED` would keep the catalog empty and re-create
     * this exact bug. [CallsignResolver.resolve] only ever returns `UNKNOWN` when [PassBResult.ranked]
     * is empty (no candidate survived parsing/separation) or, with a calibrator, below its
     * confirm threshold — and every [PassBOutcome.Rejected]/[PassBOutcome.Failed] outcome already
     * resolves to [Attribution.unknown] in [PassB.run] — so this one guard alone already excludes
     * every rejected or failed over; no separate outcome check is needed here.
     *
     * Keyed on the *top-ranked* candidate's own text ([PassBResult.ranked]'s first entry, already
     * best-first — [alertMatchInputFor]'s identical reasoning for R-1125), never on
     * `result.attribution.stationId`, which is `null` for `AMBIGUOUS` (register R-1110) — the
     * exact case this row exists to stop discarding.
     */
    private suspend fun recordStationObservation(result: PassBResult) {
        if (result.attribution.state == AttributionState.UNKNOWN) return
        val callsign = result.ranked.firstOrNull()?.candidate?.text ?: return
        db.catalogDao().recordStationObservation(callsign, result.attribution.state, SystemClock.wallMillis())
    }

    /** [PassBResult.lattice] is non-null exactly when Pass B produced one (see its own doc
     * comment) — never silently dropped, always inspectable (constitution I, FR-UI-8). */
    private suspend fun persistLattice(result: PassBResult) {
        val lattice = result.lattice ?: return
        db.catalogDao().insert(
            PhoneticLatticeEntity(
                id = Ulid.generate().value,
                transmissionId = result.transmissionId,
                source = when (lattice.source) {
                    org.ort.lexicon.LatticeSource.ACOUSTIC -> org.ort.data.entity.LatticeSource.ACOUSTIC
                    org.ort.lexicon.LatticeSource.TEXT_DERIVED -> org.ort.data.entity.LatticeSource.TEXT_DERIVED
                },
                unitsBlob = serializeSlots(lattice),
                modelId = lattice.modelRef?.assetId,
                createdAt = SystemClock.wallMillis(),
            ),
        )
    }

    /** Every ranked candidate is written — ranking only reorders, so every input candidate
     * "survives" (FR-LEX-9) into the persisted record too (FR-LEX-12): a UI or a later
     * re-ranking pass needs every candidate, not just the one that won. */
    private suspend fun persistCandidates(result: PassBResult) {
        result.ranked.forEachIndexed { index, ranked ->
            val candidateId = Ulid.generate().value
            db.catalogDao().insert(
                CallsignCandidateEntity(
                    id = candidateId,
                    transmissionId = result.transmissionId,
                    callsign = ranked.candidate.text,
                    rank = index,
                    score = ranked.totalScore.toDouble(),
                    // CallsignGrammar.parse only ever emits structurally valid, ITU-allocated
                    // candidates (see CallsignCandidate's own doc comment) — every ranked
                    // candidate reaching this sink already passed that check.
                    grammarValid = true,
                    ituPrefix = ranked.candidate.allocation.prefix,
                    ituCountry = ranked.candidate.allocation.entity,
                    priorBreakdown = ranked.contributions.associate { it.name to it.logOdds.toDouble() },
                    // Read from the "database" prior's own contribution rather than threading a
                    // separate signal through PassBResult: cold start (no database loaded) and a
                    // real miss both mean "not a hit" here, honestly (FR-LEX-31).
                    databaseHit = ranked.contribution("database")?.let { !it.coldStart && it.logOdds > 0f } ?: false,
                    selected = result.attribution.state == AttributionState.CONFIRMED &&
                        result.attribution.stationId == ranked.candidate.text,
                ),
            )
            persistSlotDetails(result.transmissionId, candidateId, ranked.candidate.slotDetails)
        }
    }

    /**
     * Register R-320, R-182: one [LatticeSlotEntity] per [org.ort.lexicon.SlotDetail] the lexicon
     * side already computed for this candidate — see that type's own doc comment for what each
     * field means and when it is genuinely `null` versus fabricated. `slotDetails` is empty for
     * every candidate CallsignGrammar produced before this existed (an older jar, or an outcome
     * with no lattice at all), so this loop is a no-op then, not an error.
     */
    private suspend fun persistSlotDetails(transmissionId: String, candidateId: String, slotDetails: List<SlotDetail>) {
        slotDetails.forEach { detail ->
            db.catalogDao().insert(
                LatticeSlotEntity(
                    id = Ulid.generate().value,
                    transmissionId = transmissionId,
                    candidateId = candidateId,
                    index = detail.index,
                    unit = detail.unit,
                    score = detail.score,
                    keptAlternate = detail.keptAlternate,
                    charStart = detail.charStart,
                    charEnd = detail.charEnd,
                ),
            )
        }
    }

    /**
     * A lossless, hand-rolled JSON array of [LatticeSlot]s — `unitsBlob` is documented as "an
     * opaque blob here; §9's types own the shape" ([org.ort.data.entity.PhoneticLatticeEntity]),
     * so this module is free to choose it. Every alternative's score is kept (FR-LEX-5: "the
     * lattice is never collapsed to a single best path"), not just the winning unit per slot.
     * [org.ort.lexicon.PhoneticUnit] names are a closed, alphanumeric enum, so no escaping is
     * needed for them.
     */
    private fun serializeSlots(lattice: PhoneticLattice): String =
        lattice.slots.joinToString(prefix = "[", postfix = "]") { slot ->
            val alts = slot.alts.joinToString(prefix = "[", postfix = "]") { alt ->
                """{"unit":"${alt.unit.name}","logProb":${alt.logProb}}"""
            }
            """{"startMs":${slot.startMs},"endMs":${slot.endMs},"alts":$alts}"""
        }
}
