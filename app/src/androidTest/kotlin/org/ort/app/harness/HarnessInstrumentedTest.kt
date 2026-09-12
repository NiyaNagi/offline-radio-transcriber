package org.ort.app.harness

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.eval.HarnessConfig
import java.io.File

/**
 * The on-device harness runner (build-plan P9, implementation-plan M2.21a, technical design
 * §17): the same manifest and report format as `:eval`'s JVM `main()`
 * (`eval/src/main/kotlin/org/ort/eval/Main.kt`), run as an instrumented test so the reference
 * device's own execution provider is what the harness measures — a desktop-only harness
 * structurally cannot measure T3 (AC-36), because T3 is defined by an NPU that exists only on
 * the phone.
 *
 * All the logic that must match `:eval`'s output format lives in [OnDeviceHarnessRunner]
 * (`app/src/harnessShared/kotlin`), proven byte-identical to the JVM harness by
 * `OnDeviceHarnessRunnerTest` (JVM, no device). This class is deliberately thin: it does nothing
 * but resolve Android-specific paths and metadata and hand them to that shared runner, so there
 * is exactly one place the report-writing logic can drift from `:eval`.
 *
 * **Corpus push / report pull (P9's "corpus pushed to app-private storage and the report pulled
 * back"):** this test reads the manifest and audio from
 * `<app-private-files>/harness-corpus/manifest.tsv` and its referenced paths, and writes the
 * report to `<app-private-files>/harness-reports/<fingerprint-ish name>.txt`. On the reference
 * device, with a debug (or otherwise debuggable) build, the real commands are:
 *
 * ```
 * adb push local-corpus/ /data/local/tmp/ort-harness-corpus
 * adb shell run-as org.ort.app mkdir -p files/harness-corpus
 * adb shell run-as org.ort.app cp -r /data/local/tmp/ort-harness-corpus/. files/harness-corpus/
 * adb shell am instrument -w -e class org.ort.app.harness.HarnessInstrumentedTest \
 *     org.ort.app.test/androidx.test.runner.AndroidJUnitRunner
 * adb shell run-as org.ort.app cat files/harness-reports/report.txt > report.txt   # or:
 * adb pull /data/data/org.ort.app/files/harness-reports/report.txt .               # rooted/emulator
 * ```
 *
 * **Not exercised by this test file, because it needs the physical reference device**: the
 * actual push, the actual `am instrument` execution on-device, and the actual pull. Those remain
 * open pending hardware access — see CHANGELOG.md and the P9 session's final report.
 */
@RunWith(AndroidJUnit4::class)
class HarnessInstrumentedTest {

    // R-1001 build report (WPJ, out-of-package but pre-existing and build-breaking, fixed here
    // because app/src/androidTest/** is this session's own owned path and this defect blocks
    // *every* instrumented test in the app, including this session's own new one): a backtick
    // method name containing spaces compiles fine to a .class file but D8 rejects it while dexing
    // for androidTest ("Space characters in SimpleName ... are not allowed prior to DEX version
    // 040") at this project's minSdk 26 — confirmed directly, this is what
    // `:app:dexBuilderDebugAndroidTest` failed on before this rename, for *any* androidTest run,
    // not anything to do with sherpa-onnx. This class's own doc comment already says it was never
    // actually exercised on a device ("pending hardware access") — this is the first time anyone
    // tried. Renamed only; no behavioural change.
    @Test
    fun m2_21a_onDeviceHarnessRunProducesACanonicalReportFilePullableOffDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val corpusDir = File(context.filesDir, "harness-corpus")
        val manifestFile = File(corpusDir, "manifest.tsv")

        // A minimal manifest so this test is self-contained; the real M2 gate run (D4/D8, a
        // future session with the reference device) pushes the actual corpus here instead of
        // relying on this fixture.
        corpusDir.mkdirs()
        manifestFile.writeText(
            "# id\tfold\tsource\tpath\tsynthetic\n" +
                "sample-1\tdev\tsynthetic\taudio/sample-1.wav\ttrue\n",
        )

        val config = HarnessConfig(
            fold = "dev",
            machine = "${Build.MANUFACTURER} ${Build.MODEL}",
            provider = "cpu", // becomes "nnapi"/the real T3 provider once P10's ASR passes exist
            threadCount = Runtime.getRuntime().availableProcessors(),
            runtimeVersion = Build.VERSION.SDK_INT.toString(),
        )

        val reportFile = File(File(context.filesDir, "harness-reports"), "report.txt")
        val report = OnDeviceHarnessRunner.runAndWriteReport(manifestFile, reportFile, config)

        assertTrue("expected the report file to exist in app-private storage", reportFile.exists())
        assertEquals(report.canonicalText(), reportFile.readText())
        assertTrue(
            "report must carry this device as its machine (constitution VI: never report a " +
                "number without its fold, machine and provider)",
            report.canonicalText().contains("machine=${config.machine}"),
        )
    }
}
