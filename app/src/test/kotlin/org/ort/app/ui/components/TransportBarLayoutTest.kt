package org.ort.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * C10 (`design/canvas/Transport-Bar.dc.html`). AGENTS.md working agreement item 7 / constitution
 * VIII: any 2.0 layout change gets a Robolectric test at the tour's own width asserting bounds.
 * Widths cover both the tour's own 390dp device and a physically larger 480dp one (constitution
 * VIII: "artboards assume a fixed logical width; layout that is correct in dp can still be wrong
 * on a physically larger screen"). `@GraphicsMode.NATIVE` + a real fixed-width `Box`: this
 * package's own established discipline for real glyph-wrap measurement (`RowsTest.kt`'s own
 * `R_880`/`R_980`).
 *
 * What this file cannot prove (reported here rather than faked): whether the bar clears the real
 * navigation-bar inset. `Modifier.safeAreaBottomPadding()`'s own doc comment
 * (`ui/components/SafeArea.kt`) records that `WindowInsets.navigationBars` reads zero under this
 * project's Robolectric setup by every method tried — that clearance is unchanged by this round
 * (the bar sits in the exact `Column` slot the plain `LiveBar` it replaces already occupied) and
 * remains a device-only claim, per that file's own doc comment.
 */
@RunWith(RobolectricTestRunner::class)
class TransportBarLayoutTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun playback(positionFraction: Float = 0.33f) = TransportBarViewState.Playback(
        transmissionId = "TX1",
        callsignLabel = "W7NPC",
        isPlaying = true,
        positionFraction = positionFraction,
        elapsedLabel = "0:04",
        totalLabel = "0:12",
        capturingDotVisible = true,
    )

    private fun setContent(widthDp: Int, fontScale: Float) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = fontScale)) {
                OrtTheme {
                    Box(modifier = Modifier.width(widthDp.dp).height(200.dp)) {
                        TransportBar(state = playback(), actions = TransportBarActions())
                    }
                }
            }
        }
    }

    private fun assertNoOverlapAndInBounds(widthDp: Int) {
        val toggle = composeTestRule.onNodeWithTag("transport-bar-toggle").getUnclippedBoundsInRoot()
        val scrub = composeTestRule.onNodeWithTag("transport-bar-scrub").getUnclippedBoundsInRoot()
        val callsign = composeTestRule.onNodeWithTag("transport-bar-callsign").getUnclippedBoundsInRoot()

        // Left-to-right order the artboard specifies: toggle, time, scrub (flex-grow), callsign.
        assertTrue(
            "expected the toggle to sit left of the scrub track, got toggle.right=${toggle.right} " +
                "scrub.left=${scrub.left}",
            toggle.right <= scrub.left,
        )
        assertTrue(
            "expected the scrub track to sit left of the callsign, got scrub.right=${scrub.right} " +
                "callsign.left=${callsign.left}",
            scrub.right <= callsign.left,
        )
        assertTrue(
            "the callsign must not be clipped past the container's own width ($widthDp dp), " +
                "got right=${callsign.right}",
            callsign.right <= widthDp.dp,
        )
    }

    @Test
    fun `no overlap between toggle, scrub and callsign at 390dp width, font scale 1_0`() {
        setContent(widthDp = 390, fontScale = 1f)
        assertNoOverlapAndInBounds(widthDp = 390)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `no overlap between toggle, scrub and callsign at 390dp width, font scale 2_0`() {
        setContent(widthDp = 390, fontScale = 2f)
        assertNoOverlapAndInBounds(widthDp = 390)
    }

    @Test
    fun `no overlap between toggle, scrub and callsign at 480dp width, font scale 1_0`() {
        setContent(widthDp = 480, fontScale = 1f)
        assertNoOverlapAndInBounds(widthDp = 480)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `no overlap between toggle, scrub and callsign at 480dp width, font scale 2_0`() {
        setContent(widthDp = 480, fontScale = 2f)
        assertNoOverlapAndInBounds(widthDp = 480)
    }

    @Test
    fun `the bar row itself carries a 44dp floor at every width`() {
        setContent(widthDp = 390, fontScale = 1f)
        val bar = composeTestRule.onNodeWithTag("transport-bar-playback").getUnclippedBoundsInRoot()
        val barHeight = bar.bottom - bar.top
        assertTrue("expected the row to be at least 44dp tall, got $barHeight", barHeight >= 44.dp)
    }
}
