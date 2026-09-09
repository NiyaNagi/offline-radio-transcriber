package org.ort.app.ui.data

import org.ort.app.ui.components.PriorBarViewState
import org.ort.core.AttributionState
import org.ort.core.PassId

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
 *
 * **Register R-423 (design), narrower than the note above.** `Detail.dc.html` itself (INFERRED's
 * own real board) carries **no** "Confidence 0.82." clause in its explanation sentence at all —
 * confirmed by reading the board's own markup directly — because the chip already shows that
 * number beside the callsign; repeating it in prose was a duplicate this package had not checked
 * the board closely enough to catch. [bodyFor]'s INFERRED branch no longer appends it. CONFIRMED's
 * own explanation (no chip on that state, per the same board file) is unchanged by this — the
 * paragraph above still governs it.
 */
public sealed interface DetailBodyViewState {
    public val explanation: String

    public data class Confirmed(override val explanation: String) : DetailBodyViewState

    /**
     * R-053: [sourceTransmissionId] is the real `Attribution.sourceTransmissionId`, non-null only
     * when Pass B actually recorded one — `onOpenTransmission(sourceTransmissionId)` is the
     * caller's job. Register R-423 (design): [sourceOverTimeLabel] (`Detail.dc.html`'s own
     * "02:14:07") is carried through *structured*, not just baked into [explanation]'s prose, so
     * [org.ort.app.ui.screens.TransmissionDetailScreen] can make that timestamp itself the tappable
     * link the board draws it as — a separate `Open the source over` line beneath the sentence is
     * not what the board shows. `null` when no source id is known yet, the same case [explanation]
     * already falls back to its generic "Matched by voice." wording for.
     */
    public data class Inferred(
        override val explanation: String,
        val sourceTransmissionId: String?,
        val sourceOverTimeLabel: String? = null,
    ) : DetailBodyViewState

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
 *
 * Register R-320 (schema v5): [winningSlots] is the *winning* candidate's own real per-slot detail
 * ([SlotDetailViewState] — unit, score, kept alternate) — empty for a record from before schema v5
 * added [org.ort.data.entity.LatticeSlotEntity], never a fabricated placeholder grid.
 * [winningGrammarValid] is the winning candidate's own real
 * [CandidateInspectionViewState.grammarValid] boolean — `null` exactly when there is no winning
 * candidate to read it from ([hasData] is false). Deliberately **not** the board's own
 * "prefix K · region 7 · suffix LWH" per-component parse: no API this package can reach returns
 * that breakdown, only the pass/fail bit `CallsignCandidateEntity.grammarValid` already recorded —
 * shown honestly as that bit alone, never an invented component split.
 */
public data class DetailWhyViewState(
    val hasData: Boolean,
    val latticeSummary: String?,
    val candidates: List<RankedCandidateViewState>,
    val priors: List<PriorBarViewState>,
    val runnerUp: RankedCandidateViewState?,
    val winningSlots: List<SlotDetailViewState> = emptyList(),
    val winningGrammarValid: Boolean? = null,
)

/**
 * R-426: one real attempt at a failed pass — `:data` schema v6's
 * [org.ort.data.entity.WorkAttemptEntity], oldest first, exactly the order `Fail-Pass.dc.html`
 * lists them in. [timeLabel] is the attempt's own real `finishedAtMillis` (when the error or
 * timeout was recorded, not when the attempt started); [reasonLabel] is the real
 * `WorkAttemptEntity.reason`, humanized ([org.ort.data.entity.WorkAttemptOutcome.TIMEOUT]'s own
 * stored reason is always the literal `"timeout"` — the only value
 * [org.ort.data.WorkQueue.runLeased]'s timeout path ever passes — so this reads "Timed out"
 * rather than repeating the raw token; a `FAILED` outcome's real message is title-cased the same
 * way this package's other error text already is).
 */
public data class PassAttemptViewState(val timeLabel: String, val reasonLabel: String)

/**
 * R-153, F18 `Fail-Pass.dc.html`, FR-RUN-9: a pass that errored repeatedly and stopped retrying
 * automatically — real per-transmission facts read from `:data`'s
 * [org.ort.data.entity.WorkQueueItemEntity] (`state = FAILED`) by
 * [org.ort.app.ui.data.CorrectionPolling.passFailure], never fabricated. [passId]/[passLabel] name
 * which pass failed in operator terms (guide §9's "pass" vocabulary — "Pass B", not `B_OFFLINE`);
 * [attempts] and [lastError] are the real `attemptCount`/`lastError` columns.
 *
 * **R-426, schema gap closed (round 12):** `:data` schema v6 added
 * [org.ort.data.entity.WorkAttemptEntity], one durable row per failed attempt, so [attemptLog]
 * now carries the real per-attempt timestamp/reason history `Fail-Pass.dc.html` draws — round 11's
 * single fallback line is kept only for the one case schema v6 cannot retroactively back: a
 * `FAILED` item whose own attempts predate this table (no `work_attempt` rows exist for it, so
 * [attemptLog] is honestly empty and [org.ort.app.ui.screens.TransmissionDetailScreen] falls back
 * to [lastError] alone, never a fabricated per-attempt list for a record that has none).
 *
 * **R-470 (design, round 13):** [sessionFailedCount] is the board's own closing "Counted in
 * tonight's health: N failed." paragraph — the real count of `FAILED` transmissions in this over's
 * own session, the same fact [org.ort.app.ui.data.ReaderPolling.captureStatus]'s own `failedCount`
 * already counts for `Capture-Status.dc.html`, never a fabricated "since install" or global total.
 */
public data class PassFailureViewState(
    val passId: PassId,
    val passLabel: String,
    val lastError: String,
    val attempts: Int,
    val attemptLog: List<PassAttemptViewState> = emptyList(),
    val sessionFailedCount: Int = 0,
)

/**
 * R-242 (register), F04 `Fail-Hallucination.dc.html`, FR-ASR-5: a rejected over, opened from a
 * `Log-Rejected.dc.html` row (WP5's own why-line/DUR wiring; this package owns only the detail
 * state itself). [reason] is the real `transmission.rejectionReason` column — the board's own
 * "4 of 6 controls fired" breakdown (a known-hallucination-phrase match, a no-speech-probability
 * figure, an energy-profile read, words-per-second, repeated-text and compression-ratio checks)
 * needs data no `:data` table records today beyond [reason] itself and, sometimes,
 * [org.ort.data.entity.TranscriptEntity.noSpeechProb] — not surfaced here, since one real number
 * out of six named checks would misrepresent a control panel that mostly does not exist yet
 * (constitution I). No `restore`/re-queue action either: "It was speech — restore" would need a
 * `:pipeline` re-run of Pass B with its phrase filter disabled, a write path this package has no
 * access to build honestly.
 */
public data class RejectedViewState(val reason: String?)

public data class DetailViewState(
    val detail: TransmissionDetailViewState,
    val body: DetailBodyViewState,
    val why: DetailWhyViewState,
    /** R-153: non-null exactly when [org.ort.app.ui.data.CorrectionPolling.passFailure] found a
     * real terminally-FAILED [org.ort.data.entity.WorkQueueItemEntity] for this transmission. */
    val passFailure: PassFailureViewState? = null,
    /** R-242: non-null exactly when the polled `processingState` is `REJECTED`. */
    val rejected: RejectedViewState? = null,
    /** R-188: the current transcript's real recorded confidence
     * ([org.ort.app.ui.data.CorrectionPolling.currentTranscriptConfidence]) — `null` for no current
     * transcript row, or one that recorded no confidence; never a fabricated number. */
    val transcriptConfidence: Double? = null,
    /** Register R-182 (schema v5): the winning candidate's real `[start, end)` character span into
     * the transcript ([org.ort.app.ui.data.CorrectionPolling.winningCharSpan]) — `null` when no
     * winning candidate has one (no candidate, or a lattice that was not text-anchored). */
    val transcriptCharSpan: IntRange? = null,
)

public object DetailViewStateMapper {

    /** The magnitude a prior's `logOdds` reaches a full bar at — `Detail.dc.html`'s "heard
     * acoustically" prior (the strongest one on every board this mapper was built against) sits at
     * +3.1/+3.8, so 4.0 leaves headroom without ever clipping a real value silently at 1.0. */
    private const val PRIOR_BAR_SCALE = 4.0

    /**
     * [passFailure] is optional and defaults to `null` so every existing call site (built before
     * R-153) compiles unchanged; a caller that has looked one up (`:app`'s
     * [org.ort.app.ui.screens.TransmissionDetailContent], gated on `processingState == FAILED`)
     * passes it through. [sourceOverTimeLabel] is R-183's own fix, the same way — `Detail.dc.html`'s
     * INFERRED explanation names the source over's real time ("to 02:14:07, where the callsign was
     * heard clearly"), which needs a second `:data` read this pure mapper cannot make itself; a
     * caller that has looked it up ([org.ort.app.ui.data.CorrectionPolling.sourceOverTimeLabel])
     * passes it through, and its absence (still possible — a source id [TransmissionId.parse] cannot
     * resolve, or a caller that has not looked it up yet) falls back to the honest generic phrasing
     * rather than fabricating a time.
     */
    public fun from(
        detail: TransmissionDetailViewState,
        passFailure: PassFailureViewState? = null,
        sourceOverTimeLabel: String? = null,
        transcriptConfidence: Double? = null,
        // R-242: mirrors [passFailure] exactly — optional, defaults to `null` so every existing
        // call site compiles unchanged, populated by a caller that already knows the polled
        // `processingState` is `REJECTED` ([org.ort.app.ui.screens.TransmissionDetailContent]).
        rejected: RejectedViewState? = null,
        transcriptCharSpan: IntRange? = null,
        // Register R-425: real, per-candidate evidence for the AMBIGUOUS chooser
        // ([org.ort.app.ui.data.CorrectionPolling.ambiguousCandidateEvidence]) — defaulted to an
        // empty map so every existing call site compiles unchanged; a caller with no entry for a
        // given callsign falls back to [ambiguousEvidence]'s own older, coarser evidence line.
        ambiguousEvidence: Map<String, CorrectionPolling.AmbiguousCandidateEvidenceViewState> = emptyMap(),
    ): DetailViewState = DetailViewState(
        detail = detail,
        body = bodyFor(detail, sourceOverTimeLabel, ambiguousEvidence),
        why = whyFor(detail.inspection),
        passFailure = passFailure,
        rejected = rejected,
        transcriptConfidence = transcriptConfidence,
        transcriptCharSpan = transcriptCharSpan,
    )

    private fun bodyFor(
        detail: TransmissionDetailViewState,
        sourceOverTimeLabel: String? = null,
        ambiguousEvidence: Map<String, CorrectionPolling.AmbiguousCandidateEvidenceViewState> = emptyMap(),
    ): DetailBodyViewState {
        val attribution = detail.attribution
        return when (attribution.state) {
            AttributionState.CONFIRMED -> DetailBodyViewState.Confirmed(
                explanation = "Heard in this over. Resolved from the phonetics at %.2f.".format(
                    attribution.confidence ?: 0.0,
                ),
            )

            AttributionState.INFERRED -> {
                val sourceId = attribution.sourceTransmissionId?.toString()
                // R-183: names the source over's real time when it is known, `Detail.dc.html`'s own
                // wording ("to 02:14:07, where the callsign was heard clearly") — the honest generic
                // fallback ("to the source over") is kept for a source id whose time this mapper's
                // caller has not (yet, or cannot) resolved, never a fabricated time.
                // R-423: no "Confidence 0.82." clause — `Detail.dc.html` carries no such sentence
                // (confirmed by reading its own markup); the chip beside the callsign already shows
                // that number (FR-UI-4).
                val explanation = if (sourceId != null) {
                    val whereClause = sourceOverTimeLabel?.let { "to $it" } ?: "to the source over"
                    "Not heard in this over. Matched by voice $whereClause, where the callsign was " +
                        "heard clearly."
                } else {
                    "Not heard in this over. Matched by voice."
                }
                DetailBodyViewState.Inferred(
                    explanation = explanation,
                    sourceTransmissionId = sourceId,
                    sourceOverTimeLabel = sourceId?.let { sourceOverTimeLabel },
                )
            }

            AttributionState.AMBIGUOUS -> ambiguousBody(detail.inspection, ambiguousEvidence)

            AttributionState.UNKNOWN -> DetailBodyViewState.Unknown(
                explanation = "No callsign heard, and the voice matched no one heard before. Nothing is claimed.",
                tried = triedStepsFor(detail.inspection),
            )
        }
    }

    private fun ambiguousBody(
        inspection: InspectionViewState,
        evidenceByCallsign: Map<String, CorrectionPolling.AmbiguousCandidateEvidenceViewState>,
    ): DetailBodyViewState.Ambiguous {
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
                    evidence = ambiguousEvidence(candidate, evidenceByCallsign[candidate.callsign]),
                    scoreLabel = "%.2f".format(candidate.score),
                )
            },
        )
    }

    /**
     * Register R-425, `Detail-Ambiguous.dc.html`: real, *distinct* per-candidate evidence — before
     * this fix, every candidate with a real ITU country (the common case for two candidates one
     * edit apart) read the identical "a known station · United States", regardless of which one
     * this device had actually heard before. [real], when the caller has looked it up
     * ([org.ort.app.ui.data.CorrectionPolling.ambiguousCandidateEvidence]), replaces that with the
     * real heard-count/last-heard/voice-match facts; `null` (a caller that has not looked it up, or
     * built before this fix) falls back to the older, coarser known/unknown-plus-country line —
     * still real, just less specific, never fabricated either way.
     */
    private fun ambiguousEvidence(
        candidate: CandidateInspectionViewState,
        real: CorrectionPolling.AmbiguousCandidateEvidenceViewState?,
    ): String {
        if (real == null) {
            val known = if (candidate.databaseHit) "a known station" else "never heard before"
            return candidate.ituCountry?.let { "$known · $it" } ?: known
        }
        val heardClause = when {
            real.heardCount == 0 -> "never heard before"
            real.heardCount == 1 -> "heard once"
            else -> "heard ${real.heardCount} times"
        }
        val lastHeardClause = real.lastHeardLabel?.let { ", last $it" }.orEmpty()
        val voiceClause = if (real.voiceOnFile) " · voice match" else ""
        return "$heardClause$lastHeardClause$voiceClause"
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
            winningSlots = chosen?.slots ?: emptyList(),
            winningGrammarValid = chosen?.grammarValid,
        )
    }

    /**
     * FR-LEX-31/constitution I: a cold-start prior contributes **exactly** zero and gets no bar at
     * all (`fillFraction = null` renders "cold start", per [PriorBarViewState]'s own contract) —
     * never a zero-length bar standing in for "no data yet". A prior that argued against fills from
     * the opposite side; [PRIOR_BAR_SCALE] only ever *scales* the real `logOdds`, never invents one.
     */
    private fun priorBar(prior: PriorContributionViewState): PriorBarViewState = if (prior.isColdStart) {
        PriorBarViewState(
            name = priorLabel(prior.priorName),
            fillFraction = null,
            valueLabel = null,
            arguedAgainst = false,
        )
    } else {
        val fraction = (kotlin.math.abs(prior.logOdds) / PRIOR_BAR_SCALE).coerceIn(0.0, 1.0).toFloat()
        PriorBarViewState(
            name = priorLabel(prior.priorName),
            fillFraction = fraction,
            valueLabel = "%+.1f".format(prior.logOdds),
            arguedAgainst = prior.logOdds < 0.0,
        )
    }

    /**
     * R-180: `Detail-Why.dc.html`'s prior rows read as guide §9 prose ("On this repeater", "In the
     * FCC database") — [CallsignCandidateEntity.priorBreakdown]'s real keys (confirmed directly in
     * `OvernightScenario.kt`, the fixture the validator's screenshot came from: `"callsign-history"`,
     * `"database"`, `"propagation"`) are kebab/snake identifiers, not prose, and were rendered
     * verbatim before this fix. [PRIOR_LABELS] translates the keys this codebase has real evidence
     * for — [org.ort.app.ui.data.CorrectionPolling]'s own two named priors
     * (`on_this_repeater`/`recent_corrections`) use the artboard's exact wording; `database` is the
     * artboard's own "In the FCC database" for the same signal. A key with no curated entry (a
     * resolver signal this mapper cannot cross-reference to a specific board phrase) still reads as
     * real, sentence-case prose — its own name, dashes/underscores replaced with spaces, first letter
     * capitalised — never the raw key, and never an invented meaning for a key this mapper cannot
     * verify.
     */
    private val PRIOR_LABELS: Map<String, String> = mapOf(
        "on_this_repeater" to "On this repeater",
        "recent_corrections" to "Recent corrections",
        "database" to "In the FCC database",
        "heard_this_session" to "Heard this session",
        "heard_acoustically" to "Heard acoustically",
        "time_of_day" to "Time of day",
    )

    private fun priorLabel(key: String): String {
        val normalized = key.replace('-', '_').replace(' ', '_').lowercase(java.util.Locale.ROOT)
        return PRIOR_LABELS[normalized] ?: key
            .replace('_', ' ')
            .replace('-', ' ')
            .replaceFirstChar { it.uppercaseChar() }
    }
}
