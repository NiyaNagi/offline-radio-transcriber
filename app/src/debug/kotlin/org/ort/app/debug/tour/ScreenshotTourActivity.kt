package org.ort.app.debug.tour

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.view.drawToBitmap
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.ort.app.BuildConfig
import org.ort.app.debug.Scenarios
import org.ort.app.ui.navigation.NavSeed
import org.ort.app.ui.navigation.OrtNavHost
import org.ort.app.ui.navigation.ReaderDestination
import org.ort.app.ui.navigation.rememberReaderNavigator
import org.ort.app.ui.setup.SetupActivity
import org.ort.app.ui.theme.OrtTheme
import java.io.File

/**
 * spec/ui-conformance-plan.md WP12 — one command that produces every screenshot a validator brief
 * names, deterministically, without an emulator session driven by hand. Debug-build-only (this
 * package's brief), launched by `tools/ui-audit/tour.ps1`:
 *
 * ```
 * adb shell am start -n org.ort.app/.debug.tour.ScreenshotTourActivity
 * ```
 *
 * The spec is read from this app's own sandbox, `filesDir/tour/spec.json` (see [readSpecJson]'s own
 * doc comment for why — API 34 scoped storage refuses this app any read of `/sdcard` or
 * `/data/local/tmp`, found by actually running this against a real device, not by inspection):
 * `tour.ps1` writes it there with `run-as` before launching this activity. [EXTRA_SPEC_PATH]
 * overrides that default path, for a caller that already has its own on-device spec file.
 *
 * For each [TourStep] (see that class's own doc comment for the schema and, importantly, for
 * exactly which `drillIn` kinds are seedable at all — v2, [TourIds]): loads the step's base scenario
 * through the real [Scenarios.load] (the same seeding [org.ort.app.debug.ScenarioReceiver] and
 * `ScenarioReaderActivity` use), resolves any `drillIn` map into a real
 * [org.ort.app.ui.navigation.NavSeed] against that just-loaded data ([TourIds.resolveSeed]), then
 * either
 *
 * - **a destination step** — composes the real [OrtNavHost] *directly inside this activity's own
 *   `setContent`* (not by launching [org.ort.app.ui.ReaderActivity] as a child — composing in place
 *   is what lets each step's own font scale actually take effect via `CompositionLocalProvider(LocalDensity
 *   provides ...)`, which neither `ReaderActivity` nor `OrtNavHost` exposes an Intent extra or
 *   parameter for), keyed on the step id so each step gets a fresh [rememberReaderNavigator] /
 *   `NavHostNavState` and the previous step's polling `LaunchedEffect`s are cancelled the normal
 *   Compose way; or
 * - **a setup step** — launches the real [SetupActivity] with `EXTRA_STEP` (its own, existing,
 *   already-public extra — nothing under `ui/setup` is touched) and captures that activity's own
 *   window once it resumes, via an [Application.ActivityLifecycleCallbacks] registered in
 *   [onCreate] — `SetupActivity`'s screen rendering is a set of private methods on the `Activity`
 *   itself (confirmed by reading `SetupActivity.kt` before writing this — there is no reusable,
 *   `Context`-free composable to call directly the way [OrtNavHost] is), so a real, separate
 *   `Activity` launch is the only way to reach it without duplicating WP9's own dispatch. Font scale
 *   (v3) uses [SetupActivity.EXTRA_FONT_SCALE] (WP9's own seam for this tour, added after v2 was
 *   written — its own doc comment explains why the *system* font-scale setting was never an option).
 *
 * captures a `Bitmap` via [androidx.core.view.drawToBitmap] once composition/polling has had time to
 * settle (see [DESTINATION_SETTLE_MILLIS]'s own doc comment for why that duration, not an arbitrary
 * one), and hands it to [TourRunner] for the actual PNG/manifest I/O. [TourRunner] catches and
 * records every per-step failure — including an unresolvable destination/settings-screen/setup id
 * or an unsupported `drillIn` key, both validated here in plain (non-composable) suspend code so a
 * bad step can never crash mid-composition; see [TourStepRenderer]'s own doc comment — so this
 * activity's own `onCreate` never needs a try/catch of its own around the whole run.
 *
 * Real capture is never started: no step here ever taps anything (this package's brief: "no
 * taps/sheets in v1"), and no code path in this file references
 * `org.ort.pipeline.capture.RealCaptureService`.
 */
public class ScreenshotTourActivity : ComponentActivity() {

    private var currentDestinationStep by mutableStateOf<ResolvedDestinationStep?>(null)
    private var pendingSetupActivity: CompletableDeferred<SetupActivity>? = null

    private val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            if (activity is SetupActivity) pendingSetupActivity?.takeIf { !it.isCompleted }?.complete(activity)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        application.registerActivityLifecycleCallbacks(lifecycleCallbacks)

        setContent {
            OrtTheme {
                val resolved = currentDestinationStep
                if (resolved != null) {
                    key(resolved.id) {
                        val baseDensity = LocalDensity.current
                        CompositionLocalProvider(
                            LocalDensity provides Density(baseDensity.density, resolved.fontScale),
                        ) {
                            val navigator = rememberReaderNavigator(
                                initialDestination = resolved.destination,
                                initialSettingsScreen = resolved.navSeed?.settingsScreen,
                                seed = resolved.navSeed,
                            )
                            OrtNavHost(sessionId = resolved.sessionId, seed = resolved.navSeed, navigator = navigator)
                        }
                    }
                }
            }
        }

        lifecycleScope.launch {
            runTour()
            application.unregisterActivityLifecycleCallbacks(lifecycleCallbacks)
            finish()
        }
    }

    private suspend fun runTour() {
        val specJson = readSpecJson()
        val outputDir = File(filesDir, TOUR_OUTPUT_DIR_NAME)
        if (specJson == null) {
            outputDir.mkdirs()
            appendManifestDone(File(outputDir, "manifest.json"), total = 0, ok = 0, errors = 0)
            return
        }
        val spec = TourSpec.parse(specJson)
        val renderer = TourStepRenderer { step, sessionId ->
            if (step.setup != null) renderSetupStep(step) else renderDestinationStep(step, sessionId)
        }
        TourRunner(applicationContext, renderer, outputDir, apkHash = BuildConfig.GIT_SHORT_COMMIT).run(spec)
    }

    /**
     * The tour spec's on-device home is this app's own private storage, never `/sdcard` or
     * `/data/local/tmp` — API 34's scoped storage refuses this app read access to either (a real
     * `FileNotFoundException ... EACCES` from `File(path).readText()`, found by actually running the
     * first real device tour, not by inspection). Default: `filesDir/tour/spec.json`, which
     * `tour.ps1` now writes with `adb shell run-as org.ort.app sh -c "mkdir -p files/tour && cat >
     * files/tour/spec.json"` before this activity is launched — the same private directory
     * [TourRunner]'s own output lands in, and the same one `pm clear` wipes, so a spec written before
     * a clear would silently vanish; `tour.ps1` writes it *after* any `-Clear`. [EXTRA_TOUR_JSON]
     * (inline JSON in the launch intent) stays as a convenience for a caller that is not `tour.ps1`
     * itself; [EXTRA_SPEC_PATH] overrides the default on-device path outright.
     */
    private fun readSpecJson(): String? {
        val overridePath = intent?.getStringExtra(EXTRA_SPEC_PATH)
        val specFile = if (overridePath != null) {
            File(overridePath)
        } else {
            File(File(filesDir, TOUR_OUTPUT_DIR_NAME), "spec.json")
        }
        if (specFile.exists()) return specFile.readText()
        return intent?.getStringExtra(EXTRA_TOUR_JSON)
    }

    /**
     * [org.ort.app.debug.tour.TourStep.destination] and every [TourStep.drillIn] key are resolved
     * to real values here — plain suspend code, not composition — so an unrecognised name, or a
     * symbolic drill-in value [TourIds] cannot find in this step's own just-loaded data, throws in a
     * place [TourRunner] already catches, never inside `setContent` where an exception would crash
     * the whole activity instead of failing one step (see this class's own doc comment).
     */
    private suspend fun renderDestinationStep(step: TourStep, sessionId: String?): TourCapture {
        val destination = ReaderDestination.entries.firstOrNull { it.name == step.destination }
            ?: error("tour step '${step.id}' names unknown destination '${step.destination}'")
        val navSeed = TourIds.resolveSeed(applicationContext, sessionId, step.drillIn)
        currentDestinationStep = ResolvedDestinationStep(step.id, destination, navSeed, step.fontScale, sessionId)
        // Composition + the first poll tick: `OrtNavHost`'s own `LaunchedEffect(sessionId)` polling
        // loops run their body once, synchronously, before their first `delay(2_000)` (confirmed by
        // reading `OrtNavHost.kt`'s `rememberDrawerLiveState` before writing this), so the initial
        // read completes well inside this window on a real, continuously-rendering window — not a
        // blind multi-second sleep, a bound sized to "one recomposition + one local DB read".
        delay(DESTINATION_SETTLE_MILLIS)
        if (step.override != null) {
            Scenarios.load(applicationContext, step.override)
            // The override must be observed by the *next* poll tick, not a fresh composition (this
            // is the in-process form of `scenario.ps1 -NoRestart` — see `TourStep.override`'s own
            // doc comment) — so this wait is sized to `OrtNavHost`'s own 2_000ms poll interval, not
            // an arbitrary duration either.
            delay(OVERRIDE_SETTLE_MILLIS)
        }
        if (step.waitMillis > 0) delay(step.waitMillis)
        if (step.scroll == "end") {
            TourAccessibilityScroll.scrollToEnd(window.decorView)
            delay(SCROLL_SETTLE_MILLIS)
        }
        val bitmap = window.decorView.drawToBitmap()
        currentDestinationStep = null
        return TourCapture(bitmap, bitmap.width, bitmap.height)
    }

    private suspend fun renderSetupStep(step: TourStep): TourCapture {
        val stepName = SetupStepIds.setupStepNameFor(requireNotNull(step.setup))
            ?: error("tour step '${step.id}' names unknown setup id '${step.setup}'")
        val deferred = CompletableDeferred<SetupActivity>()
        pendingSetupActivity = deferred
        startActivity(
            Intent(this, SetupActivity::class.java)
                .putExtra(SetupActivity.EXTRA_STEP, stepName)
                .putExtra(SetupActivity.EXTRA_FONT_SCALE, step.fontScale),
        )
        val setupActivity = withTimeout(SETUP_LAUNCH_TIMEOUT_MILLIS) { deferred.await() }
        pendingSetupActivity = null
        delay(DESTINATION_SETTLE_MILLIS)
        if (step.waitMillis > 0) delay(step.waitMillis)
        if (step.scroll == "end") {
            TourAccessibilityScroll.scrollToEnd(setupActivity.window.decorView)
            delay(SCROLL_SETTLE_MILLIS)
        }
        val bitmap = setupActivity.window.decorView.drawToBitmap()
        setupActivity.finish()
        return TourCapture(bitmap, bitmap.width, bitmap.height)
    }

    private data class ResolvedDestinationStep(
        val id: String,
        val destination: ReaderDestination,
        val navSeed: NavSeed?,
        val fontScale: Float,
        val sessionId: String?,
    )

    public companion object {
        public const val EXTRA_SPEC_PATH: String = "spec_path"
        public const val EXTRA_TOUR_JSON: String = "tour_json"
        public const val TOUR_OUTPUT_DIR_NAME: String = "tour"

        private const val DESTINATION_SETTLE_MILLIS = 600L
        private const val OVERRIDE_SETTLE_MILLIS = 2_300L
        private const val SETUP_LAUNCH_TIMEOUT_MILLIS = 10_000L

        /** After [TourAccessibilityScroll.scrollToEnd] — content shifting into place from a real
         * scroll (not a fresh composition) needs its own settle, not the destination-composition one
         * above; one frame plus margin, not sized to any poll interval since nothing here waits on a
         * poll tick. */
        private const val SCROLL_SETTLE_MILLIS = 300L
    }
}
