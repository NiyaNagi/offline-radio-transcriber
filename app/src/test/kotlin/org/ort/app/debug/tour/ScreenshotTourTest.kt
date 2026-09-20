package org.ort.app.debug.tour

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.failures.DebugFailureOverride
import org.ort.app.ui.setup.SharedPreferencesSetupStore
import org.ort.pipeline.capture.AsrAvailability
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.InputStatus
import org.ort.pipeline.capture.LevelStatus
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.capture.ShedStatus
import org.ort.pipeline.capture.StorageForecast
import org.ort.pipeline.capture.ThermalStatus
import org.ort.pipeline.capture.VadAvailability
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * spec/ui-conformance-plan.md WP12, register (this package's brief): proves [TourRunner]'s own
 * orchestration — real scenario loading via [org.ort.app.debug.Scenarios.load], PNG + JSONL
 * manifest writing, per-step error isolation — against a [TourStepRenderer] fake that never opens a
 * window. See [TourStepRenderer]'s own doc comment for why a real `Activity`/`OrtNavHost`
 * composition is deliberately *not* exercised from this shared JVM worker.
 */
@RunWith(RobolectricTestRunner::class)
class ScreenshotTourTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun resetProcessWideState() {
        AsrAvailability.reset()
        VadAvailability.reset()
        CaptureState.idle(clearSession = true)
        ShedStatus.reset()
        ThermalStatus.reset()
        RigStatus.reset()
        StorageForecast.reset()
        LevelStatus.reset()
        InputStatus.reset()
        DebugFailureOverride.clear()
        context.getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun fakeCapture(size: Int = 4): TourCapture =
        TourCapture(Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888), size, size)

    @Test
    fun `R_TOUR_RUNNER_OK a two-step tour writes two PNGs and an ok manifest for both`() = runTest {
        val outputDir = File(context.filesDir, "tour-test-ok").apply { deleteRecursively() }
        val spec = TourSpec(
            listOf(
                TourStep(id = "overnight/N01-now", scenario = "overnight", destination = "NOW"),
                TourStep(id = "overnight/L01-log", scenario = "overnight", destination = "LOG"),
            ),
        )
        val renderer = TourStepRenderer { step, sessionId ->
            assertNotNull("expected a primary session id for step '${step.id}'", sessionId)
            fakeCapture()
        }

        val entries = TourRunner(context, renderer, outputDir).run(spec)

        assertEquals(2, entries.size)
        assertTrue("every entry should report ok: $entries", entries.all { it.ok })
        assertTrue(File(outputDir, "overnight/N01-now.png").exists())
        assertTrue(File(outputDir, "overnight/L01-log.png").exists())

        val manifestFile = File(outputDir, "manifest.json")
        val manifestEntries = readManifestEntries(manifestFile)
        assertEquals(2, manifestEntries.size)
        assertTrue(manifestEntries.all { it.ok })
        val lastLine = manifestFile.readLines().last { it.isNotBlank() }
        assertTrue("manifest's last line should be the done marker", JSONObject(lastLine).getBoolean("done"))
    }

    @Test
    fun `R_TOUR_RUNNER_ERROR_ISOLATED an unknown destination records an error and the tour still finishes`() = runTest {
        val outputDir = File(context.filesDir, "tour-test-error").apply { deleteRecursively() }
        val spec = TourSpec(
            listOf(
                TourStep(id = "bad/unknown", scenario = "overnight", destination = "NOT_A_REAL_DESTINATION"),
                TourStep(id = "overnight/N01-now", scenario = "overnight", destination = "NOW"),
            ),
        )
        val renderer = TourStepRenderer { step, _ ->
            if (step.destination == "NOT_A_REAL_DESTINATION") error("simulated: unknown destination")
            fakeCapture()
        }

        val entries = TourRunner(context, renderer, outputDir).run(spec)

        assertEquals(2, entries.size)
        assertFalse("the bad step should record an error", entries[0].ok)
        assertNotNull(entries[0].errorMessage)
        assertTrue("the second step should still complete", entries[1].ok)
        assertFalse(File(outputDir, "bad/unknown.png").exists())
        assertTrue(File(outputDir, "overnight/N01-now.png").exists())
    }

    /** Coordinator round two: a step whose own capture carries a note (today, exactly the
     * `scroll: "end"`-on-a-screen-with-nothing-to-scroll case — see [TourAccessibilityScroll.ScrollOutcome])
     * must still record `ok = true` with `errorMessage = null` — the note is evidence the screen
     * reached what it claims (it fits without scrolling), never a failure to record as one. */
    @Test
    fun `R_TOUR_NOTE_OK a capture carrying a note still reports ok with no error message`() = runTest {
        val outputDir = File(context.filesDir, "tour-test-note").apply { deleteRecursively() }
        val spec = TourSpec(listOf(TourStep(id = "overnight/N01-now", scenario = "overnight", destination = "NOW")))
        val renderer = TourStepRenderer { _, _ -> fakeCapture().copy(note = "no scroll — fits") }

        val entries = TourRunner(context, renderer, outputDir).run(spec)

        assertEquals(1, entries.size)
        assertTrue("a noted capture must still report ok", entries.single().ok)
        assertEquals(null, entries.single().errorMessage)
        assertEquals("no scroll — fits", entries.single().note)

        val manifestEntries = readManifestEntries(File(outputDir, "manifest.json"))
        assertEquals("no scroll — fits", manifestEntries.single().note)
        assertTrue(manifestEntries.single().ok)
    }

    /** R-1083 (register): the stale-manifest false green relies on `tour.ps1` matching a `done`
     * line's own `runId` against the one it just pushed — this is the device-side half of that
     * contract: every manifest line [TourRunner] writes for a given run, including the trailing
     * `done` marker, must carry the run's own id, never a placeholder or the previous run's. */
    @Test
    fun `R_1083_RUN_ID_STAMPED every manifest entry and the done line carry the run's own id`() = runTest {
        val outputDir = File(context.filesDir, "tour-test-runid").apply { deleteRecursively() }
        val spec = TourSpec(listOf(TourStep(id = "overnight/N01-now", scenario = "overnight", destination = "NOW")))
        val renderer = TourStepRenderer { _, _ -> fakeCapture() }

        TourRunner(context, renderer, outputDir, runId = "run-A").run(spec)

        val manifestFile = File(outputDir, "manifest.json")
        val manifestEntries = readManifestEntries(manifestFile)
        assertEquals("run-A", manifestEntries.single().runId)
        val lastLine = manifestFile.readLines().last { it.isNotBlank() }
        assertEquals("run-A", JSONObject(lastLine).getString("runId"))
    }

    /** R-1083: a second, later run against the same on-device output directory (`tour.ps1` can be
     * re-run any number of times against the same app install) must never leave the previous run's
     * own id on the final `done` line — that is exactly the shape of manifest a stale poll could
     * mistake for its own success. [TourRunner.run] already wipes [outputDir] first (this test
     * would fail just the same if that regressed); this proves the *id* half of the fix
     * specifically, independent of the file-deletion half. */
    @Test
    fun `R_1083_STALE_RUN_ID_REPLACED a later run's done line never carries an earlier run's own id`() = runTest {
        val outputDir = File(context.filesDir, "tour-test-stale-runid").apply { deleteRecursively() }
        val spec = TourSpec(listOf(TourStep(id = "overnight/N01-now", scenario = "overnight", destination = "NOW")))
        val renderer = TourStepRenderer { _, _ -> fakeCapture() }

        TourRunner(context, renderer, outputDir, runId = "run-old").run(spec)
        TourRunner(context, renderer, outputDir, runId = "run-new").run(spec)

        val manifestFile = File(outputDir, "manifest.json")
        val lastLine = manifestFile.readLines().last { it.isNotBlank() }
        assertEquals("run-new", JSONObject(lastLine).getString("runId"))
    }

    @Test
    fun `R_TOUR_UNSUPPORTED_DRILL_IN an unsupported key records an error without invoking the renderer`() = runTest {
        val outputDir = File(context.filesDir, "tour-test-drillin").apply { deleteRecursively() }
        val spec = TourSpec(
            listOf(
                TourStep(
                    id = "detail/D01",
                    scenario = "overnight",
                    destination = "LOG",
                    drillIn = mapOf("transmissionId" to "t1"),
                ),
            ),
        )
        var rendererCalled = false
        val renderer = TourStepRenderer { _, _ ->
            rendererCalled = true
            fakeCapture()
        }

        val entries = TourRunner(context, renderer, outputDir).run(spec)

        assertFalse(entries[0].ok)
        assertNotNull(entries[0].errorMessage)
        assertFalse("the renderer must never run for an unsupported drillIn key", rendererCalled)
    }
}
