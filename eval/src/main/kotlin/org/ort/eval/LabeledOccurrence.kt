package org.ort.eval

import org.ort.lexicon.PhoneticLattice
import org.ort.lexicon.RankingContext

/**
 * One hand-labelled callsign occurrence for the harness (FR-TST-8): a [PhoneticLattice] already
 * built — acoustic (Pass C, M4) or text-derived (Pass B, T0/FR-LEX-6) — the [RankingContext]
 * that applied at the moment it was heard, and the ground truth. `:eval` v1 composes the pure
 * JVM pipeline from `:lexicon` onward; it does not run ASR (`:asr-*`, build-plan P10) or capture
 * (`:capture-*`, P4/P8), which have not been built yet, so occurrences arrive with their
 * lattice already formed rather than as raw audio.
 *
 * [truthCallsign] is `null` for a negative example — audio with no callsign present — used to
 * measure false positives (FR-TST-8's rejection-rate and attribution-accuracy metrics).
 */
public data class LabeledOccurrence(
    val id: String,
    val source: String,
    val lattice: PhoneticLattice,
    val context: RankingContext,
    val truthCallsign: String?,
)
