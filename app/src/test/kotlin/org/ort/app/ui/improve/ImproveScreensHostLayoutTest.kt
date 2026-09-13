package org.ort.app.ui.improve

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.failures.FailLevelBanner
import org.ort.app.ui.failures.LevelProblem
import org.ort.app.ui.failures.LevelViewState
import org.ort.app.ui.theme.OrtTheme
import org.ort.pipeline.reprocess.ReprocessStatus
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Register R-1056 (halt-class, field session 1's own recurring shape): reproduces the validator's
 * own evidence (`05-done@2x.png`/`05-done@2x-dump.xml`) as closely as a Robolectric host can — the
 * real [ImproveDoneScreen]/[ImproveRunningScreen], with a real "too quiet" banner
 * ([FailLevelBanner], the same composable `NavHostBody`'s own `FailureHost` wrapper renders above
 * the destination) sharing the screen exactly the way the validator's device did, at the tour's own
 * real device geometries. R-1049's own lesson, repeated verbatim in this register row, is why this
 * renders the *real screen* rather than [ImproveActionBarScaffold] alone with stand-in content: "a
 * component-only test recently passed while the real screen was broken."
 *
 * What this Robolectric host genuinely cannot prove — recorded rather than glossed over, the same
 * way [org.ort.app.ui.failures.FailureActionBarScaffold]'s own class doc does — is the *real*
 * navigation-bar inset: [org.ort.app.ui.components.safeAreaBottomPadding]'s own
 * `WindowInsets.navigationBars` reads zero under this harness regardless of what the code does,
 * confirmed by that file's own three independent attempts to drive it. So the inset mechanism
 * itself (this fix calls the identical, already-proven [org.ort.app.ui.components
 * .safeAreaBottomPadding] `SetupScaffold`/`FailureActionBarScaffold` already use) is asserted by
 * inspection of the production code, not a number this harness could fabricate; what these tests
 * *do* prove on the JVM — real bounds, real native text layout (`@GraphicsMode(NATIVE)`) — is that
 * the action bar is genuinely pinned at the bottom of the space it is given, that no real body text
 * renders behind it, and that every body line can be scrolled to a position above it. The device
 * capture in this builder's own report is the evidence for the inset itself (constitution VIII:
 * "the screenshot wins").
 */
@RunWith(RobolectricTestRunner::class)
class ImproveScreensHostLayoutTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun quietBanner() = LevelViewState(problem = LevelProblem.QUIET, peakDbfs = -34f, sinceLabel = "14:02")

    private fun doneState() = ImproveDoneViewState(
        headline = "Field, Thu 3 Sep",
        clearedCount = 12,
        summary = ReprocessStatus.Summary(
            total = 12,
            transcriptsChanged = 12,
            attributionsChanged = 12,
            rejected = 0,
            failed = 0,
            changedTransmissionIds = (1..12).map { "TX$it" }.toSet(),
        ),
    )

    private fun runningState() = ImproveRunningViewState(
        headline = "Field, Thu 3 Sep",
        doneCount = 4,
        totalCount = 12,
        paused = false,
        autoPausedReason = null,
    )

    /** [org.ort.app.ui.navigation.OrtNavHost]'s own `NavHostBody`, reproduced only in shape — a
     * `Scaffold`, a `Column` filling it, the real [FailLevelBanner] sharing the top of the screen
     * (`FailureHost`'s own overlay, real height here since it is composed directly rather than
     * measured through `BoxWithConstraints`), and the real screen as the weighted sibling below —
     * matching [org.ort.app.ui.navigation.TransportBarHostLayoutTest]'s own established shape for
     * this exact class of finding. */
    private fun setContent(fontScale: Float, screen: @Composable () -> Unit) {
        composeTestRule.setContent {
            val realDensity = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density = realDensity, fontScale = fontScale)) {
                OrtTheme {
                    Scaffold { padding ->
                        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                            FailLevelBanner(state = quietBanner())
                            Box(modifier = Modifier.weight(1f)) { screen() }
                        }
                    }
                }
            }
        }
    }

    /** Register R-292's own established shape (also [org.ort.app.ui.navigation
     * .TransportBarHostLayoutTest]'s own precedent for this exact class of finding), applied to
     * [actionBarTag]'s bar and whatever [lastBodyNode] names as the board's own last real body
     * line. Deliberately does not assert the bar's own absolute position against the root or the
     * host `Column`: `Scaffold`'s own default `contentWindowInsets` reads a real, non-zero, and
     * width-bucket-dependent value under this harness (confirmed empirically — unlike
     * `windowInsetsPadding(WindowInsets.navigationBars)` read directly, which this codebase's own
     * `FailureActionBarScaffold.kt` doc comment already established reads zero here), so an exact
     * "flush with the bottom" equality is not a stable invariant this harness can assert either way.
     *
     * Also deliberately does not check the last body line's *unclipped* bounds against the bar
     * before scrolling: when the body genuinely does not fit at rest, [ImproveActionBarScaffold]'s
     * own hard-constrained scroll region correctly *clips* the overflow at the scrollable
     * container's own edge (exactly at the bar's top) rather than drawing it — a real device or a
     * clip-aware screenshot would show nothing there, even though the unclipped layout position
     * (unaffected by that visual clip) still reports past it. Register R-1056's actual defect was
     * never that shape: it was a plain, unclipped `Column` placing the button row itself past the
     * screen's own bottom edge with body text drawn straight through it — which is exactly what the
     * scroll-and-clear check below would have failed against the old code (content already
     * unreachable, since there was no scroll region for [performScrollTo] to act on), and does not
     * fail against the fix. */
    private fun assertActionBarPinnedAndClearOfContent(
        actionBarTag: String,
        lastBodyNode: () -> SemanticsNodeInteraction,
    ) {
        composeTestRule.onNodeWithTag(actionBarTag).assertIsDisplayed()

        // The last body line must be reachable by a scroll, and once reached, genuinely clear of
        // the bar -- R-1056's own "clipped at the bottom edge" and R-292's established "content can
        // always scroll clear" pattern.
        val afterScroll = lastBodyNode().performScrollTo().getUnclippedBoundsInRoot()
        val barAfterScroll = composeTestRule.onNodeWithTag(actionBarTag).getUnclippedBoundsInRoot()
        assertTrue(
            "the last body line (bottom=${afterScroll.bottom}), once scrolled to, must clear the action " +
                "bar (top=${barAfterScroll.top})",
            afterScroll.bottom <= barAfterScroll.top,
        )
    }

    // ---------------------------------------------------------------------------------------
    // Done board
    // ---------------------------------------------------------------------------------------

    private fun setDoneContent(fontScale: Float) = setContent(fontScale) {
        ImproveDoneScreen(state = doneState(), onDone = {}, onReviewChanges = {})
    }

    private fun assertDoneBoardIsSound() {
        composeTestRule.onNodeWithText("Review the 12 changes").assertExists()
        assertActionBarPinnedAndClearOfContent(actionBarTag = "improve-done-action-bar") {
            // R-1056(b): the per-record note is the last body line on the board, and the one the
            // validator's own evidence showed drawn straight through the button row.
            composeTestRule.onNodeWithText("Per-record detail", substring = true)
        }
    }

    @Test
    @Requirement("R-1056")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `R_1056 the Done board scrolls clear of its action bar at 390dp font scale 1_0, banner present`() {
        setDoneContent(fontScale = 1f)
        assertDoneBoardIsSound()
    }

    @Test
    @Requirement("R-1056")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `R_1056 the Done board scrolls clear of its action bar at 390dp font scale 2_0, banner present`() {
        setDoneContent(fontScale = 2f)
        assertDoneBoardIsSound()
    }

    @Test
    @Requirement("R-1056")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w480dp-h844dp-420dpi")
    fun `R_1056 the Done board scrolls clear of its action bar at 480dp font scale 1_0, banner present`() {
        setDoneContent(fontScale = 1f)
        assertDoneBoardIsSound()
    }

    @Test
    @Requirement("R-1056")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w480dp-h844dp-420dpi")
    fun `R_1056 the Done board scrolls clear of its action bar at 480dp font scale 2_0, banner present`() {
        setDoneContent(fontScale = 2f)
        assertDoneBoardIsSound()
    }

    // ---------------------------------------------------------------------------------------
    // Running board -- same shape (R-1056's own instruction: "check them all")
    // ---------------------------------------------------------------------------------------

    private fun setRunningContent(fontScale: Float) = setContent(fontScale) {
        ImproveRunningScreen(state = runningState(), onPause = {}, onCancel = {})
    }

    private fun assertRunningBoardIsSound() {
        composeTestRule.onNodeWithText("Cancel").assertExists()
        assertActionBarPinnedAndClearOfContent(actionBarTag = "improve-running-action-bar") {
            composeTestRule.onNodeWithText("Live capture is unaffected", substring = true)
        }
    }

    @Test
    @Requirement("R-1056")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `R_1056 the Running board scrolls clear of its action bar at 390dp font scale 1_0, banner present`() {
        setRunningContent(fontScale = 1f)
        assertRunningBoardIsSound()
    }

    @Test
    @Requirement("R-1056")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `R_1056 the Running board scrolls clear of its action bar at 390dp font scale 2_0, banner present`() {
        setRunningContent(fontScale = 2f)
        assertRunningBoardIsSound()
    }

    @Test
    @Requirement("R-1056")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w480dp-h844dp-420dpi")
    fun `R_1056 the Running board scrolls clear of its action bar at 480dp font scale 2_0, banner present`() {
        setRunningContent(fontScale = 2f)
        assertRunningBoardIsSound()
    }
}
