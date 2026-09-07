package org.ort.eval

import org.ort.lexicon.CallsignGrammar
import org.ort.lexicon.ConfusionCostMatrix
import org.ort.lexicon.ItuPrefixTable
import org.ort.lexicon.PriorCombiner
import org.ort.lexicon.PropagationModel
import org.ort.lexicon.defaultPriors
import org.ort.testing.CorpusManifest
import org.ort.testing.Fold
import java.io.File

/**
 * `:eval`'s JVM entry point (technical design §17): a corpus manifest in, a machine-readable
 * report out. Takes the manifest path as its first argument and reports on the `dev` fold.
 *
 * **This does not yet compute a real number.** A [LabeledOccurrence] needs a hand-labelled
 * callsign occurrence — spoken tokens or acoustic units aligned to a ground-truth callsign,
 * with the ranking context that applied at the time. That labelling protocol is build-plan
 * P2/P6's "yours, not a session" item (Q16, spec/build-plan.md S1.8) and has not been produced
 * in this sandbox, and the ASR passes that would derive a lattice from real audio (`:asr-*`,
 * P10) do not exist yet either. Reporting a number here would be exactly the fabrication the
 * task instructions forbid, so this prints what is missing instead of a fake report. The
 * mechanism itself — [Harness], calibration, ablation, the reliability diagram — is exercised
 * and proven correct by the module's own tests against synthetic fixtures.
 */
public fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("usage: eval <manifest-path> [--allow-eval]")
        return
    }
    val manifest = CorpusManifest.load(File(args[0]))
    val allowEval = "--allow-eval" in args
    val fold = if (allowEval) Fold.EVAL else Fold.DEV
    val entries = manifest.entries(fold, allowEval)

    println("loaded ${entries.size} manifest entries for fold=$fold")
    println(
        "no hand-labelled callsign occurrences are wired to these entries yet (build-plan P2/P6's " +
            "labelling protocol, spec/build-plan.md S1.8, has not been produced) — refusing to report a " +
            "fabricated precision/recall number. See lexicon/eval test suites for the harness mechanism, " +
            "proven against synthetic fixtures.",
    )

    // Wired for the day real occurrences exist: the same grammar and priors the harness's own
    // tests use, so real data drops in without touching this file.
    val grammar = CallsignGrammar(ItuPrefixTable.bundled(), ConfusionCostMatrix.bundled())
    val combiner = PriorCombiner(defaultPriors(PropagationModel()))
    val harness = Harness(grammar, combiner)
    val report = harness.run(
        fitOn = emptyList(),
        evaluate = emptyList(),
        config = HarnessConfig(fold.name.lowercase(), "unspecified", "cpu", 1, "unspecified"),
    )
    print(report.canonicalText())
}
