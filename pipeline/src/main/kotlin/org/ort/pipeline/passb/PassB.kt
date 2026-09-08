package org.ort.pipeline.passb

import org.ort.asrapi.DecodeOptions
import org.ort.asrapi.PassBOutcome
import org.ort.asrapi.RejectionPipeline
import org.ort.asrapi.SegmentCandidate
import org.ort.core.Attribution
import org.ort.core.PassFingerprint
import org.ort.core.TransmissionState
import org.ort.data.PassRunOutcome
import org.ort.data.entity.WorkQueueItemEntity
import org.ort.lexicon.CallsignGrammar
import org.ort.lexicon.PhoneticLattice
import org.ort.lexicon.PriorCombiner
import org.ort.lexicon.RankedCandidate
import org.ort.lexicon.RankingContext
import org.ort.lexicon.TextDerivedLatticeBuilder
import org.ort.lexicon.VariantTable
import org.ort.pipeline.Pass
import org.ort.pipeline.diagnostics.DiagnosticsLog

/** A closed segment's audio and the pre-decode facts Pass B's cheap rejection rules need. */
public data class SegmentAudio(val samples: FloatArray, val candidate: SegmentCandidate)

/**
 * Loads a leased [WorkQueueItemEntity]'s segment audio (build-plan P11 owns this seam, not the
 * retained-audio decode path itself — `:pipeline` already depends on `:capture-android`, but
 * wiring a real [SegmentAudioProvider] to [org.ort.capture.android.codec.FlacStore]'s decoder is
 * left to the session that has a device to verify it against; see CHANGELOG).
 */
public fun interface SegmentAudioProvider {
    public suspend fun forItem(item: WorkQueueItemEntity): SegmentAudio
}

/**
 * Everything one Pass B run produced, for whatever renders an attribution (build-plan P11: "this
 * is the first point in the project where an attribution reaches a screen"). [lattice] and
 * [ranked] are `null`/empty exactly when [outcome] is not [PassBOutcome.Accepted] or produced no
 * parseable candidates — never silently dropped, always inspectable (constitution I, FR-UI-8).
 */
public data class PassBResult(
    val transmissionId: String,
    val outcome: PassBOutcome,
    val lattice: PhoneticLattice?,
    val ranked: List<RankedCandidate>,
    val attribution: Attribution,
    val fingerprint: PassFingerprint,
)

/** Where a [PassBResult] goes. `:data` gaining a write path for attribution fields is a follow-up
 * (see CHANGELOG "left open") — this seam lets `:pipeline` be correct now and pluggable later. */
public fun interface PassBResultSink {
    public suspend fun record(result: PassBResult)
}

/**
 * The real Pass B (build-plan P11, M3): capture's closed segment → the six-control rejection
 * pipeline (`:asr-api`, P10) → a text-derived [PhoneticLattice] (technical design §9.2, FR-LEX-6)
 * → the callsign grammar (`:lexicon`, P3) → prior ranking (P7) → [CallsignResolver] → an
 * [Attribution]. One real [Pass], carrying its [fingerprint] end to end (constitution III: "every
 * pass is a pure function ... and records the fingerprint of what produced it").
 *
 * **Text-to-lattice never aborts on an unrecognised word** (unlike
 * [org.ort.lexicon.TextDerivedLatticeBuilder.build], which throws on the first one): a real Pass B
 * transcript of ordinary speech contains many words that are not phonetic spellings, and Pass B
 * must not crash on "the weather here is clear" the way it would reject an out-of-vocabulary NATO
 * word inside a deliberately spelled callsign. [resolveFromText] uses
 * [org.ort.lexicon.TextDerivedLatticeBuilder.buildAnchored] instead (R-182/FR-UI-4): only tokens
 * the [VariantTable] recognises become lattice slots, each carrying its real transcript character
 * span; an unrecognised token is silently excluded rather than aborting the whole transcript.
 * Spotting a callsign embedded in continuous speech — as opposed to a transcript that is *only*
 * the spelled callsign — is exactly the acoustic spotting problem M4 (`UnitSpotter`) exists to
 * solve properly; this is T0's necessarily degraded approximation (FR-LEX-6).
 */
/**
 * The resolution-side collaborators [PassB] composes, grouped so the constructor stays under
 * the parameter-count lint threshold without hiding any of them — every field here is still a
 * named, independently testable collaborator, just grouped by role (decode vs. resolve).
 */
public data class PassBResolutionChain(
    val variants: VariantTable,
    val grammar: CallsignGrammar,
    val combiner: PriorCombiner,
    val resolver: CallsignResolver,
)

public class PassB(
    private val audioProvider: SegmentAudioProvider,
    private val rejectionPipeline: RejectionPipeline,
    private val resolution: PassBResolutionChain,
    /**
     * Public (audit F-013, constitution I: "every machine conclusion MUST be inspectable") so a
     * caller — [PassBFactory]'s own tests included — can confirm what this instance was actually
     * built with, rather than trusting an assembly step no one can see.
     */
    public val fingerprint: PassFingerprint,
    private val sink: PassBResultSink,
    private val contextFor: (WorkQueueItemEntity) -> RankingContext = { RankingContext() },
    private val decodeOptions: DecodeOptions = DecodeOptions(),
) : Pass {

    override suspend fun run(item: WorkQueueItemEntity): PassRunOutcome {
        val segment = audioProvider.forItem(item)
        val outcome = rejectionPipeline.process(segment.candidate, segment.samples, decodeOptions)

        val (lattice, ranked, attribution) = when (outcome) {
            is PassBOutcome.Accepted -> resolveFromText(outcome.result.text, contextFor(item))
            is PassBOutcome.Rejected, is PassBOutcome.Failed -> Triple(null, emptyList(), Attribution.unknown())
        }

        sink.record(PassBResult(item.transmissionId, outcome, lattice, ranked, attribution, fingerprint))

        return when (outcome) {
            is PassBOutcome.Accepted -> PassRunOutcome.Finished(TransmissionState.COMPLETE)
            is PassBOutcome.Rejected -> {
                // FR-OBS-1 "rejection reasons by count": the closed RejectionRuleId only -- never
                // outcome.detail, which the rule itself may compose from the segment's own text.
                DiagnosticsLog.logRejection(outcome.rule)
                PassRunOutcome.Finished(TransmissionState.REJECTED)
            }
            is PassBOutcome.Failed -> PassRunOutcome.Errored(outcome.reason)
        }
    }

    /**
     * R-182/FR-UI-4: built with [TextDerivedLatticeBuilder.buildAnchored] rather than the plain
     * [PhoneticLattice.ofUnits] this used before — `buildAnchored` records each kept unit's real
     * `[charStart, charEnd)` offset into [text] on its `LatticeSlot`, which `CallsignGrammar.parse`
     * then carries into every emitted `CallsignCandidate.slotDetails` automatically (that class's
     * own kdoc: "the only real producer, and always fills it") — no further change needed here for
     * `slotDetails` to reach [resolution]'s ranking or [sink] unchanged, since [RankedCandidate]
     * wraps the same `CallsignCandidate` instance [combiner] ranks, never copying it without its
     * `slotDetails`. `buildAnchored` keeps [PassB]'s existing "an unrecognised token is silently
     * excluded, never aborts the whole transcript" behaviour itself (see this class's own kdoc) —
     * the manual split/`mapNotNull` this replaced only ever duplicated that same rule less exactly.
     *
     * Never confused with an audio-derived (Pass C) lattice: [TextDerivedLatticeBuilder] always
     * tags its output `LatticeSource.TEXT_DERIVED`, and only *this* code path ever calls
     * `buildAnchored` — an acoustic lattice's slots keep `charStart`/`charEnd` `null` (constitution
     * I: never fabricate a transcript-text relationship a text-derived lattice alone can offer).
     */
    private fun resolveFromText(
        text: String,
        context: RankingContext,
    ): Triple<PhoneticLattice?, List<RankedCandidate>, Attribution> {
        val lattice = TextDerivedLatticeBuilder(resolution.variants).buildAnchored(text)
        if (lattice.isEmpty) return Triple(null, emptyList(), Attribution.unknown())

        val candidates = resolution.grammar.parse(lattice)
        if (candidates.isEmpty()) return Triple(lattice, emptyList(), Attribution.unknown())

        val ranked = resolution.combiner.rank(candidates, context)
        return Triple(lattice, ranked, resolution.resolver.resolve(ranked))
    }
}
