package org.ort.eval

import org.ort.testing.CorpusEntry
import org.ort.testing.CorpusManifest
import org.ort.testing.Fold

/**
 * Wires the harness to the shared corpus manifest (`:testing`, build-plan P2). The eval fold is
 * refused without an explicit opt-in (FR-TST-7 → AC-100) by [CorpusManifest.entries] itself —
 * this adds no bypass and no separate flag; it is the one sanctioned path to `eval`, and it is
 * still an explicit, single call site rather than something reachable by accident.
 */
public object ManifestHarness {
    public fun occurrencesForFold(
        manifest: CorpusManifest,
        fold: Fold,
        allowEval: Boolean = false,
        toOccurrences: (CorpusEntry) -> List<LabeledOccurrence>,
    ): List<LabeledOccurrence> = manifest.entries(fold, allowEval).flatMap(toOccurrences)
}
