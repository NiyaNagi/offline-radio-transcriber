package org.ort.app.ui.failures

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.LevelStatus
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Register R-1080 (constitution VIII), found in the same R-1077 capture: once the scroll box and
 * the scroll hint became `Column` siblings (`FailureHost.kt`'s own `BannerOverlay`), `Banner`'s own
 * border (`ui/components/Feedback.kt`, wrapping its title/body/action `Row` at that Row's real,
 * unclipped height) gets its own bottom edge clipped away by `Modifier.verticalScroll` the moment
 * the content needs to scroll — the side lines run down to the visible edge and simply stop, with
 * the hint pill floating below a shape that never closes.
 *
 * `BannerOverlay` now closes the same rounded rectangle one level up, around the scroll box *and*
 * the hint together (`failure-banner-closing-border`), in the hint's own amber, only while the hint
 * itself is showing. This test proves the closing element's own bounds actually reach past *both*
 * pieces — not merely repeat the scroll box's own, already-clipped extent.
 *
 * Discrimination: reverting `BannerOverlay`'s `closingBorder`/`failure-banner-closing-border` back
 * out makes the `onNodeWithTag("failure-banner-closing-border")` lookup below fail outright (the
 * node does not exist); restored, it exists and its bottom reaches at least the hint's own bottom.
 */
@RunWith(RobolectricTestRunner::class)
class BannerClosingBorderTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setUp() {
        CaptureState.idle(clearSession = true)
        LevelStatus.reset()
    }

    @After
    fun tearDown() {
        CaptureState.idle(clearSession = true)
        LevelStatus.reset()
    }

    /** Identical scenario to `BannerChevronClearanceTest`'s own `armTooQuietBanner` — the real
     * `Fail-Level` scenario already known to need scrolling at font scale 2.0 in a short viewport. */
    private fun armTooQuietBanner() {
        LevelStatus.update(
            measured = LevelStatus.State.Measured(
                peakDbfs = -34f,
                rmsDbfs = -34f,
                noiseFloorDbfs = null,
                clipped = false,
                clipCountLastSecond = 0,
                sampleRateHz = 16_000,
                updatedAtMillis = 0L,
            ),
            peakHistoryDbfs = emptyList(),
        )
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `R_1080 the closing border reaches past the scroll hint, not just the clipped scroll box`() {
        val sessionId = "r1080-level-low-session"
        CaptureState.capturing(sessionId)
        armTooQuietBanner()

        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrtTheme {
                    // Same forced-short viewport `BannerChevronClearanceTest` uses to guarantee the
                    // banner caps and scrolls — the one shape this register row is about.
                    Box(modifier = Modifier.requiredSize(390.dp, 400.dp)) {
                        FailureHost(sessionId = sessionId, content = { })
                    }
                }
            }
        }

        composeTestRule.waitUntil(15_000) {
            composeTestRule.onAllNodesWithTag("failure-banner-scroll-hint").fetchSemanticsNodes().isNotEmpty()
        }

        val closingBorder = composeTestRule.onNodeWithTag("failure-banner-closing-border").getUnclippedBoundsInRoot()
        val scrollContent = composeTestRule.onNodeWithTag("failure-banner-scroll-content").getUnclippedBoundsInRoot()
        val hint = composeTestRule.onNodeWithTag("failure-banner-scroll-hint").getUnclippedBoundsInRoot()

        assertTrue(
            "expected the closing border (top=${closingBorder.top}) to start at or above the scroll " +
                "content's own top (${scrollContent.top})",
            closingBorder.top <= scrollContent.top,
        )
        assertTrue(
            "expected the closing border (bottom=${closingBorder.bottom}) to reach at least the scroll " +
                "hint's own bottom (${hint.bottom}) — R-1080: a border that stops at the (clipped) scroll " +
                "box's own edge leaves the hint floating below a shape that never closes",
            closingBorder.bottom >= hint.bottom,
        )
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `R_1080 at font scale 1-0, the ordinary unclipped case gains no second border`() {
        val sessionId = "r1080-level-low-session-fits"
        CaptureState.capturing(sessionId)
        armTooQuietBanner()

        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                OrtTheme {
                    // The tour AVD's own real height — plenty of room for `Fail-Level`'s short body
                    // at font scale 1.0, so the banner never needs to scroll and the hint never shows.
                    Box(modifier = Modifier.requiredSize(390.dp, 844.dp)) {
                        FailureHost(sessionId = sessionId, content = { })
                    }
                }
            }
        }

        composeTestRule.waitUntil(15_000) {
            composeTestRule.onAllNodesWithTag("failure-banner-overlay").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithTag("failure-banner-scroll-hint").assertCountEquals(0)
        // The closing-border Column always exists (its own bounds still meaningful to a reader
        // relying on this test for the non-scrolling shape) — bounds equal the scroll content's own
        // when no hint is reserved below it.
        val closingBorder = composeTestRule.onNodeWithTag("failure-banner-closing-border").getUnclippedBoundsInRoot()
        val scrollContent = composeTestRule.onNodeWithTag("failure-banner-scroll-content").getUnclippedBoundsInRoot()
        assertTrue(
            "expected the closing border (bottom=${closingBorder.bottom}) to match the scroll content's " +
                "own bottom (${scrollContent.bottom}) when nothing needs to scroll",
            closingBorder.bottom == scrollContent.bottom,
        )
    }
}
