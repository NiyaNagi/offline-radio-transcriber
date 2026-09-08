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
 */
public class DataPassBResultSink(private val db: OrtDatabase) : PassBResultSink {

    override suspend fun record(result: PassBResult): Unit = db.inWriteTransaction {
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
            }
            is PassBOutcome.Rejected -> {
                db.transmissionDao().setRejectionReason(result.transmissionId, "${outcome.rule}: ${outcome.detail}")
            }
            is PassBOutcome.Failed -> Unit
        }
        persistLattice(result)
        persistCandidates(result)
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
