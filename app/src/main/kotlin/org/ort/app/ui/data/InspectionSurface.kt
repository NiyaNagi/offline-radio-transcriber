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
    /**
     * Register R-1148 (split from R-1144/R-1117; FR-UI-8, constitution I): the slots, if any,
     * where *this specific candidate's own path* genuinely differed from what the lattice
     * recorded — never every slot, only the real differences, in lattice order. Empty either
     * because this candidate's own path agreed with the lattice at every recorded slot (nothing to
     * explain) or because no per-candidate alignment was recorded for this row at all (a record
     * from before schema v17); the two look identical here on purpose — neither honestly supports
     * a "why" sentence (see [org.ort.data.entity.LatticeSlotEntity.offeredByLattice]'s own doc
     * comment for how those two cases are told apart at the row level).
     */
    val slotMismatches: List<SlotMismatchViewState> = emptyList(),
)

/**
 * Register R-1148: one real difference between this candidate's own winning path and what the
 * lattice recorded at [slotIndex] — never fabricated, never "heard weakly"/"heard strongly"
 * (constitution I; register R-1121: [org.ort.lexicon.LatticeSlot.alts]' scores are text-derived
 * placeholders, not real acoustic confidence). [candidateUnit] is `null` exactly when this
 * candidate's own path deleted this slot. [offeredByLattice] is `true` iff [candidateUnit] was one
 * of this slot's own alternatives — the lattice's top pick or a kept alternate — genuinely present
 * rather than a confusion-matrix substitution the lattice never listed at all; always `false` for a
 * `null` [candidateUnit].
 */
public data class SlotMismatchViewState(
    val slotIndex: Int,
    val latticeUnit: String,
    val candidateUnit: String?,
    val offeredByLattice: Boolean,
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
                    priorContributions = entity.priorBreakdown.orEmpty().map { (name, logOdds) ->
                        PriorContributionViewState(priorName = name, logOdds = logOdds, isColdStart = logOdds == 0.0)
                    }.sortedBy { it.priorName },
                    ituPrefix = entity.ituPrefix,
                    ituCountry = entity.ituCountry,
                    id = entity.id,
                    slots = slotsByCandidateId[entity.id].orEmpty().sortedBy { it.index }.map { slot ->
                        SlotDetailViewState(
                            unit = slot.unit,
                            score = slot.score,
                            keptAlternate = slot.keptAlternate,
                            belowThreshold = slot.score < BELOW_THRESHOLD_SCORE,
                        )
                    },
                    // Register R-1148: `offeredByLattice != null` is the recorded/not-recorded
                    // marker (see LatticeSlotEntity.offeredByLattice's own doc comment); among
                    // recorded rows, only a genuine difference from the lattice's own top pick is
                    // a mismatch worth keeping -- an agreeing slot has nothing to explain.
                    slotMismatches = slotsByCandidateId[entity.id].orEmpty()
                        .filter { it.offeredByLattice != null && it.candidateUnit != it.unit }
                        .sortedBy { it.index }
                        .map { slot ->
                            SlotMismatchViewState(
                                slotIndex = slot.index,
                                latticeUnit = slot.unit,
                                candidateUnit = slot.candidateUnit,
                                offeredByLattice = slot.offeredByLattice == true,
                            )
                        },
                )
            },
        )
    }
}
