package org.ort.app.debug.tour

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.debug.Scenarios
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * spec/ui-conformance-plan.md WP12: proves `tools/ui-audit/tour.json` itself is well-formed and
 * internally consistent — every id unique, every scenario/override name a real
 * [Scenarios.NAMES] entry, every setup id a name [SetupStepIds] knows, every destination a real
 * [org.ort.app.ui.navigation.ReaderDestination] — the same "read the real file, not a fixture that
 * can drift from it" approach `ScenariosTest`'s own suite takes with `Scenarios.NAMES`.
 * `@RunWith(RobolectricTestRunner::class)` only for `org.json` (a real implementation needs
 * Robolectric's shadow; the plain `android.jar` compile stub throws `Stub!` at runtime), not for
 * anything Android-specific this class itself does.
 */
@RunWith(RobolectricTestRunner::class)
class TourSpecTest {

    private val tourJsonFile: File by lazy {
        File("../tools/ui-audit/tour.json").canonicalFile.also {
            check(it.exists()) {
                "expected tools/ui-audit/tour.json at $it - working directory was ${File(".").canonicalFile}"
            }
        }
    }

    private fun loadSpec(): TourSpec = TourSpec.parse(tourJsonFile.readText())

    @Test
    fun `R_TOUR_PARSE tour json parses into at least one step`() {
        val spec = loadSpec()
        assertTrue("tour.json should declare at least one step", spec.steps.isNotEmpty())
    }

    @Test
    fun `R_TOUR_UNIQUE_IDS every step id is unique`() {
        val spec = loadSpec()
        val ids = spec.steps.map { it.id }
        val duplicates = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue("duplicate tour step ids: $duplicates", duplicates.isEmpty())
    }

    @Test
    fun `R_TOUR_SCENARIO_NAMES every scenario and override name a real Scenarios entry`() {
        val spec = loadSpec()
        for (step in spec.steps) {
            assertTrue(
                "step '${step.id}' names unknown scenario '${step.scenario}'",
                step.scenario in Scenarios.NAMES,
            )
            step.override?.let { override ->
                assertTrue("step '${step.id}' names unknown override scenario '$override'", override in Scenarios.NAMES)
            }
        }
    }

    @Test
    fun `R_TOUR_DESTINATION_NAMES every destination step names a real ReaderDestination`() {
        val spec = loadSpec()
        val destinationNames = org.ort.app.ui.navigation.ReaderDestination.entries.map { it.name }.toSet()
        for (step in spec.steps.filter { it.destination != null }) {
            assertTrue(
                "step '${step.id}' names unknown destination '${step.destination}'",
                step.destination in destinationNames,
            )
        }
    }

    @Test
    fun `R_TOUR_SETUP_IDS every setup step names a known S-id`() {
        val spec = loadSpec()
        for (step in spec.steps.filter { it.setup != null }) {
            assertTrue(
                "step '${step.id}' names unknown setup id '${step.setup}'",
                step.setup in SetupStepIds.KNOWN_IDS,
            )
        }
    }

    @Test
    fun `R_TOUR_SETTINGS_SCREEN destination steps carrying a settingsScreen name a real SettingsScreenId`() {
        val spec = loadSpec()
        val names = org.ort.app.ui.settings.SettingsScreenId.entries.map { it.name }.toSet()
        for (step in spec.steps) {
            step.drillIn["settingsScreen"]?.let { name ->
                assertTrue("step '${step.id}' names unknown settingsScreen '$name'", name in names)
            }
        }
    }

    @Test
    fun `R_TOUR_DRILL_IN_KEYS every drillIn key in tour json is one TourIds can resolve`() {
        val spec = loadSpec()
        for (step in spec.steps) {
            assertTrue(
                "step '${step.id}' names unsupported drillIn key(s) ${step.unsupportedDrillInKeys}",
                step.unsupportedDrillInKeys.isEmpty(),
            )
        }
    }

    @Test
    fun `R_TOUR_ONE_OF a step never mixes a destination and a setup id`() {
        val spec = loadSpec()
        for (step in spec.steps) {
            assertFalse(
                "step '${step.id}' names both a destination and a setup id",
                step.destination != null && step.setup != null,
            )
        }
    }

    @Test
    fun `R_TOUR_STRUCTURAL_ERRORS a step missing both destination and setup fails to parse`() {
        val json = """{"steps":[{"id":"x","scenario":"empty"}]}"""
        try {
            TourSpec.parse(json)
            fail("expected an IllegalArgumentException for a step with neither destination nor setup")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `R_TOUR_BOTH_REJECTED a step naming both a destination and a setup id fails to parse`() {
        val json = """{"steps":[{"id":"x","scenario":"empty","destination":"NOW","setup":"S01"}]}"""
        try {
            TourSpec.parse(json)
            fail("expected an IllegalArgumentException for a step naming both destination and setup")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `R_TOUR_SCROLL_VALUE_REJECTED a step naming an unknown scroll value fails to parse`() {
        val json = """{"steps":[{"id":"x","scenario":"empty","destination":"NOW","scroll":"top"}]}"""
        try {
            TourSpec.parse(json)
            fail("expected an IllegalArgumentException for a step naming an unsupported scroll value")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `R_TOUR_SCROLL_END_ACCEPTED every scroll step in tour json names only the supported end value`() {
        val spec = loadSpec()
        val scrollSteps = spec.steps.filter { it.scroll != null }
        assertTrue("expected at least one scroll step (R-460)", scrollSteps.isNotEmpty())
        for (step in scrollSteps) {
            assertTrue("step '${step.id}' names unsupported scroll value '${step.scroll}'", step.scroll == "end")
        }
    }
}
