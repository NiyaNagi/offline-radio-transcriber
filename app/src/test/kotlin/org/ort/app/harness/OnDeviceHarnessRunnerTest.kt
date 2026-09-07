package org.ort.app.harness

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.ort.eval.Harness
import org.ort.eval.HarnessConfig
import org.ort.lexicon.CallsignGrammar
import org.ort.lexicon.ConfusionCostMatrix
import org.ort.lexicon.ItuPrefixTable
import org.ort.lexicon.PriorCombiner
import org.ort.lexicon.PropagationModel
import org.ort.lexicon.defaultPriors
import org.ort.testing.Requirement
import java.io.File
import java.nio.file.Path

/**
 * Build-plan P9 / implementation-plan M2.21a: the on-device harness runner must produce the
 * exact same [org.ort.eval.HarnessReport.canonicalText] format as the JVM harness
 * (eval/src/main/kotlin/org/ort/eval/Main.kt) — "same manifest, same report format". This is
 * the JVM-side half of that proof: [OnDeviceHarnessRunner] has no `android.*` import, so this
 * test exercises the exact code path the instrumented entry point
 * (app/src/androidTest/kotlin/org/ort/app/harness/HarnessInstrumentedTest.kt) calls on-device,
 * without needing a device to run it.
 */
class OnDeviceHarnessRunnerTest {

    private fun manifest(dir: Path, fold: String = "dev"): File {
        val file = dir.resolve("manifest.tsv").toFile()
        // Synthetic data must never enter the eval fold (FR-TST-9) — CorpusManifest enforces
        // this at parse time, so the eval-fold fixture below marks its entry non-synthetic.
        val synthetic = fold != "eval"
        file.writeText(
            "# id\tfold\tsource\tpath\tsynthetic\n" +
                "sample-1\t$fold\tsynthetic\taudio/sample-1.wav\t$synthetic\n",
        )
        return file
    }

    private fun config(fold: String = "dev") = HarnessConfig(
        fold = fold,
        machine = "test-bench",
        provider = "cpu",
        threadCount = 1,
        runtimeVersion = "unspecified",
    )

    /** The same construction Main.kt uses, so the expected text is derived independently of
     * [OnDeviceHarnessRunner] rather than by calling into it. */
    private fun expectedReportText(config: HarnessConfig): String {
        val grammar = CallsignGrammar(ItuPrefixTable.bundled(), ConfusionCostMatrix.bundled())
        val combiner = PriorCombiner(defaultPriors(PropagationModel()))
        val harness = Harness(grammar, combiner)
        return harness.run(fitOn = emptyList(), evaluate = emptyList(), config = config).canonicalText()
    }

    @Test
    @Requirement("AC-36")
    fun `M2_21a on-device runner produces byte-identical canonical text to the JVM harness`(@TempDir dir: Path) {
        val cfg = config()
        val report = OnDeviceHarnessRunner.run(manifest(dir, "dev"), cfg)
        assertEquals(expectedReportText(cfg), report.canonicalText())
    }

    @Test
    @Requirement("AC-36")
    fun `runAndWriteReport writes exactly canonicalText to the output file, pullable off-device`(@TempDir dir: Path) {
        val cfg = config()
        val outputFile = dir.resolve("reports/on-device-report.txt").toFile()
        val report = OnDeviceHarnessRunner.runAndWriteReport(manifest(dir, "dev"), outputFile, cfg)

        assertEquals(true, outputFile.exists())
        assertEquals(report.canonicalText(), outputFile.readText())
        assertEquals(expectedReportText(cfg), outputFile.readText())
    }

    @Test
    @Requirement("FR-TST-7", "AC-100")
    fun `FR_TST_7 the eval fold is refused without an explicit opt-in, same as the JVM harness`(@TempDir dir: Path) {
        val evalManifest = manifest(dir, "eval")
        val cfg = config("eval")

        assertThrows(IllegalStateException::class.java) {
            OnDeviceHarnessRunner.run(evalManifest, cfg, allowEval = false)
        }

        // allowEval = true is the one sanctioned path (constitution VI) — it must still succeed.
        val report = OnDeviceHarnessRunner.run(evalManifest, cfg, allowEval = true)
        assertEquals(expectedReportText(cfg), report.canonicalText())
    }
}
