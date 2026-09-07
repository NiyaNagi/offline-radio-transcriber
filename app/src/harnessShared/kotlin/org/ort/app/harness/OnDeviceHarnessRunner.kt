package org.ort.app.harness

import org.ort.eval.Harness
import org.ort.eval.HarnessConfig
import org.ort.eval.HarnessReport
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
 * The on-device counterpart of `:eval`'s JVM `main()`
 * (`eval/src/main/kotlin/org/ort/eval/Main.kt`) — build-plan P9, implementation-plan M2.21a,
 * technical design §17: "`:eval` ships two entry points over one implementation... emitting the
 * same report format."
 *
 * This object is deliberately free of any `android.*` import so it compiles and runs identically
 * whether it is called from a plain JVM unit test
 * (`app/src/test/kotlin/org/ort/app/harness/OnDeviceHarnessRunnerTest.kt`, no device needed) or
 * from the instrumented entry point that actually executes on the reference device
 * (`app/src/androidTest/kotlin/org/ort/app/harness/HarnessInstrumentedTest.kt`). "Same manifest,
 * same report format as the JVM harness" is only true if both call this one function — a runner
 * reimplemented separately for on-device use would drift from `:eval`'s own tests silently.
 *
 * It lives in `app/src/harnessShared/kotlin`, wired into both the `test` and `androidTest`
 * source sets (see `app/build.gradle.kts`), rather than `app/src/main`, because `:app`'s main
 * source set has no permitted compile-time edge to `:eval` (`buildSrc/.../ModuleGraph.kt` — only
 * `:pipeline`, `:data`, `:net`, `:core`). `dependencyRules` does not check test-scoped
 * configurations (`testImplementation`, `androidTestImplementation`) by design — `:testing`
 * fakes are meant to be reachable from any module's tests, and the harness is exactly that kind
 * of test-only tool, never linked into the shipped `app` binary.
 */
public object OnDeviceHarnessRunner {

    /**
     * Loads [manifestFile] (the same tab-separated format `:testing`'s `CorpusManifest` reads
     * everywhere else) and runs the harness against [config]'s fold, refusing the `eval` fold
     * without [allowEval] — the identical gate `:eval`'s `Main.kt` applies (FR-TST-7, AC-100).
     *
     * Mirrors `Main.kt` exactly: no hand-labelled callsign occurrences are wired to manifest
     * entries yet (build-plan P2/P6's labelling protocol, spec/build-plan.md S1.8, has not been
     * produced, and P10's ASR passes that would derive a lattice from real audio do not exist
     * yet either), so this reports the harness's mechanism against an empty occurrence set
     * rather than fabricate a precision/recall number. When real occurrences exist, the same
     * `ManifestHarness` wiring `:eval` already has drops in here unchanged.
     */
    public fun run(manifestFile: File, config: HarnessConfig, allowEval: Boolean = false): HarnessReport {
        val manifest = CorpusManifest.load(manifestFile)
        val fold = Fold.valueOf(config.fold.uppercase())
        // The call's return value is unused today (see kdoc above) — its purpose here is solely
        // to apply the eval-fold seal to whatever corpus was pushed to the device, exactly as
        // Main.kt does for the desktop harness.
        manifest.entries(fold, allowEval)

        val grammar = CallsignGrammar(ItuPrefixTable.bundled(), ConfusionCostMatrix.bundled())
        val combiner = PriorCombiner(defaultPriors(PropagationModel()))
        val harness = Harness(grammar, combiner)
        return harness.run(fitOn = emptyList(), evaluate = emptyList(), config = config)
    }

    /**
     * Runs the harness and writes [HarnessReport.canonicalText] to [outputFile] verbatim,
     * creating parent directories as needed. This is the exact text an `adb pull` of
     * [outputFile] carries off the device — see `HarnessInstrumentedTest` for where it is
     * expected to land in app-private storage.
     */
    public fun runAndWriteReport(
        manifestFile: File,
        outputFile: File,
        config: HarnessConfig,
        allowEval: Boolean = false,
    ): HarnessReport {
        val report = run(manifestFile, config, allowEval)
        outputFile.parentFile?.mkdirs()
        outputFile.writeText(report.canonicalText())
        return report
    }
}
