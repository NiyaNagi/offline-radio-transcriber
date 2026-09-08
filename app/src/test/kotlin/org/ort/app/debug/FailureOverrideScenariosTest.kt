package org.ort.app.debug

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.failures.DebugFailureOverride
import org.ort.app.ui.failures.FailurePresentation
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * `ScenariosTest.kt` split — detekt's `LargeClass` finding, the same fix `RowsTest.kt`'s own
 * `NavRowTest.kt` split already establishes as this codebase's house style (`CHANGELOG.md`: "Rather
 * than suppress, ... moved verbatim into a new file"). This is WP11b (register R-100)'s own
 * self-contained section verbatim: the seven scenario ids with no runtime signal today, driven
 * entirely through [DebugFailureOverride] rather than `:data`/the process-wide capture facets
 * [ScenariosTest] itself resets in `@After` — each scenario's only real job is to set the exact
 * [FailurePresentation] its board needs, so these tests assert exactly that rather than re-testing
 * the composables (`FailureScreensTest` already does that). Untouched otherwise: same assertions,
 * same test names, same [Requirement] tags.
 */
@RunWith(RobolectricTestRunner::class)
class FailureOverrideScenariosTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun resetProcessWideAvailability() {
        DebugFailureOverride.clear()
    }

    @Test
    @Requirement("F-014", "R-100")
    fun `F14_clock-dst sets the Clock debug override`() = runTest {
        Scenarios.load(context, "clock-dst")
        assertTrue(DebugFailureOverride.current is FailurePresentation.Clock)
    }

    @Test
    @Requirement("R-147")
    fun `R_147 F14_clock-dst carries the facts and Around the change log the full screen needs`() = runTest {
        Scenarios.load(context, "clock-dst")
        val presentation = DebugFailureOverride.current
        assertTrue(presentation is FailurePresentation.Clock)
        presentation as FailurePresentation.Clock
        assertTrue(presentation.state.nightLabel.isNotEmpty())
        assertTrue(presentation.state.windowLabel.isNotEmpty())
        assertTrue(presentation.state.logRows.isNotEmpty())
    }

    @Test
    @Requirement("F-016", "R-100")
    fun `F16_usb-permission sets the Usb debug override`() = runTest {
        Scenarios.load(context, "usb-permission")
        assertTrue(DebugFailureOverride.current is FailurePresentation.Usb)
    }

    @Test
    @Requirement("F-017", "R-100")
    fun `F17_interrupted-pass sets the Interrupted debug override`() = runTest {
        Scenarios.load(context, "interrupted-pass")
        assertTrue(DebugFailureOverride.current is FailurePresentation.Interrupted)
    }

    @Test
    @Requirement("R-147")
    fun `R_147 F17_interrupted-pass carries the backlog label and the 3 overs list the full screen needs`() = runTest {
        Scenarios.load(context, "interrupted-pass")
        val presentation = DebugFailureOverride.current
        assertTrue(presentation is FailurePresentation.Interrupted)
        presentation as FailurePresentation.Interrupted
        assertTrue(presentation.state.backlogLabel.isNotEmpty())
        assertEquals(3, presentation.state.overs.size)
    }

    @Test
    @Requirement("F-019", "R-100")
    fun `F19_reconcile sets the Reconcile debug override with both mismatch directions`() = runTest {
        Scenarios.load(context, "reconcile")
        val presentation = DebugFailureOverride.current
        assertTrue(presentation is FailurePresentation.Reconcile)
        presentation as FailurePresentation.Reconcile
        assertTrue(presentation.state.recordsNoFile.isNotEmpty())
        assertTrue(presentation.state.filesNoRecord.isNotEmpty())
    }

    @Test
    @Requirement("F-020", "R-100")
    fun `F20_migration-failed sets the Migration debug override with a failed step`() = runTest {
        Scenarios.load(context, "migration-failed")
        val presentation = DebugFailureOverride.current
        assertTrue(presentation is FailurePresentation.Migration)
        presentation as FailurePresentation.Migration
        assertTrue(presentation.state.steps.any { !it.ok })
    }

    @Test
    @Requirement("F-021", "R-100")
    fun `F21_asset-swap sets the AssetSwap debug override`() = runTest {
        Scenarios.load(context, "asset-swap")
        assertTrue(DebugFailureOverride.current is FailurePresentation.AssetSwap)
    }

    @Test
    @Requirement("R-148")
    fun `R_148 F21_asset-swap's options each carry a real sub-line, not a bare label`() = runTest {
        Scenarios.load(context, "asset-swap")
        val presentation = DebugFailureOverride.current
        assertTrue(presentation is FailurePresentation.AssetSwap)
        presentation as FailurePresentation.AssetSwap
        assertTrue(presentation.state.options.isNotEmpty())
        presentation.state.options.forEach { option -> assertTrue(option.subLine.isNotEmpty()) }
    }

    @Test
    @Requirement("F-022", "R-100")
    fun `F22_calibration sets the Calibration debug override`() = runTest {
        Scenarios.load(context, "calibration")
        assertTrue(DebugFailureOverride.current is FailurePresentation.Calibration)
    }

    @Test
    @Requirement("R-100")
    fun `R_100 loading a real-signal scenario clears a prior debug override`() = runTest {
        Scenarios.load(context, "clock-dst")
        assertNotNull(DebugFailureOverride.current)

        Scenarios.load(context, "thermal")

        assertNull("a later scenario must not leave the previous one's override behind", DebugFailureOverride.current)
    }
}
