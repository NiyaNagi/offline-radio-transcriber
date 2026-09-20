package org.ort.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * D50 (Q22 closed, FR-SEG-10, AC-162): AGENTS.md working agreement item 7 / constitution VIII — a
 * row change under a `ui/components` file gets a Robolectric layout test at the tour's own device
 * qualifier (`w390dp-h844dp-420dpi`), native graphics, asserting real measured bounds at both font
 * scales the tour exercises. [LiveBar]'s own row now carries an additional mark
 * ([VadFallbackMark]) between the room-audio mark and the label — this is the evidence that the
 * new mark neither clips the label nor pushes the bar past its own 44dp floor.
 */
@RunWith(RobolectricTestRunner::class)
class LiveBarLayoutTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun fallbackState(localMicrophone: Boolean = false) = LiveBarViewState(
        level = listOf(0.4f, 1f, 0.6f, 1f),
        partialText = "and we're clear on the repeater",
        label = "Live",
        tone = LiveBarTone.NOMINAL,
        vadFallback = true,
        localMicrophone = localMicrophone,
    )

    private fun setContent(fontScale: Float, localMicrophone: Boolean = false) {
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = LocalDensity.current.density, fontScale = fontScale),
            ) {
                OrtTheme {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        LiveBar(
                            state = fallbackState(localMicrophone),
                            onClick = {},
                            modifier = Modifier.testTag("bar"),
                        )
                    }
                }
            }
        }
    }

    private fun assertMarkAndLabelFitWithoutOverlap() {
        val bar = composeTestRule.onNodeWithTag("live-bar").getUnclippedBoundsInRoot()
        val mark = composeTestRule.onNodeWithTag("live-bar-vad-fallback-mark", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val label = composeTestRule.onNodeWithText("Live", useUnmergedTree = true).getUnclippedBoundsInRoot()

        val barHeight = bar.bottom - bar.top
        assertTrue("expected the live bar to keep its 44dp floor, got $barHeight", barHeight >= 44.dp)
        assertTrue(
            "expected the fallback mark (right=${mark.right}) to sit left of the label (left=${label.left}), " +
                "never overlapping it",
            mark.right <= label.left,
        )
        assertTrue(
            "expected the fallback mark to render with a real, non-zero width, never clipped to nothing, " +
                "got ${mark.right - mark.left}",
            mark.right - mark.left > 0.dp,
        )
        assertTrue(
            "expected the label to stay within the bar's own right edge (bar.right=${bar.right}), got " +
                "label.right=${label.right}",
            label.right <= bar.right,
        )
    }

    @Test
    @Requirement("D50", "FR-SEG-10", "AC-162")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `D50 the fallback mark and the label never overlap at 390dp font scale 1_0`() {
        setContent(fontScale = 1f)
        assertMarkAndLabelFitWithoutOverlap()
    }

    @Test
    @Requirement("D50", "FR-SEG-10", "AC-162")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `D50 the fallback mark and the label never overlap at 390dp font scale 2_0`() {
        setContent(fontScale = 2f)
        assertMarkAndLabelFitWithoutOverlap()
    }

    /** E2-G02's room mark and D50's fallback mark can appear together (a room-audio session can
     * also be running on the fallback VAD) — asserted here so a future change to either mark's own
     * spacing cannot silently make them collide. */
    @Test
    @Requirement("D50", "FR-SEG-10", "FR-CAP-3a")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `D50 the fallback mark and the room-audio mark coexist without overlapping, font scale 2_0`() {
        setContent(fontScale = 2f, localMicrophone = true)
        val room = composeTestRule.onNodeWithTag("live-bar-room-mark", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val mark = composeTestRule.onNodeWithTag("live-bar-vad-fallback-mark", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertTrue(
            "expected the room mark (right=${room.right}) to sit left of the fallback mark " +
                "(left=${mark.left}), never overlapping it",
            room.right <= mark.left,
        )
        assertMarkAndLabelFitWithoutOverlap()
    }
}
