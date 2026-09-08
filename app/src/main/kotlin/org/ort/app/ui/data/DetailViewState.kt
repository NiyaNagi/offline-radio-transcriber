package org.ort.app.ui.data

import org.ort.app.ui.components.PriorBarViewState
import org.ort.core.AttributionState

/**
 * ui-conformance WP6 (R-050/R-051/R-053/R-057), `Detail.dc.html`/`Detail-Confirmed.dc.html`/
 * `Detail-Ambiguous.dc.html`/`Detail-Unknown.dc.html`/`Detail-Why.dc.html`, design-guide.md §6.2:
 * the per-attribution-state body copy and the "why this callsign" surface, built **only** from the
 * real [TransmissionDetailViewState]/[InspectionViewState] `:app` already reads. Where the design
 * asks for something the schema does not carry today — a per-slot phonetic-lattice score
 * (`PhoneticLatticeEntity.unitsBlob` is an opaque blob with no defined per-unit shape yet; see
 * that entity's own doc comment), a "nearest voice match" distance for an UNKNOWN over, or thread
 * context — this mapper does not invent one (constitution I: "never fabricate a number"). Those
 * gaps are named in this package's CHANGELOG entry, not silently rendered as if they existed.
 *
 * **FR-UI-4, rewritten (this package's audit of `TransmissionDetailScreenTest`'s old assertion):**
 * the design's source of truth (`States.dc.html`, guide §6.2) shows the confidence
 * [org.ort.app.ui.components.ScoreChip] **only** on INFERRED — never a bare number on CONFIRMED —
 * but a confidence value is never *omitted* either: it renders as prose in the header's
 * explanation sentence for every state that carries one. That sentence is what [bodyFor] builds.
 */
public sealed interface DetailBodyViewState {
    public val explanation: String

    public data class Confirmed(override val explanation: String) : DetailBodyViewState

    /** R-053: [sourceTransmissionId] is the real `Attribution.sourceTransmissionId`, non-null only
     * when Pass B actually recorded one — `onOpenTransmission(sourceTransmissionId)` is the caller's job. */
    public data class Inferred(override val explanation: String, val sourceTransmissionId: String?) :
        DetailBodyViewState

    public data class Ambiguous(
        override val explanation: String,
        val alternateCallsign: String?,
        val candidates: List<AmbiguousCandidateViewState>,
    ) : DetailBodyViewState

    public data class Unknown(override val explanation: String, val tried: List<TriedStepViewState>) :
        DetailBodyViewState
}

/** One AMBIGUOUS chooser row (`Detail-Ambiguous.dc.html`). [evidence] is built from the real
 * [CandidateInspectionViewState.databaseHit] flag, never an invented "heard N times" count. */
public data class AmbiguousCandidateViewState(val callsign: String, val evidence: String, val scoreLabel: String)

/** One "what was tried" row (`Detail-Unknown.dc.html`). [resolved] marks the final, always-true
 * "kept as an unidentified voice" step distinctly from the attempts that did not resolve. */
public data class TriedStepViewState(val title: String, val detail: String?, val resolved: Boolean)

/** One ranked candidate row, reused by the inline preview and [org.ort.app.ui.screens.DetailWhyScreen]. */
public data class RankedCandidateViewState(val callsign: String, val scoreLabel: String, val chosen: Boolean)

/**
 * The "why this callsign" surface (R-051, FR-UI-8, `Detail-Why.dc.html`). [hasData] is false
 * exactly when [InspectionViewState.isEmpty] is — the honest "no resolver output recorded" case
 * [org.ort.app.ui.screens.TransmissionDetailScreen] already rendered before this package, kept
 * unchanged. [latticeSummary] is source + model only, never a fabricated per-slot render — see
 * this file's class doc.
 */
public data class DetailWhyViewState(
    val hasData: Boolean,
    val latticeSummary: String?,
    val candidates: List<RankedCandidateViewState>,
    val priors: List<PriorBarViewState>,
    val runnerUp: RankedCandidateViewState?,
)

public data class DetailViewState(
    val detail: TransmissionDetailViewState,
    val body: DetailBodyViewState,
    val why: DetailWhyViewState,
)

public object DetailViewStateMapper {

    /** The magnitude a prior's `logOdds` reaches a full bar at — `Detail.dc.html`'s "heard
     * acoustically" prior (the strongest one on every board this mapper was built against) sits at
     * +3.1/+3.8, so 4.0 leaves headroom without ever clipping a real value silently at 1.0. */
    private const val PRIOR_BAR_SCALE = 4.0

    public fun from(detail: TransmissionDetailViewState): DetailViewState = DetailViewState(
        detail = detail,
        body = bodyFor(detail),
        why = whyFor(detail.inspection),
    )

    private fun bodyFor(detail: TransmissionDetailViewState): DetailBodyViewState {
        val attribution = detail.attribution
        return when (attribution.state) {
            AttributionState.CONFIRMED -> DetailBodyViewState.Confirmed(
                explanation = "Heard in this over. Resolved from the phonetics at %.2f.".format(
                    attribution.confidence ?: 0.0,
                ),
            )

            AttributionState.INFERRED -> {
                val sourceId = attribution.sourceTransmissionId?.toString()
                val confidenceClause = attribution.confidence?.let { " Confidence %.2f.".format(it) }.orEmpty()
                val explanation = if (sourceId != null) {
                    "Not heard in this over. Matched by voice to the source over, where the callsign was " +
                        "heard clearly.$confidenceClause"
                } else {
                    "Not heard in this over. Matched by voice.$confidenceClause"
                }
                DetailBodyViewState.Inferred(explanation = explanation, sourceTransmissionId = sourceId)
            }

            AttributionState.AMBIGUOUS -> ambiguousBody(detail.inspection)

            AttributionState.UNKNOWN -> DetailBodyViewState.Unknown(
                explanation = "No callsign heard, and the voice matched no one heard before. Nothing is claimed.",
                tried = triedStepsFor(detail.inspection),
            )
        }
    }

    private fun ambiguousBody(inspection: InspectionViewState): DetailBodyViewState.Ambiguous {
        val topTwo = inspection.candidates.sortedBy { it.rank }.take(2)
        val explanation = if (topTwo.size >= 2) {
            "Two candidates survived and the phonetics do not separate them: " +
                "${topTwo[0].callsign} or ${topTwo[1].callsign}."
        } else {
            "Two or more candidates remain within the separation threshold. The system will not choose."
        }
        return DetailBodyViewState.Ambiguous(
            explanation = explanation,
            alternateCallsign = topTwo.getOrNull(1)?.callsign,
            candidates = topTwo.map { candidate ->
                AmbiguousCandidateViewState(
                    callsign = candidate.callsign,
                    evidence = if (candidate.databaseHit) "a known station" else "never heard before",
                    scoreLabel = "%.2f".format(candidate.score),
                )
            },
        )
    }

    /**
     * `Detail-Unknown.dc.html`'s "what was tried". Only the grammar step and the always-true
     * "kept as an unidentified voice" step are built from data this mapper actually has — a
     * "nearest voice match" and "thread context" step are named in the artboard but no real figure
     * for either reaches [TransmissionDetailViewState] yet (see this file's class doc); adding them
     * honestly needs a `:data` read this package does not own.
     */
    private fun triedStepsFor(inspection: InspectionViewState): List<TriedStepViewState> {
        val best = inspection.candidates.maxByOrNull { it.score }
        val grammarStep = if (best != null) {
            TriedStepViewState(
                title = "Callsign grammar over the phonetic lattice",
                detail = "Best partial ${best.callsign} at %.2f, below the floor.".format(best.score),
                resolved = false,
            )
        } else {
            TriedStepViewState(
                title = "Callsign grammar over the phonetic lattice",
                detail = "No sequence parsed as a valid callsign.",
                resolved = false,
            )
        }
        val keptStep = TriedStepViewState(
            title = "Kept as an unidentified voice",
            detail = "If this voice is heard again with a callsign, this over will be re-attributed by " +
                "inference and marked as such.",
            resolved = true,
        )
        return listOf(grammarStep, keptStep)
    }

    private fun whyFor(inspection: InspectionViewState): DetailWhyViewState {
        if (inspection.isEmpty) {
            return DetailWhyViewState(
                hasData = false,
                latticeSummary = null,
                candidates = emptyList(),
                priors = emptyList(),
                runnerUp = null,
            )
        }
        val ranked = inspection.candidates.sortedBy { it.rank }
        val chosen = ranked.firstOrNull { it.selected } ?: ranked.firstOrNull()
        val runnerUp = ranked.firstOrNull { it !== chosen }
        return DetailWhyViewState(
            hasData = true,
            latticeSummary = inspection.lattice?.let { lattice ->
                lattice.modelId?.let { "${lattice.source} · model $it" } ?: lattice.source
            },
            candidates = ranked.map { candidate ->
                RankedCandidateViewState(
                    callsign = candidate.callsign,
                    scoreLabel = "score %.1f".format(candidate.score),
                    chosen = candidate.selected,
                )
            },
            priors = (chosen?.priorContributions ?: emptyList()).map(::priorBar),
            runnerUp = runnerUp?.let {
                RankedCandidateViewState(
                    callsign = it.callsign,
                    scoreLabel = "score %.1f".format(it.score),
                    chosen = false,
                )
            },
        )
    }

    /**
     * FR-LEX-31/constitution I: a cold-start prior contributes **exactly** zero and gets no bar at
     * all (`fillFraction = null` renders "cold start", per [PriorBarViewState]'s own contract) —
     * never a zero-length bar standing in for "no data yet". A prior that argued against fills from
     * the opposite side; [PRIOR_BAR_SCALE] only ever *scales* the real `logOdds`, never invents one.
     */
    private fun priorBar(prior: PriorContributionViewState): PriorBarViewState = if (prior.isColdStart) {
        PriorBarViewState(name = prior.priorName, fillFraction = null, valueLabel = null, arguedAgainst = false)
    } else {
        val fraction = (kotlin.math.abs(prior.logOdds) / PRIOR_BAR_SCALE).coerceIn(0.0, 1.0).toFloat()
        PriorBarViewState(
            name = prior.priorName,
            fillFraction = fraction,
            valueLabel = "%+.1f".format(prior.logOdds),
            arguedAgainst = prior.logOdds < 0.0,
        )
    }
}
