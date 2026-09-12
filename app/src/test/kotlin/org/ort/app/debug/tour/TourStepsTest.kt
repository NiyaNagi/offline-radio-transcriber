package org.ort.app.debug.tour

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.debug.DebugBundledAssetSourceOverride
import org.ort.app.debug.Scenarios
import org.ort.app.debug.TinyFixtureBundledAssetSource
import org.ort.app.ui.failures.DebugFailureOverride
import org.ort.app.ui.navigation.NavSeed
import org.ort.app.ui.navigation.OrtNavHost
import org.ort.app.ui.navigation.ReaderDestination
import org.ort.app.ui.navigation.rememberReaderNavigator
import org.ort.app.ui.setup.SharedPreferencesSetupStore
import org.ort.app.ui.theme.OrtTheme
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
 * spec/ui-conformance-plan.md WP12 v3, register R-410: proves every *destination* step in the real
 * `tools/ui-audit/tour.json` lands on the screen it claims to, catching a wrong-screen capture (the
 * exact bug class R-410 found by eye — `setup-verified/S02b-mic-denied` actually captured S12) as a
 * failing test rather than a reviewer's own manual comparison.
 *
 * **Scope, stated plainly rather than silently narrowed**: this class covers every *destination*
 * step (composes the real [OrtNavHost] the same way [ScreenshotTourActivity] does) — 123 of
 * `tour.json`'s 142 steps at the time of writing (v5, register R-460, added `scroll: "end"`
 * variants of six existing destination steps plus five setup-only steps — the former are covered
 * here automatically since they carry the same `destination`/`drillIn` as their non-scrolled
 * sibling; [TourAccessibilityScroll] itself is exercised only on a real device, this class having
 * no window to scroll; v6, WP8's `stationSubScreen` seam, added ST03/ST04 as four more destination
 * steps). The 19 *setup* steps are not covered here:
 * `SetupActivity`'s screen rendering is a set of private methods on the `Activity` itself (see
 * `ScreenshotTourActivity`'s own doc comment, confirmed by reading `SetupActivity.kt`), so there is
 * no composable this class can call directly the way it calls `OrtNavHost` — their correctness rests
 * on the real-device tour runs this package's report cites (93/93 and 111/111 across two full runs),
 * not on a JVM-side assertion. A future round could add a lighter, Activity-free check once
 * `SetupActivity`'s per-step content is itself extracted into a callable composable (`ui/setup`'s
 * row, not this package's).
 *
 * Every expected marker below is a real `testTag` or a real title string this codebase already
 * asserts against elsewhere (`OrtNavHostDestinationDispatchTest`'s own per-destination title checks,
 * `CaptureStatusScreen.kt`'s/`SearchScreen.kt`'s/`ThreadDetailScreen.kt`'s own `testTag`s) — never a
 * guessed string invented for this test alone.
 */
@RunWith(RobolectricTestRunner::class)
class TourStepsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

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
        // Register, CI regression: backstop for R_TOUR_STEPS's own override (already cleared in
        // its own `finally`) -- catches a future test that forgets to.
        DebugBundledAssetSourceOverride.clear()
        context.getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    /** One assertion this class knows how to check against a composed [OrtNavHost]. */
    private sealed interface Expected {
        data class Tag(val tag: String) : Expected
        data class AnyTag(val tags: Set<String>) : Expected
        data class Text(val text: String) : Expected

        /** v7 (register R-010..R-014/R-334/R-770): [Tag] alone only proves a node with this tag
         * exists *somewhere* in the tree - `ModalNavigationDrawer`'s own `drawerContent` is always
         * composed, open or closed (confirmed directly: [TourDrawerSeedTest]'s own closed case finds
         * `drawer-rows` present but off-screen), so a bare [Tag] check on it would pass even if
         * `openDrawer` silently did nothing. This variant additionally requires the node's own
         * bounds to actually be on-screen, the same distinction [TourDrawerSeedTest] uses.
         */
        data class DisplayedTag(val tag: String) : Expected
    }

    /** R-017: a drill-in's own header names its origin destination as the back label
     * (`openedFromDestination()`, `NavSeed.kt`'s own doc comment) - checked against the real
     * rendered text (confirmed by reading a real device capture before writing this; it is the
     * bare destination label, e.g. "Log", not a longer "Back to Log" sentence), the same bare
     * label [expectedForDestination] already checks for that destination's own root. `null` when
     * [drillIn] names none of the kinds this class knows how to check. */
    private fun expectedForDrillIn(drillIn: Map<String, String>): Expected? = when {
        drillIn["captureLevelMeter"] == "true" -> Expected.Tag("level-meter-chart")
        // R-261: an open sheet blocks the tree behind it (confirmed by `TourStepsTest` itself, run
        // once and read - the query field genuinely disappears while `search-filters-sheet` shows),
        // so these two check the sheet's own content, never the destination root underneath it.
        drillIn["searchFiltersOpen"] == "true" -> Expected.Tag("search-filters-sheet")
        drillIn["logSheetOpen"] == "true" -> Expected.Text("Filter the log")
        // v7 (register R-010..R-014/R-334/R-770): the drawer's own scrollable row list, required to
        // be genuinely on-screen (`Expected.DisplayedTag`'s own doc comment) - checked before the
        // generic destination branch below so a drawer step is proven to land on the open drawer
        // itself, never merely on the destination it was opened over.
        drillIn["openDrawer"] == "true" -> Expected.DisplayedTag("drawer-rows")
        // v7 (register R-770): F04's own detail - `TransmissionDetailScreen.kt`'s
        // `RejectedHeaderSection`, real `testTag`, checked before the generic `transmission` branch
        // below so this is proven to be the rejected detail, not merely any transmission detail.
        drillIn["transmission"] == "rejected" -> Expected.Tag("rejected-section")
        drillIn.containsKey("transmission") -> Expected.Text("Log")
        // ST03/ST04 (WP8's `initialSubScreen` seam): checked before the bare `station` branch
        // below - `Station-Pattern`/`Station-Identity` draw their own `DrillInHeader` with
        // `parentLabel` set to the station's own callsign/label (`StationPatternScreen.kt`/
        // `StationIdentityScreen.kt`, confirmed by reading both before writing this), never the
        // "Stations" origin-destination label the plain station root (ST02) shows - so the two
        // real, on-screen titles this class already knows are real strings this app renders
        // (`StationPatternScreen.kt`'s "When they are around", `StationIdentityScreen.kt`'s "How
        // this station is known") are the honest check here, not a guessed one.
        drillIn["stationSubScreen"] == "PATTERN" -> Expected.Text("When they are around")
        drillIn["stationSubScreen"] == "IDENTITY" -> Expected.Text("How this station is known")
        drillIn.containsKey("station") -> Expected.Text("Stations")
        drillIn.containsKey("thread") -> Expected.Text("Threads")
        drillIn.containsKey("frequency") -> Expected.Text("Frequencies")
        drillIn.containsKey("logFilterFrequency") -> Expected.Text("Log")
        drillIn.containsKey("reviewSession") -> Expected.Text("Earlier nights")
        // Every Settings sub-screen carries its own "‹ Settings" back chevron (confirmed by
        // reading a real device capture of Settings-Capture/-Storage/-Assets before writing
        // this) - a weaker check than a per-sub-screen title, but one this class can make for
        // all nine sub-screens without guessing text it has not actually seen rendered.
        drillIn.containsKey("settingsScreen") -> Expected.Text("Settings")
        else -> null
    }

    private fun expectedForDestination(destination: String): Expected = when (destination) {
        "NOW" -> Expected.AnyTag(setOf("now-idle-title", "now-active-title"))
        "LOG" -> Expected.Text("Log")
        "SEARCH" -> Expected.Tag("search-query-field")
        "THREADS" -> Expected.Text("Threads")
        "STATIONS" -> Expected.Text("Stations")
        "FREQUENCIES" -> Expected.Text("Frequencies")
        "CAPTURE" -> Expected.Tag("capture-status-title")
        "IMPROVE_RECORDS" -> Expected.Text("Improve records")
        "EARLIER_NIGHTS" -> Expected.Text("Earlier nights")
        "SETTINGS" -> Expected.Text("Settings")
        else -> error("TourStepsTest has no expected marker for destination '$destination'")
    }

    /** A handful of steps share a `destination`/`drillIn` shape with a sibling step that lands on a
     * genuinely different real state (v7, register R-770) — [expectedForDestination]/
     * [expectedForDrillIn] alone cannot tell them apart, since both are keyed on shape, not id. Real
     * strings only, the same rule the two functions above already follow. */
    private fun expectedForStepId(id: String): Expected? = when (id) {
        // `ThreadListMapper.listState`: no transmission `field-tier1` seeds ever carries a
        // `threadId` (only `OvernightScenario` sets one), and `field-tier1` (unlike
        // `stations-14-nights`, confirmed empirically on-device before switching to this scenario -
        // its own transmissions belong to no single "current" session `ThreadPolling` reads, so it
        // renders the *Empty* state instead) has one real, current session's worth of real overs -
        // `UngroupedThreads`' own real prose, the fact that distinguishes T03
        // (`Threads-Ungrouped.dc.html`) from both Empty and a T01 `Grouped` card list.
        "field-tier1/T03-threads-ungrouped" -> Expected.Text("Conversations are not built on this phone yet")
        else -> null
    }

    private fun expectedFor(step: TourStep): Expected = expectedForStepId(step.id)
        ?: expectedForDrillIn(step.drillIn)
        ?: expectedForDestination(requireNotNull(step.destination))

    private data class Resolved(
        val id: String,
        val destination: ReaderDestination,
        val seed: NavSeed?,
        val sessionId: String?,
    )

    /**
     * Register, CI regression (`OutOfMemoryError` at
     * `org.robolectric.res.android.Asset$_CompressedAsset.getBuffer`, Release workflow run
     * 34664670909, commit `5e07273f`): this test's own scenario loop reaches
     * `assets-bundled`/`asset-corrupt`/`tier0-llm-stored`'s own destination steps, each calling
     * `Scenarios.load` -> `installRealBundledAssets` -> the real ~555MB gated LLM asset once a real
     * `HF_TOKEN` build makes it genuinely present (`mergeDebugAssets`'s own `dependsOn
     * (fetchBundledAssets)`, register) — Robolectric's own asset reader inflates a compressed
     * asset's entire uncompressed content into the test JVM's heap to serve it. This test's own
     * purpose is proving *navigation* (every destination step lands on the screen it claims to),
     * not install fidelity, so it gets the same [DebugBundledAssetSourceOverride] fixture-sized
     * source `ScenariosTest`'s own `R_110 ...five times...`/`R_110 every declared scenario name...`
     * already use, for the same reason, in the same `try`/`finally` shape.
     */
    /** The one step this test's own `setContent` renders at a time — a class-level property
     * (rather than a `var` local to the test method, as before R-807/the CI regression fix) purely
     * so [checkStep] can be extracted to a method of its own, keeping the test method itself under
     * detekt's `NestedBlockDepth` once the `try`/`finally` this fix added wraps the loop. */
    private var current by mutableStateOf<Resolved?>(null)

    @Test
    fun `R_TOUR_STEPS every destination step in tour json lands on the screen it claims to`() {
        val tourJsonFile = File("../tools/ui-audit/tour.json").canonicalFile
        check(tourJsonFile.exists()) { "expected tools/ui-audit/tour.json at $tourJsonFile" }
        val spec = TourSpec.parse(tourJsonFile.readText())
        val destinationSteps = spec.steps.filter { it.destination != null }
        assertTrue("expected at least one destination step", destinationSteps.isNotEmpty())

        // `setContent` can only be called once per test (Compose UI test's own rule, found by
        // actually running this, not by inspection) - one composition drives every step instead,
        // keyed on the step id so each gets a fresh `rememberReaderNavigator`/`NavHostNavState` the
        // same way `ScreenshotTourActivity` itself relies on `key(...)` for the identical reason.
        composeTestRule.setContent {
            OrtTheme {
                val resolved = current
                if (resolved != null) {
                    key(resolved.id) {
                        val navigator = rememberReaderNavigator(
                            initialDestination = resolved.destination,
                            initialSettingsScreen = resolved.seed?.settingsScreen,
                            seed = resolved.seed,
                        )
                        OrtNavHost(sessionId = resolved.sessionId, seed = resolved.seed, navigator = navigator)
                    }
                }
            }
        }

        DebugBundledAssetSourceOverride.override = TinyFixtureBundledAssetSource(context.filesDir)
        val failures = mutableListOf<String>()
        try {
            destinationSteps.forEach { step -> checkStep(step)?.let { failures += it } }
        } finally {
            DebugBundledAssetSourceOverride.clear()
        }

        assertTrue("wrong-screen captures:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    /** One step of `R_TOUR_STEPS...`'s own loop, extracted so that test method stays under
     * detekt's `NestedBlockDepth` — returns a failure message, or `null` once the step's own
     * expected marker is found. */
    private fun checkStep(step: TourStep): String? {
        val loadResult = runBlocking { Scenarios.load(context, step.scenario) }
        val navSeed = runBlocking { TourIds.resolveSeed(context, loadResult.primarySessionId, step.drillIn) }
        val destination = ReaderDestination.entries.first { it.name == step.destination }

        current = Resolved(step.id, destination, navSeed, loadResult.primarySessionId)
        composeTestRule.waitForIdle()

        val expected = expectedFor(step)
        fun isFound(): Boolean = when (expected) {
            is Expected.Tag -> composeTestRule.onAllNodesWithTag(expected.tag).fetchSemanticsNodes().isNotEmpty()
            is Expected.AnyTag -> expected.tags.any {
                composeTestRule.onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty()
            }
            is Expected.Text -> composeTestRule.onAllNodes(hasText(expected.text, substring = true))
                .fetchSemanticsNodes().isNotEmpty()
            is Expected.DisplayedTag -> try {
                composeTestRule.onNodeWithTag(expected.tag).assertIsDisplayed()
                true
            } catch (notDisplayed: AssertionError) {
                false
            }
        }
        // Some states (a sheet whose own content waits on a nested poll, e.g. Log's filter sheet's
        // facet counts) are not necessarily settled by one `waitForIdle` - retry with `waitUntil`
        // before calling a step a real wrong-screen failure.
        val found = if (isFound()) {
            true
        } else {
            try {
                composeTestRule.waitUntil(WAIT_UNTIL_TIMEOUT_MILLIS) { isFound() }
                true
            } catch (timeout: Exception) {
                false
            }
        }
        return if (found) null else "${step.id}: expected $expected, not found"
    }

    private companion object {
        const val WAIT_UNTIL_TIMEOUT_MILLIS = 10_000L
    }
}
