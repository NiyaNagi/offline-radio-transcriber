package org.ort.app.ui.failures

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
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
 * Register R-1077 (WPPOLISH): at font scale 2.0, [FailureHost]'s own scroll-affordance chevron
 * (register R-883, `BannerOverlay`'s down-facing hint pill) was painted as a `BoxScope`-aligned
 * `Alignment.BottomCenter` sibling directly *on top of* the scrollable content box beneath it —
 * both were children of the same `Box`, which sizes itself to the larger child and lets each
 * child's own alignment decide its position independently, so the hint's own pill always drew
 * over whatever text happened to sit at the bottom of the visible, capped viewport (`Fail-Level`'s
 * own "... the peaks sit in the green band." wrapped so its last word landed directly under the
 * chevron on the reference device — reported as "in the gree[chevron]and."). The fix
 * (`FailureHost.kt`'s own `BannerOverlay`) makes the scrollable content and the hint `Column`
 * siblings instead of `Box`-overlaid ones, with the hint's own (unweighted) height reserved out of
 * the capped total before the scroll box gets the rest — so the two can never share vertical
 * space, whatever the wrapped text's own last line happens to be.
 *
 * Discriminates directly: reverting `BannerOverlay` to its pre-fix `Box`-with-`BottomCenter`
 * shape reproduces the chevron drawing inside the last line's own vertical span (confirmed by
 * temporarily restoring that shape and re-running this case — see `CHANGELOG.md`).
 */
@RunWith(RobolectricTestRunner::class)
class BannerChevronClearanceTest {

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

    /** [FailureMapper.QUIET_PEAK_THRESHOLD_DBFS] is `-30f`; `-34f` matches `Fail-Level.dc.html`'s
     * own figures, the same shape `NavHostBannerLiveBarSqueezeTest`'s own `armTooQuietBanner`
     * establishes for the identical scenario (`level-low`). */
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
    fun `R_1077 the scroll hint chevron never intersects the banner's own last text line at font scale 2_0`() {
        val sessionId = "r1077-level-low-session"
        CaptureState.capturing(sessionId)
        armTooQuietBanner()

        // R-1043 (register): the same shape as `BannerClosingBorderTest`'s own two cases, root-
        // caused there in full — a Release-runner-only failure traced to `FailureHost`'s polling
        // `LaunchedEffect` (`FailureSignalsPolling.current`) lazily opening a real `OrtDatabase`
        // (WAL setup, hand-written schema, `runBlocking`) the first time a test asks for a non-null
        // `sessionId`, not the 2-second poll interval itself. `FailureMapper.map`'s Level branch
        // (this test's own `armTooQuietBanner` scenario) never reads anything `sessionId` gates in
        // `FailureSignalsPolling.current`, so it was never load-bearing here either — `sessionId =
        // null` (kept for `CaptureState.capturing` above, an in-memory holder with no I/O) skips
        // every Room query, and a single bounded virtual frame replaces both wall-clock waits.
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = 2f),
            ) {
                OrtTheme {
                    // `requiredSize` — not `size` — because the root of a Compose hierarchy under
                    // `setContent` always receives *fixed* incoming constraints matching the real
                    // window (the `w390dp-h844dp` qualifier's own real pixel size); a plain `size`
                    // would be coerced straight back up to that fixed size (`ControlsTest`'s own
                    // R-1065 cases hit the identical issue, hence `requiredWidth` there). A
                    // deliberately short, `required`-forced viewport (well under what `Fail-Level`'s
                    // own wrapped body needs at 2.0) forces the banner to cap and scroll — the
                    // shape this register row is actually about.
                    Box(modifier = Modifier.requiredSize(390.dp, 400.dp)) {
                        FailureHost(sessionId = null, content = { })
                    }
                }
            }
        }
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.waitForIdle()

        // The scroll box's own bottom edge — not the overlay's — is what actually decides which of
        // the body text's many wrapped lines is the last one *visually rendered* before the cut
        // (`Modifier.verticalScroll` clips its own content to its own measured bounds, independent
        // of the outer `clipToBounds()`), which is what the register row is about (most of a long,
        // scrolled paragraph's own lines sit well past this edge, off-screen, at rest). Fixed and
        // unfixed code disagree on exactly where this edge falls — the fix reserves the hint's own
        // height out of the scroll box, shrinking it — which is the whole point under test.
        val overlayBottom = composeTestRule.onNodeWithTag("failure-banner-scroll-content")
            .getUnclippedBoundsInRoot().bottom

        val isBodyTextNode = SemanticsMatcher("is the Fail-Level banner's own body Text node") { node ->
            node.config.getOrNull(SemanticsProperties.Text)
                ?.any { it.text.contains("the peaks sit in the green band.") } == true &&
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult) != null
        }
        val bodyNode = composeTestRule.onNode(isBodyTextNode, useUnmergedTree = true).fetchSemanticsNode()
        val bodyBounds = composeTestRule.onNode(isBodyTextNode, useUnmergedTree = true).getUnclippedBoundsInRoot()

        val layoutResults = mutableListOf<TextLayoutResult>()
        bodyNode.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layoutResults)
        val layoutResult = layoutResults.first()

        // `density = 1f` above (matching `ControlsTest`'s own R-1065 technique) makes px and dp
        // numerically identical, so these can be added directly to the node's own dp bounds.
        val lineTopsInRoot = (0 until layoutResult.lineCount).map { bodyBounds.top + layoutResult.getLineTop(it).dp }
        val visibleLastLine = lineTopsInRoot.indexOfLast { it < overlayBottom }.coerceAtLeast(0)
        val lastLineTop = lineTopsInRoot[visibleLastLine]
        // `Modifier.verticalScroll` clips its own content at the scroll box's own bottom edge
        // (`overlayBottom` above) — a line whose top is visible can still have its own bottom
        // clipped away there, so the *visible* extent of the last line never exceeds that edge.
        val lastLineBottom = minOf(bodyBounds.top + layoutResult.getLineBottom(visibleLastLine).dp, overlayBottom)

        val chevronBounds = composeTestRule.onNodeWithTag("failure-banner-scroll-hint").getUnclippedBoundsInRoot()

        assertTrue(
            "expected the scroll-hint chevron (top=${chevronBounds.top}, bottom=${chevronBounds.bottom}) to " +
                "never intersect the banner's own last visible text line (top=$lastLineTop, " +
                "bottom=$lastLineBottom, overlayBottom=$overlayBottom) at font scale 2.0 (register R-1077) " +
                "— the chevron must sit in its own space below the text",
            chevronBounds.top >= lastLineBottom || chevronBounds.bottom <= lastLineTop,
        )
    }
}
