package org.ort.app.debug.tour

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
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
import kotlinx.coroutines.withTimeoutOrNull
import org.ort.app.BuildConfig
import org.ort.app.debug.Scenarios
import org.ort.app.ui.navigation.NavSeed
import org.ort.app.ui.navigation.OrtNavHost
import org.ort.app.ui.navigation.ReaderDestination
import org.ort.app.ui.navigation.ReaderNavigator
import org.ort.app.ui.navigation.ReviewSessionView
import org.ort.app.ui.navigation.rememberReaderNavigator
import org.ort.app.ui.setup.RigLinkState
import org.ort.app.ui.setup.SetupActivity
import org.ort.app.ui.theme.OrtTheme
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.InputStatus
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

    /** R-803: the [ReaderNavigator] the currently-composed destination step actually built, written
     * once per fresh `key(resolved.id)` composition via [SideEffect] (below) — the one handle
     * [renderDestinationStep] (a plain suspend function, outside composition) can poll to prove a
     * screen has actually settled to what the step asked for, rather than assuming a fixed delay
     * always suffices. `@Volatile` because the write happens on the composition/UI thread and the
     * read happens from `lifecycleScope`'s own coroutine — both are the main thread in practice, but
     * this makes that safe rather than assumed. */
    @Volatile
    private var activeNavigator: ReaderNavigator? = null

    private val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            // R-803: an activity already finishing (this same class's own `finish()` call for the
            // *previous* setup step, mid-teardown) must never complete a *new* step's deferred — the
            // exact race behind a captured screenshot showing the previous step's own screen/font
            // scale instead of the one just requested (`assets-bundled/S12-ready-bundled` showing
            // `setup-rig-bluetooth/S10b` at its own 2x scale, found by the coordinator's spot-check).
            if (activity is SetupActivity && !activity.isFinishing) {
                pendingSetupActivity?.takeIf { !it.isCompleted }?.complete(activity)
            }
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
                            // R-803: published for `renderDestinationStep` to poll — see
                            // `activeNavigator`'s own doc comment. Runs after every successful
                            // composition of this exact `key(resolved.id)` subtree, so a step that is
                            // still mid-render never publishes a half-built navigator.
                            SideEffect { activeNavigator = navigator }
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
        val expectDrawerOpen = navSeed?.openDrawer == true
        // R-840: only meaningful once a step's own seed actually asks for a session review at all —
        // a step with no `reviewSession` drillIn never touches `Earlier nights`' own Session/Digest
        // split, so this stays `null` (not checked) for every other step, the same "only alongside
        // its own companion key" contract `NavSeed.reviewSessionView` itself documents.
        val expectReviewSessionView = navSeed?.pendingReviewSessionId?.let {
            navSeed.reviewSessionView
                ?: ReviewSessionView.SESSION
        }
        // R-910/R-911: `CaptureState.isCapturing` is process-wide, already true the instant this
        // step's own scenario finished loading (every live scenario marks it via
        // `ScenarioFixtures.markCapturing` — see `WpiScenariosTest.R_913_*`) — the same real fact a
        // live bar's own presence depends on, read directly rather than duplicated as a new `drillIn`
        // key no step needs to name.
        val expectLiveBar = CaptureState.isCapturing
        // R-803 (halt): a stale `activeNavigator` from the *previous* step's own, about-to-be-torn-
        // down composition must never be read as "already matching" this step's own destination —
        // cleared before the new `key(resolved.id)` composition even starts, not after.
        activeNavigator = null
        currentDestinationStep = ResolvedDestinationStep(step.id, destination, navSeed, step.fontScale, sessionId)
        val settled = awaitDestinationSettled(destination, expectDrawerOpen, expectReviewSessionView, expectLiveBar)
        if (!settled) {
            val observed = activeNavigator
            error(
                "tour step '${step.id}' never settled to destination=$destination drawerOpen=$expectDrawerOpen " +
                    "reviewSessionView=$expectReviewSessionView expectLiveBar=$expectLiveBar within " +
                    "${STATE_WAIT_TIMEOUT_MILLIS}ms — observed destination=${observed?.currentState?.value} " +
                    "drawerOpen=${observed?.drawerOpenState?.value} " +
                    "reviewSessionView=${observed?.reviewSessionViewState?.value} " +
                    "liveBar=${observed?.liveBarState?.value} sessionId=$sessionId " +
                    "captureStateSessionId=${CaptureState.sessionId} " +
                    "captureStateIsCapturing=${CaptureState.isCapturing} " +
                    "inputStatus=${InputStatus.state}",
            )
        }
        // Composition + the first poll tick: `OrtNavHost`'s own `LaunchedEffect(sessionId)` polling
        // loops run their body once, synchronously, before their first `delay(2_000)` (confirmed by
        // reading `OrtNavHost.kt`'s `rememberDrawerLiveState` before writing this), so the initial
        // read completes well inside this window on a real, continuously-rendering window — not a
        // blind multi-second sleep, a bound sized to "one recomposition + one local DB read". Kept
        // as a floor for animations even now that the state match above proves the right screen
        // composed (R-803's own coordinator instruction).
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
        var scrollNote: String? = null
        if (step.scroll == "end") {
            when (TourAccessibilityScroll.scrollToEnd(window.decorView)) {
                TourAccessibilityScroll.ScrollOutcome.Scrolled -> delay(SCROLL_SETTLE_MILLIS)
                TourAccessibilityScroll.ScrollOutcome.NothingToScroll -> scrollNote = NO_SCROLL_NOTE
            }
        }
        awaitStableSemantics(step.id, window.decorView)
        val bitmap = window.decorView.drawToBitmap()
        currentDestinationStep = null
        activeNavigator = null
        return TourCapture(bitmap, bitmap.width, bitmap.height, note = scrollNote)
    }

    /** R-803 (halt): polls [activeNavigator] (bounded, [STATE_WAIT_TIMEOUT_MILLIS]) until it reports
     * exactly the destination and drawer-open state this step asked for, rather than assuming a
     * fixed delay always suffices — the fix for a `@2x` capture showing the previous step's own
     * still-open drawer over the right destination underneath it (the coordinator's own spot-check
     * finding, `mode-local-mic/ST01`/`mode-change-pending/CF11`/`assets-bundled`/`tier0-llm-stored`'s
     * own `CF04`/`CF11` steps). Returns `true` the moment both match; `false` on timeout — the caller
     * decides how to report that, with whatever `activeNavigator` last observed. */
    private suspend fun awaitDestinationSettled(
        destination: ReaderDestination,
        expectDrawerOpen: Boolean,
        expectReviewSessionView: ReviewSessionView?,
        expectLiveBar: Boolean,
    ): Boolean = withTimeoutOrNull(STATE_WAIT_TIMEOUT_MILLIS) {
        while (
            activeNavigator?.currentState?.value != destination ||
            activeNavigator?.drawerOpenState?.value != expectDrawerOpen ||
            (
                expectReviewSessionView != null &&
                    activeNavigator?.reviewSessionViewState?.value != expectReviewSessionView
                ) ||
            // R-910/R-911 (register, reviewer B2 on run 3): a live scenario's own bar can lag a
            // beat behind `CaptureState.isCapturing` the same way the drawer's own open/closed state
            // could — never checked when the scenario is not live, since [ReaderNavigator.liveBarState]
            // makes no promise about being `null` then (only about being non-`null` once one is real).
            (expectLiveBar && activeNavigator?.liveBarState?.value == null)
        ) {
            delay(STATE_POLL_INTERVAL_MILLIS)
        }
        true
    } == true

    private suspend fun renderSetupStep(step: TourStep): TourCapture {
        val stepName = SetupStepIds.setupStepNameFor(requireNotNull(step.setup))
            ?: error("tour step '${step.id}' names unknown setup id '${step.setup}'")
        // WPD's S10b checklist seam (this round): a setup step can also name which paired device to
        // drive through `SetupActivity.EXTRA_DEBUG_RIG_BLUETOOTH_ADDRESS` — the one drillIn key a
        // setup step reads at all (never through `TourIds.resolveSeed`, which only ever builds a
        // `NavSeed` for a *destination* step); `TourSpec.SUPPORTED_DRILL_IN_KEYS` still validates it
        // so a typo fails loudly rather than silently doing nothing.
        val rigBluetoothAddress = step.drillIn["rigBluetoothAddress"]
        // R-900 (register, A2's spot-check on run 3): `S10b-verified` == `S10b-identified` — the
        // settle above waited on `currentStepForTest` and `rigBluetoothSelectedAddressForTest`, never
        // on the checklist's own inner `RigLinkState`, so the capture could land the instant the
        // address selection landed, before the scripted port had actually progressed past
        // `Identified` to `Verified`. A step names the terminal state it expects here — resolved
        // against `SetupActivity.rigLinkStateForTest` (already exposed, WPD's own prior round) below,
        // never assumed from `rigBluetoothAddress` alone.
        val expectedRigLinkState = step.drillIn["rigLinkState"]?.let { name ->
            RIG_LINK_STATE_PREDICATES[name]
                ?: error(
                    "tour step '${step.id}' names unknown rigLinkState '$name' — expected one of ${RIG_LINK_STATE_PREDICATES.keys}",
                )
        }
        val deferred = CompletableDeferred<SetupActivity>()
        pendingSetupActivity = deferred
        startActivity(
            Intent(this, SetupActivity::class.java)
                .putExtra(SetupActivity.EXTRA_STEP, stepName)
                .putExtra(SetupActivity.EXTRA_FONT_SCALE, step.fontScale)
                .apply {
                    rigBluetoothAddress?.let { putExtra(SetupActivity.EXTRA_DEBUG_RIG_BLUETOOTH_ADDRESS, it) }
                },
        )
        val setupActivity = withTimeout(SETUP_LAUNCH_TIMEOUT_MILLIS) { deferred.await() }
        pendingSetupActivity = null
        // R-803 (halt): the resolved `setupActivity` reference can, in principle, still be the
        // *previous* step's own instance if `onActivityResumed` raced its teardown (the `isFinishing`
        // guard above narrows that, does not eliminate it) — this is the check that turns "captured
        // the wrong activity's screen" into an honest, loud error instead: a stale activity's own
        // `currentStepForTest` never advances to `stepName`, so this bound times out rather than
        // silently accepting whatever that activity happened to be showing.
        val settled = withTimeoutOrNull(STATE_WAIT_TIMEOUT_MILLIS) {
            while (setupActivity.currentStepForTest?.name != stepName) delay(STATE_POLL_INTERVAL_MILLIS)
            // The S10b checklist itself: wait for the extra's own selection to have actually landed
            // (never assumed from `startActivity` alone) before this step's own settle floor below.
            // Every `InMemoryRigLinkPort` script this package installs reaches its own stable,
            // terminal `RigLinkState` within milliseconds of selection (real Bluetooth latency does
            // not exist in this fake) — `rigBluetoothSelectedAddress` landing is therefore the one
            // fact worth an explicit wait; `DESTINATION_SETTLE_MILLIS`'s existing floor (600ms) is
            // generous cover for the connect flow's own few-millisecond run to whichever state its
            // script holds at.
            if (rigBluetoothAddress != null) {
                while (setupActivity.rigBluetoothSelectedAddressForTest != rigBluetoothAddress) {
                    delay(STATE_POLL_INTERVAL_MILLIS)
                }
            }
            // R-900: the checklist's own inner state — see this function's own note above on why
            // `rigBluetoothSelectedAddressForTest` landing is not, by itself, proof the connect flow
            // reached the specific state this step means to capture.
            if (expectedRigLinkState != null) {
                while (!expectedRigLinkState(setupActivity.rigLinkStateForTest)) {
                    delay(STATE_POLL_INTERVAL_MILLIS)
                }
            }
            true
        } == true
        if (!settled) {
            error(
                "tour step '${step.id}' never settled to setup step '$stepName'" +
                    (rigBluetoothAddress?.let { " with rigBluetoothAddress='$it' selected" } ?: "") +
                    (step.drillIn["rigLinkState"]?.let { " with rigLinkState='$it'" } ?: "") +
                    " within ${STATE_WAIT_TIMEOUT_MILLIS}ms — observed step '${setupActivity.currentStepForTest}', " +
                    "selectedAddress='${setupActivity.rigBluetoothSelectedAddressForTest}', " +
                    "linkState=${setupActivity.rigLinkStateForTest} (isFinishing=${setupActivity.isFinishing})",
            )
        }
        delay(DESTINATION_SETTLE_MILLIS)
        if (step.waitMillis > 0) delay(step.waitMillis)
        var scrollNote: String? = null
        if (step.scroll == "end") {
            when (TourAccessibilityScroll.scrollToEnd(setupActivity.window.decorView)) {
                TourAccessibilityScroll.ScrollOutcome.Scrolled -> delay(SCROLL_SETTLE_MILLIS)
                TourAccessibilityScroll.ScrollOutcome.NothingToScroll -> scrollNote = NO_SCROLL_NOTE
            }
        }
        awaitStableSemantics(step.id, setupActivity.window.decorView)
        val bitmap = setupActivity.window.decorView.drawToBitmap()
        setupActivity.finish()
        return TourCapture(bitmap, bitmap.width, bitmap.height, note = scrollNote)
    }

    /**
     * R-973 (generalises R-971): the destination/drawer/live-bar/link-state waits above prove a
     * screen composed *the right thing*, never that its own asynchronous data load has finished
     * landing — `model-missing/CF04`'s own truncated-mid-word capture (WPE's report: every row
     * composes once `ModelsController`'s state lands, with no "Loading…" text to key on) is exactly
     * that gap. Bounded, generic replacement for a text-only "Loading…" wait: captures only once two
     * [TourAccessibilityScroll.snapshot] readings taken [SEMANTICS_STABLE_INTERVAL_MILLIS] apart carry
     * the same text/`contentDescription` content and neither carries a known placeholder marker —
     * never on elapsed time or a fixed delay alone. An honest [error] on timeout, not a silent
     * capture of whatever the screen happened to show.
     */
    private suspend fun awaitStableSemantics(stepId: String, rootView: android.view.View) {
        var lastSnapshot = TourAccessibilityScroll.snapshot(rootView)
        val stable = withTimeoutOrNull(SEMANTICS_STABLE_TIMEOUT_MILLIS) {
            var previous = lastSnapshot
            while (true) {
                delay(SEMANTICS_STABLE_INTERVAL_MILLIS)
                val current = TourAccessibilityScroll.snapshot(rootView)
                lastSnapshot = current
                if (current.text == previous.text && !current.hasPlaceholder) break
                previous = current
            }
            true
        }
        if (stable != true) {
            error(
                "tour step '$stepId' never reached two consecutive stable, placeholder-free " +
                    "semantics snapshots ${SEMANTICS_STABLE_INTERVAL_MILLIS}ms apart within " +
                    "${SEMANTICS_STABLE_TIMEOUT_MILLIS}ms — last snapshot had " +
                    "hasPlaceholder=${lastSnapshot.hasPlaceholder}, text='${lastSnapshot.text.take(300)}'",
            )
        }
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

        /** R-973's own explicit ask: two [TourAccessibilityScroll.snapshot] readings "≥500ms apart". */
        private const val SEMANTICS_STABLE_INTERVAL_MILLIS = 500L

        /** Bounded the same way [STATE_WAIT_TIMEOUT_MILLIS] is — generous enough for a real, slow
         * asynchronous load (`ModelsController`'s own state, R-973) to finish landing, never so long
         * a genuinely stuck screen hangs the whole tour run. */
        private const val SEMANTICS_STABLE_TIMEOUT_MILLIS = 15_000L

        /** Coordinator round two: the manifest note for a `scroll: "end"` step whose own screen has
         * no vertically-scrollable container at all — evidence the screen fits, not a failure. */
        private const val NO_SCROLL_NOTE = "no scroll — fits"

        /** R-803 (halt): the bound `awaitDestinationSettled`/`renderSetupStep`'s own settle-wait use
         * before giving up and reporting an honest error — generous (well past a single dropped
         * frame or a backlogged dispatcher under tour load) but still a real bound, never an
         * indefinite wait.
         *
         * **R-910/R-911 (run 4a, real-device finding): widened from 5s to 20s.** `5_000L` was
         * enough for the destination/drawer/reviewSessionView checks alone (proven on-device by
         * every prior run), but the live-bar wait this round added consistently timed out at 5s on
         * a real emulator for *every* live scenario tried (`rig-bt-connected`, `mode-bluetooth`,
         * `mode-usb` — three different scenarios, two different destinations, one on Settings, one
         * on Capture), then consistently succeeded once retested at 20s — a genuine real-device cost
         * this class's own JVM tests (Robolectric, synchronous) could never surface: a live
         * scenario's own first `rememberDrawerLiveState` poll tick does several real, cold suspend
         * reads (a directory listing, two DB queries, a `RigStatus` read) before it ever reaches the
         * live-bar computation, competing with the rest of a fresh Activity's own cold-start cost —
         * confirmed by actually bisecting on a real device (5s fails every time, 20s passes every
         * time), not assumed. */
        private const val STATE_WAIT_TIMEOUT_MILLIS = 20_000L

        /** How often those same waits re-check the observed state — cheap in-process reads
         * (`MutableState`/a plain field), never worth a longer interval. */
        private const val STATE_POLL_INTERVAL_MILLIS = 30L

        /** R-900: every `rigLinkState` a setup step can name, resolved against the real
         * [RigLinkState] shapes [org.ort.app.ui.setup.InMemoryRigLinkPort]'s own scripts reach —
         * `"connecting"`/`"identified"`/`"dropped"` are each held forever by their own scenario's
         * script (`hang`/`hangAfterIdentify`/`dropAfterOpen`), `"verified"` is the terminal state
         * `useDefaultBehaviour` actually reaches. A predicate, not an equality check, since
         * [RigLinkState.Identified]/[RigLinkState.Verified] both carry a payload this map does not
         * know or need to know the value of — only the shape matters here. */
        private val RIG_LINK_STATE_PREDICATES: Map<String, (RigLinkState?) -> Boolean> = mapOf(
            "connecting" to { state -> state is RigLinkState.Opening },
            "identified" to { state -> state is RigLinkState.Identified },
            "verified" to { state -> state is RigLinkState.Verified },
            "dropped" to { state -> state is RigLinkState.Lost },
        )
    }
}
