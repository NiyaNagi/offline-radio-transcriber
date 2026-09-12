package org.ort.app.debug.tour

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.components.LiveBar
import org.ort.app.ui.components.LiveBarTone
import org.ort.app.ui.components.LiveBarViewState
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

/** Mirrors exactly what `ScreenshotTourActivity`'s own composition root does for every real tour
 * step — `testTag` is invisible to the platform accessibility tree (and so to
 * [AccessibilityNodeInfo.getViewIdResourceName]) unless some ancestor opts into
 * [testTagsAsResourceId]; see [TourAccessibilityTap]'s own doc comment for the empirical finding
 * behind this. Debug-tour-only, never a production composable. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun WithTestTagsAsResourceId(content: @Composable () -> Unit) {
    Box(modifier = Modifier.semantics { testTagsAsResourceId = true }) { content() }
}

/**
 * WPW (register R-1007 follow-up), **R-1021** (the lead's own field report against the real
 * tour): [TourAccessibilityTap] is the real route `ScreenshotTourActivity`'s own `tapLiveBar`
 * drillIn uses to reach `LiveMonitorScreen` — no `NavSeed` field exists to seed it directly. A real
 * [ComponentActivity] (not a bare `createComposeRule()`, which `TourStepsTest`'s own doc comment
 * notes has no real window to walk).
 *
 * **R-1021: matching on the live bar's own *label* (`"Live"`) was itself the defect, not a testing
 * shortcut that happened to work.** `"Live"` is only [org.ort.app.ui.data.LiveBarPolling
 * .toneAndLabel]'s own `else` branch — the *last* entry in a real priority ladder outranked by
 * `"Gap"`, `"N behind"`, `"Tier N"`, `"Rig lost"` and six others. `overnight-live-monitor` (this
 * package's own scenario, WPW) deliberately seeds a gap and a queue backlog — two of the seven
 * `Live-Monitor.dc.html` states that scenario exists to carry — so the real bar correctly reads
 * `"Gap"`/`"N behind"`, and a tap keyed on the literal string `"Live"` finds nothing. This is
 * constitution II's "never assert prose... that a designer may legitimately change tomorrow"
 * appearing in the capture tooling itself, not a product defect and not something the scenario
 * should be changed to paper over (that would delete two of the seven states the capture exists to
 * prove). The fix: tap by [org.ort.app.ui.components.LiveBar]'s own stable `testTag("live-bar")`,
 * carried on its outer `Column` regardless of tone/label/partial text, never by copy.
 *
 * Every test below builds the *real* `LiveBar` composable (never a synthetic clickable `Box`) at a
 * `LiveBarTone.DEGRADED`/`"Gap"` state — the exact shape that made the field tour fail — so a
 * regression back to label-matching fails these tests for the right reason, not by accident.
 * `TourAccessibilityTap.tapClickableNodeWithText` (the label-matching entry point R-1007's own
 * round added and R-1021 replaced) was removed outright, not merely superseded — this round's own
 * audit found it unused by any real `tour.json` step, and a public, unused text-matching entry
 * point is exactly the standing invitation to reintroduce this defect that R-1021 exists to close.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TourAccessibilityTapTest {

    /** `overnight-live-monitor`'s own real shape: a degraded bar reading `"Gap"`, never `"Live"` —
     * see this class's own doc comment for why that is correct, not a fixture bug. */
    private val degradedGapState = LiveBarViewState(
        level = listOf(0f, 0f, 0f, 0f),
        partialText = null,
        label = "Gap",
        tone = LiveBarTone.DEGRADED,
    )

    @Test
    fun `R_1021 tapNodeWithTestTag finds and taps the real live bar even though its label is not Live`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        var tapped = false
        activity.setContent {
            WithTestTagsAsResourceId { LiveBar(state = degradedGapState, onClick = { tapped = true }) }
        }
        shadowOf(Looper.getMainLooper()).idle()

        val outcome = TourAccessibilityTap.tapNodeWithTestTag(activity.window.decorView, "live-bar")

        assertEquals(TourAccessibilityTap.TapOutcome.Tapped, outcome)
        assertEquals("the real onClick handler must have actually run, not merely been located", true, tapped)
    }

    @Test
    fun `tapNodeWithTestTag reports NotFound when no node anywhere carries that tag`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { WithTestTagsAsResourceId { Text("something else entirely") } }
        shadowOf(Looper.getMainLooper()).idle()

        val outcome = TourAccessibilityTap.tapNodeWithTestTag(activity.window.decorView, "live-bar")

        assertEquals(TourAccessibilityTap.TapOutcome.NotFound, outcome)
    }

    @Test
    fun `tapNodeWithTestTag never matches a differently-tagged clickable node`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        var tapped = false
        activity.setContent {
            WithTestTagsAsResourceId {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("not-the-live-bar")
                        .clickable(role = Role.Button, onClick = { tapped = true })
                        .clearAndSetSemantics {
                            contentDescription = "unrelated"
                            role = Role.Button
                            onClick(label = null) {
                                tapped = true
                                true
                            }
                        },
                ) {
                    Text("unrelated control")
                }
            }
        }
        shadowOf(Looper.getMainLooper()).idle()

        val outcome = TourAccessibilityTap.tapNodeWithTestTag(activity.window.decorView, "live-bar")

        assertEquals(TourAccessibilityTap.TapOutcome.NotFound, outcome)
        assertFalse(tapped)
    }

    @Test
    fun `hasNodeWithTestTag finds a real tagged node without needing it to be clickable`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            WithTestTagsAsResourceId { LiveBar(state = degradedGapState, onClick = {}) }
        }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(true, TourAccessibilityTap.hasNodeWithTestTag(activity.window.decorView, "live-bar"))
        assertEquals(false, TourAccessibilityTap.hasNodeWithTestTag(activity.window.decorView, "no-such-tag"))
    }
}
