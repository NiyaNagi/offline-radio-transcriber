package org.ort.app.debug.tour

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.debug.Scenarios
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
 * step (composes the real [OrtNavHost] the same way [ScreenshotTourActivity] does) — 102 of
 * `tour.json`'s 111 steps at the time of writing. The 9 *setup* steps are not covered here:
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
        context.getSharedPreferences(SharedPreferencesSetupStore.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    /** One assertion this class knows how to check against a composed [OrtNavHost]. */
    private sealed interface Expected {
        data class Tag(val tag: String) : Expected
        data class AnyTag(val tags: Set<String>) : Expected
        data class Text(val text: String) : Expected
    }

    /** R-017: a drill-in's own header names its origin destination as the back label
     * (`openedFromDestination()`, `NavSeed.kt`'s own doc comment) - checked against the real
     * rendered text (confirmed by reading a real device capture before writing this; it is the
     * bare destination label, e.g. "Log", not a longer "Back to Log" sentence), the same bare
     * label [expectedForDestination] already checks for that destination's own root. `null` when
     * [drillIn] names none of the kinds this class knows how to check. */
    private fun expectedForDrillIn(drillIn: Map<String, String>): Expected? = when {
        drillIn["captureLevelMeter"] == "true" -> Expected.Tag("level-meter-chart")
        drillIn.containsKey("transmission") -> Expected.Text("Log")
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

    private fun expectedFor(step: TourStep): Expected =
        expectedForDrillIn(step.drillIn) ?: expectedForDestination(requireNotNull(step.destination))

    private data class Resolved(
        val id: String,
        val destination: ReaderDestination,
        val seed: NavSeed?,
        val sessionId: String?,
    )

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
        var current by mutableStateOf<Resolved?>(null)
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

        val failures = mutableListOf<String>()
        for (step in destinationSteps) {
            val loadResult = runBlocking { Scenarios.load(context, step.scenario) }
            val navSeed = runBlocking { TourIds.resolveSeed(context, loadResult.primarySessionId, step.drillIn) }
            val destination = ReaderDestination.entries.first { it.name == step.destination }

            current = Resolved(step.id, destination, navSeed, loadResult.primarySessionId)
            composeTestRule.waitForIdle()

            val expected = expectedFor(step)
            val found = when (expected) {
                is Expected.Tag -> composeTestRule.onAllNodesWithTag(expected.tag).fetchSemanticsNodes().isNotEmpty()
                is Expected.AnyTag -> expected.tags.any {
                    composeTestRule.onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty()
                }
                is Expected.Text -> composeTestRule.onAllNodes(hasText(expected.text, substring = true))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            if (!found) failures += "${step.id}: expected $expected, not found"
        }

        assertTrue("wrong-screen captures:\n${failures.joinToString("\n")}", failures.isEmpty())
    }
}
