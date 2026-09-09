package org.ort.app.debug.tour

import android.content.Context
import android.graphics.Bitmap
import org.ort.app.debug.Scenarios
import java.io.File
import java.io.FileOutputStream

/** What one [TourStep] produced, before it is written to disk — [TourRunner] owns the disk I/O so
 * a [TourStepRenderer] stays a pure "compose it, capture it" step, independently testable (see
 * `ScreenshotTourTest`'s own fake). */
public data class TourCapture(public val bitmap: Bitmap, public val width: Int, public val height: Int)

/**
 * Produces one [TourCapture] for a [TourStep] whose base scenario [TourRunner] has already loaded.
 * The real implementation ([org.ort.app.debug.tour.RealTourStepRenderer], `ScreenshotTourActivity`'s
 * file) composes the real UI; tests substitute a fake that never touches a window, exactly the
 * reason [org.ort.app.ui.navigation.ReaderActivityDestinationSmokeTest] was split into its own
 * Gradle task — real `Activity`/window churn under Robolectric is expensive and, run enough times
 * in the one JVM worker `testDebugUnitTest` shares with ~900 other tests, has already been shown to
 * leave that worker's Compose test environment unable to reach idle for whatever composes next.
 * `ScreenshotTourTest` proves [TourRunner]'s own orchestration — scenario loading, PNG/manifest
 * writing, per-step error isolation — without paying that cost.
 */
public fun interface TourStepRenderer {
    /** [sessionId] is [org.ort.app.debug.Scenarios.LoadResult.primarySessionId] from the base
     * scenario [TourRunner] has already loaded by the time this is called — the real renderer
     * feeds it straight to [org.ort.app.ui.navigation.OrtNavHost]'s own `sessionId` parameter,
     * exactly as `ReaderActivity`/`ScenarioReaderActivity` already do. */
    public suspend fun render(step: TourStep, sessionId: String?): TourCapture
}

/**
 * Runs every [TourStep] in a [TourSpec] against a real [Scenarios.load] and a [TourStepRenderer],
 * writing `<outputDir>/<id>.png` and one JSONL line per step to `<outputDir>/manifest.json` as each
 * step finishes (never buffered to the end — see [appendManifestEntry]'s own doc comment). A step
 * that throws anywhere in this sequence — an unknown scenario name, an unsupported [TourStep.drillIn]
 * key, a renderer failure — is caught, written as one `error` manifest line, and the loop moves on;
 * nothing here ever aborts the tour early (this package's brief, verbatim).
 */
public class TourRunner(
    private val context: Context,
    private val renderer: TourStepRenderer,
    private val outputDir: File,
) {
    public suspend fun run(spec: TourSpec): List<TourManifestEntry> {
        // A fresh run must not append onto a previous invocation's manifest.json (tour.ps1 can be
        // re-run against the same on-device output dir any number of times) — start clean rather
        // than accumulating stale `done` markers/entries a reader would trip over.
        outputDir.deleteRecursively()
        outputDir.mkdirs()
        val manifestFile = File(outputDir, "manifest.json")
        val entries = mutableListOf<TourManifestEntry>()
        for (step in spec.steps) {
            val entry = runStep(step)
            entries += entry
            appendManifestEntry(manifestFile, entry)
        }
        val ok = entries.count { it.ok }
        appendManifestDone(manifestFile, total = entries.size, ok = ok, errors = entries.size - ok)
        return entries
    }

    private suspend fun runStep(step: TourStep): TourManifestEntry = try {
        if (step.unsupportedDrillInKeys.isNotEmpty()) {
            error(
                "tour step '${step.id}' needs drillIn key(s) ${step.unsupportedDrillInKeys} — v1 has no host " +
                    "parameter to seed them (OrtNavHost/NavHostNavState/ReaderNavigator expose no way to set " +
                    "a transmission/station/frequency/thread id, a log filter or the capture level-meter flag " +
                    "from outside the ui/navigation package; see TourStep's own doc comment)",
            )
        }
        val loadResult = Scenarios.load(context, step.scenario)
        val capture = renderer.render(step, loadResult.primarySessionId)
        writePng(capture.bitmap, File(outputDir, "${step.id}.png"))
        TourManifestEntry.success(step.id, step.scenario, step.fontScale, capture.width, capture.height)
    } catch (e: Exception) {
        TourManifestEntry.failure(step.id, step.scenario, step.fontScale, e.message ?: e.toString())
    }

    private fun writePng(bitmap: Bitmap, file: File) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out) }
    }

    private companion object {
        const val PNG_QUALITY = 100
    }
}
