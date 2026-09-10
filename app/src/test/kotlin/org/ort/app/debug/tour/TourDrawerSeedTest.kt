package org.ort.app.debug.tour

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.debug.Scenarios
import org.ort.app.ui.failures.DebugFailureOverride
import org.ort.app.ui.navigation.OrtNavHost
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

/**
 * spec/ui-conformance-plan.md WP12 v7, register R-010..R-014/R-334/R-770: `NavSeed.openDrawer` is
 * this round's own one-field/one-dispatch-line seam (`NavSeed.kt`'s own doc comment; the dispatch
 * line is `OrtNavHost.kt`'s `rememberDrawerState(if (seed?.openDrawer == true) DrawerValue.Open
 * else DrawerValue.Closed)`) — this class is the proof the coordinator's own brief asked for: a
 * drawer step actually lands on the open drawer panel itself, not merely on the destination it was
 * opened over (`ModalDrawerSheet`'s own content is *always* present in the semantics tree, open or
 * closed — Material 3 translates the whole sheet off-screen rather than removing it — so a bare
 * `assertExists()` on `drawer-rows` alone would pass even with the drawer closed; `assertIsDisplayed()`
 * additionally requires the node's own bounds to actually overlap the window, which only the *open*
 * sheet's does, confirmed by running both cases in this class before relying on the distinction).
 */
@RunWith(RobolectricTestRunner::class)
class TourDrawerSeedTest {

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

    @Test
    fun `R_TOUR_DRAWER_SEED_OPENS_THE_DRAWER openDrawer true lands on the drawer panel itself`() {
        val sessionId = runBlocking { Scenarios.load(context, "overnight") }.primarySessionId
        // Through `TourIds.resolveSeed` (the real `tour.json` drillIn -> NavSeed path
        // `ScreenshotTourActivity` itself uses), not a hand-built `NavSeed(openDrawer = true)` -
        // a hand-built seed would have passed this test while a real device run showed no drawer
        // at all, because `resolveSeed` did not actually wire `drillIn["openDrawer"]` through to
        // `NavSeed.openDrawer` until this was caught on a real device and fixed here.
        val seed = runBlocking { TourIds.resolveSeed(context, sessionId, mapOf("openDrawer" to "true")) }
        composeTestRule.setContent {
            OrtTheme { OrtNavHost(sessionId = sessionId, seed = seed) }
        }
        // The drawer's own scrollable row list (`Drawer.kt`'s real `testTag`) - not merely present,
        // genuinely on-screen, which only the open sheet achieves.
        composeTestRule.onNodeWithTag("drawer-rows").assertIsDisplayed()
    }

    @Test
    fun `R_TOUR_DRAWER_SEED_NULL_STAYS_CLOSED a null seed leaves the same drawer content off-screen`() {
        val sessionId = runBlocking { Scenarios.load(context, "overnight") }.primarySessionId
        composeTestRule.setContent {
            OrtTheme { OrtNavHost(sessionId = sessionId, seed = null) }
        }
        // Same real screen, same real drawer content composed underneath by `ModalNavigationDrawer`
        // - proof that `assertIsDisplayed()` above is a genuine open/closed distinction, not a check
        // that would have passed regardless of `openDrawer`.
        val displayed = try {
            composeTestRule.onNodeWithTag("drawer-rows").assertIsDisplayed()
            true
        } catch (notDisplayed: AssertionError) {
            false
        }
        assertFalse("drawer-rows should not be on-screen with no seed at all", displayed)
    }
}
