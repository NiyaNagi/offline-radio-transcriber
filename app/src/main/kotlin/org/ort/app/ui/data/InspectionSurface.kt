package org.ort.app.ui.data

import org.ort.data.entity.CallsignCandidateEntity
import org.ort.data.entity.LatticeSlotEntity
import org.ort.data.entity.PhoneticLatticeEntity

/**
 * Build-plan P16, FR-UI-8 — the inspection surface: for a resolved callsign, the phonetic
 * lattice, the candidate list, and the per-prior breakdown `:lexicon`'s `PriorCombiner` (P7)
 * produces. This is the surface that makes the resolver auditable (constitution I: "every
 * machine conclusion MUST be inspectable... a conclusion that cannot be explained cannot be
 * corrected").
 *
 * **Module boundary note.** `:app`'s allowed edges are `{:pipeline, :data, :net, :core}` —
 * `:lexicon` is deliberately not among them (`ModuleGraph.allowed`), and `:pipeline` depends on
 * `:lexicon` via `implementation`, not `api`, so `RankedCandidate`/`PriorContribution`/
 * `PhoneticLattice` are not on `:app`'s compile classpath and reaching them would mean widening
 * the module graph — which this prompt says to report rather than do. This mapper instead reads
 * the plain, lexicon-free `:data` entities [PhoneticLatticeEntity]/[CallsignCandidateEntity] that
 * build-plan P5 already put in the schema for exactly this purpose
 * (`CallsignCandidateEntity.priorBreakdown: Map<String, Double>?`). Nothing in this codebase
 * writes those tables yet — no prompt has wired Pass B's `PassBResult.lattice`/`.ranked` into
 * them (that would be a `:pipeline` change, out of this prompt's scope) — so today's real state
 * is the honest empty state this mapper produces for every transmission; the read path is built
 * and tested against the schema so it renders real data the moment a future session adds that
 * write.
 */
public data class PriorContributionViewState(
    val priorName: String,
    val logOdds: Double,
    /**
     * True exactly when [logOdds] is exactly zero. `:lexicon`'s own invariant
     * (`PriorContribution.coldStart`) is that cold start contributes **exactly** zero — never a
     * default, never a small value (FR-LEX-31) — so testing the persisted double against zero
     * recovers the same fact without needing the `:lexicon` type that first established it. A
     * prior that abstained and a prior that argued against are different facts and must not look
     * the same (this prompt's own wording) — abstention renders separately from a negative,
     * "argued against" contribution.
     */
    val isColdStart: Boolean,
)

public data class CandidateInspectionViewState(
    val callsign: String,
    val rank: Int,
    val score: Double,
    val grammarValid: Boolean,
    val databaseHit: Boolean,
    val selected: Boolean,
    val priorContributions: List<PriorContributionViewState>,
    /** R-187: `Detail-Ambiguous.dc.html`'s own evidence line ("... · Oregon") names the candidate's
     * real ITU allocation, not just whether it is a known station — carried through from
     * [org.ort.data.entity.CallsignCandidateEntity]'s own columns (already recorded for Tier B's
     * lexicon search; this mapper simply had not read them before this fix). */
    val ituPrefix: String? = null,
    val ituCountry: String? = null,
    /** Register R-320 (schema v5): the real [org.ort.data.entity.CallsignCandidateEntity.id] this
     * candidate's [org.ort.data.entity.LatticeSlotEntity] rows key off — carried through so
     * [DetailViewStateMapper.whyFor] can find the *winning* candidate's own slots without a second,
     * redundant lookup. Not itself shown on screen. */
    val id: String = "",
    /** Register R-320, `Detail-Why.dc.html` section 1: this candidate's own per-slot detail, in
     * lattice order — empty for a record `:data` recorded before schema v5 added
     * [org.ort.data.entity.LatticeSlotEntity], never a fabricated placeholder grid. */
    val slots: List<SlotDetailViewState> = emptyList(),
)

/**
 * Register R-320/R-182 (schema v5): one [org.ort.data.entity.LatticeSlotEntity], translated.
 * [belowThreshold] is [BELOW_THRESHOLD_SCORE]'s own disclosed policy cut (the board's own worked
 * example, `Detail-Why.dc.html`: `.64` reads amber, `.88`+ reads green) — no corpus-fitted
 * per-unit threshold exists anywhere in this codebase to read instead, the same honest-constant
 * pattern [org.ort.app.ui.data.CorrectionPolling]'s own `PRIOR_ADJUSTMENT_INCREMENT` already uses.
 */
public data class SlotDetailViewState(
    val unit: String,
    val score: Double,
    val keptAlternate: String?,
    val belowThreshold: Boolean,
)

public data class LatticeInspectionViewState(val source: String, val modelId: String?, val createdAt: Long)

public data class InspectionViewState(
    val lattice: LatticeInspectionViewState?,
    val candidates: List<CandidateInspectionViewState>,
) {
    public val isEmpty: Boolean get() = lattice == null && candidates.isEmpty()

    public companion object {
        public val EMPTY: InspectionViewState = InspectionViewState(lattice = null, candidates = emptyList())
    }
}

public object InspectionViewStateMapper {

    /** [SlotDetailViewState]'s own disclosed policy cut — see that class's doc comment. */
    private const val BELOW_THRESHOLD_SCORE = 0.7

    /**
     * [slots] is register R-320/R-182's own addition (schema v5) — defaulted to empty so every
     * call site built before it exists compiles unchanged; a caller that has looked up
     * [org.ort.data.dao.CatalogDao.slotDetailsFor] passes it through, grouped here by
     * [org.ort.data.entity.LatticeSlotEntity.candidateId] onto the matching
     * [CandidateInspectionViewState.slots] — never guessed from rank or position, since a
     * candidate's own real id is what the schema actually keys slots by.
     */
    public fun from(
        lattices: List<PhoneticLatticeEntity>,
        candidates: List<CallsignCandidateEntity>,
        slots: List<LatticeSlotEntity> = emptyList(),
    ): InspectionViewState {
        val slotsByCandidateId = slots.groupBy { it.candidateId }
        return InspectionViewState(
            lattice = lattices.maxByOrNull { it.createdAt }?.let { entity ->
                LatticeInspectionViewState(
                    source = entity.source.name,
                    modelId = entity.modelId,
                    createdAt = entity.createdAt,
                )
            },
            candidates = candidates.sortedBy { it.rank }.map { entity ->
                CandidateInspectionViewState(
                    callsign = entity.callsign,
                    rank = entity.rank,
                    score = entity.score,
                    grammarValid = entity.grammarValid,
                    databaseHit = entity.databaseHit,
                    selected = entity.selected,
                    priorContributions = (entity.priorBreakdown ?: emptyMap()).map { (name, logOdds) ->
                        PriorContributionViewState(priorName = name, logOdds = logOdds, isColdStart = logOdds == 0.0)
                    }.sortedBy { it.priorName },
                    ituPrefix = entity.ituPrefix,
                    ituCountry = entity.ituCountry,
                    id = entity.id,
                    slots = (slotsByCandidateId[entity.id] ?: emptyList()).sortedBy { it.index }.map { slot ->
                        SlotDetailViewState(
                            unit = slot.unit,
                            score = slot.score,
                            keptAlternate = slot.keptAlternate,
                            belowThreshold = slot.score < BELOW_THRESHOLD_SCORE,
                        )
                    },
                )
            },
        )
    }
}
