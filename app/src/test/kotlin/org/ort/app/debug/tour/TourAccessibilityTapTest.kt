package org.ort.app.debug.tour

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

/**
 * WPW (register R-1007 follow-up): [TourAccessibilityTap] is the real route
 * `ScreenshotTourActivity`'s own `tapLiveBar` drillIn uses to reach `LiveMonitorScreen` — no
 * `NavSeed` field exists to seed it directly. A real [ComponentActivity] (not a bare
 * `createComposeRule()`, which `TourStepsTest`'s own doc comment notes has no real window to walk),
 * mirroring exactly the technique `org.ort.app.ui.components.LiveBar` itself uses: a `.clickable(...)`
 * modifier plus `clearAndSetSemantics` naming a `contentDescription`, a `Role.Button`, and an
 * `onClick` action.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TourAccessibilityTapTest {

    @Test
    fun `tapClickableNodeWithText finds and taps the real node carrying that exact text`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        var tapped = false
        activity.setContent {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clickable(role = Role.Button, onClickLabel = "Live", onClick = { tapped = true })
                    .clearAndSetSemantics {
                        contentDescription = "Live"
                        role = Role.Button
                        onClick(label = "Live") {
                            tapped = true
                            true
                        }
                    },
            ) {
                Text("meter bars")
            }
        }
        shadowOf(Looper.getMainLooper()).idle()

        val outcome = TourAccessibilityTap.tapClickableNodeWithText(activity.window.decorView, "Live")

        assertEquals(TourAccessibilityTap.TapOutcome.Tapped, outcome)
        assertEquals(
            "the real click handler must have actually run, not merely been located",
            true,
            tapped,
        )
    }

    @Test
    fun `tapClickableNodeWithText reports NotFound when nothing on screen carries that exact text`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { Text("something else entirely") }
        shadowOf(Looper.getMainLooper()).idle()

        val outcome = TourAccessibilityTap.tapClickableNodeWithText(activity.window.decorView, "Live")

        assertEquals(TourAccessibilityTap.TapOutcome.NotFound, outcome)
    }

    @Test
    fun `tapClickableNodeWithText never matches a non-clickable node carrying the same text`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        var tapped = false
        activity.setContent {
            // A plain, non-clickable node whose own text happens to be "Live" — must never be
            // mistaken for the real, clickable live bar.
            Text("Live")
        }
        shadowOf(Looper.getMainLooper()).idle()

        val outcome = TourAccessibilityTap.tapClickableNodeWithText(activity.window.decorView, "Live")

        assertEquals(TourAccessibilityTap.TapOutcome.NotFound, outcome)
        assertFalse(tapped)
    }
}
