package org.ort.app.ui.navigation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.failures.DebugFailureOverride
import org.ort.app.ui.failures.FailurePresentation
import org.ort.app.ui.failures.StorageTimelineStage
import org.ort.app.ui.failures.StorageWarningViewState
import org.ort.app.ui.theme.OrtTheme
import org.ort.data.OrtDatabase
import org.ort.pipeline.capture.CaptureState
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * Register R-1061 (coordinator round 2): "Don't hard-code the bar height ... reserve the bar's
 * *measured* height ... add a test at 1.0 and 2.0 that the reservation equals the bar's laid-out
 * height." Round 1 shipped a guessed constant (`LIVE_BAR_RESERVE_HEIGHT = 96.dp`); `OrtNavHost.kt`
 * now measures the real, laid-out [org.ort.app.ui.components.TransportBar] height
 * (`onGloballyPositioned`, the identical mechanism [org.ort.app.ui.failures.FailureHost] already
 * uses for the banner's own height, R-178) and feeds it to `FailureHost`'s `reservedBottomHeight`.
 *
 * This is not directly observable from outside — `OrtNavHost.kt`'s own `liveBarHeight` is a
 * private composition-local `remember`, and `FailureHost`'s `reservedBottomHeight` parameter has
 * no semantics of its own — so this proves the wiring by its one visible effect instead:
 * [org.ort.app.ui.failures.FailureHost]'s own banner cap is `(viewportHeight - reservedBottomHeight)
 * * 0.55f` (`bannerCapViewportHeight`, `BANNER_MAX_HEIGHT_FRACTION`). A real
 * [FailurePresentation.StorageWarning] banner (`DebugFailureOverride`, the same debug-only seam
 * `FailureHostTest`'s own `R_300` case already uses to force a presentation with no real signal
 * polling) with its own two-stage timeline is tall enough, at both font scales tested here, to
 * exceed even the *unreserved* cap — confirmed in the same assertion by requiring
 * `failure-banner-scroll-hint` to exist (`BannerOverlay`'s own "real content is still hidden past
 * the cap" signal, R-883) — so the banner's own rendered height is genuinely governed by the cap
 * formula, not merely coincidentally equal to some other number. Solving that formula for
 * `reservedBottomHeight` from the banner's own measured height, the live bar's own measured height
 * (`live-bar-clearance`) and the root's own measured height proves `reservedBottomHeight` really is
 * the bar's real, laid-out height — not `96.dp`: with the pre-fix constant, this assertion is off
 * by `(96.dp - realBarHeight) * 0.55f`, tens of dp, at both scales (`TransportBarHostLayoutTest`'s
 * own R-1049 measurements put the real bar at 64.0dp at 1.0 and a bounded ~64-76dp at 2.0 — nowhere
 * near 96dp either way).
 */
@RunWith(RobolectricTestRunner::class)
class LiveBarHeightReservationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** R-1043 follow-up (register): both cases below drive `OrtNavHost` with a real, non-null,
     * never-inserted `sessionId` — `FailureHost`'s and `OrtNavHost`'s own live-bar poll both open a
     * real `OrtDatabase` the first time anything asks for one with a non-null session id, a genuine
     * one-time disk-I/O cost that is trivial on an idle machine but not necessarily on a hosted
     * runner under load — root-caused in full at `BannerClosingBorderTest`'s own `R_1080` comment.
     * Pre-warming here means that cost never lands inside either `waitUntil` below. */
    @Before
    fun prewarmDatabase() {
        OrtDatabase.create(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        CaptureState.idle(clearSession = true)
        DebugFailureOverride.clear()
    }

    private fun armStorageWarningBanner() {
        DebugFailureOverride.show(
            FailurePresentation.StorageWarning(
                StorageWarningViewState(
                    nightsLeftLabel = "2.4",
                    freeLabel = "6.0 GB free",
                    // Four stages, not two — R-883's own "tallest real banner shape" reasoning:
                    // two stages alone fits comfortably within even the *unreserved* cap at font
                    // scale 1.0 (confirmed directly: the scroll-hint precondition below did not
                    // appear with only two), so this uses every stage a real overnight session's
                    // own storage timeline could plausibly report, long enough to exceed the cap
                    // at both scales tested here once a live bar also reserves real room.
                    timeline = listOf(
                        StorageTimelineStage(
                            label = "09:14:02 · session started",
                            detail = "60.0 GB budget · retention set to 30 nights",
                            reached = true,
                        ),
                        StorageTimelineStage(
                            label = "12:28:15 · warned at 3 nights left",
                            detail = "notification and status surface · retention set to 30 nights",
                            reached = true,
                        ),
                        StorageTimelineStage(
                            label = "13:40:51 · warned at 2.4 nights left",
                            detail = "second warning · write rate climbing",
                            reached = true,
                        ),
                        StorageTimelineStage(
                            label = "Not reached",
                            detail = "500 MB hard floor — capture would halt with the red banner, never quietly",
                            reached = false,
                        ),
                    ),
                ),
            ),
        )
    }

    private fun assertReservationEqualsRealBarHeight(fontScale: Float) {
        val sessionId = "r1061-reservation-session"
        armStorageWarningBanner()
        CaptureState.capturing(sessionId)

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = LocalDensity.current.density, fontScale = fontScale),
            ) {
                OrtTheme {
                    OrtNavHost(
                        sessionId = sessionId,
                        // LOG neither embeds its own live bar (unlike NOW/CAPTURE) nor owns a
                        // fixed action bar of its own (unlike Improve's Done/Running) — the host's
                        // real pinned bar and the real banner cap are both exercised directly.
                        navigator = rememberReaderNavigator(initialDestination = ReaderDestination.LOG),
                    )
                }
            }
        }

        composeTestRule.waitUntil(15_000) {
            composeTestRule.onAllNodesWithTag("failure-banner-overlay").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.waitUntil(15_000) {
            composeTestRule.onAllNodesWithTag("live-bar-clearance").fetchSemanticsNodes().isNotEmpty()
        }

        // The precondition this test's own reasoning depends on: real content is genuinely hidden
        // past the cap, so the banner's own rendered height is governed by the cap formula.
        composeTestRule.onNodeWithTag("failure-banner-scroll-hint").assertExists()

        val rootBounds = composeTestRule.onRoot().getUnclippedBoundsInRoot()
        val viewportHeight = rootBounds.bottom - rootBounds.top
        val barBounds = composeTestRule.onNodeWithTag("live-bar-clearance").getUnclippedBoundsInRoot()
        val barHeight = barBounds.bottom - barBounds.top
        val bannerBounds = composeTestRule.onNodeWithTag("failure-banner-overlay").getUnclippedBoundsInRoot()
        val bannerHeight = bannerBounds.bottom - bannerBounds.top

        val expectedBannerHeight = (viewportHeight.value - barHeight.value) * 0.55f
        val driftDp = abs(bannerHeight.value - expectedBannerHeight)
        assertTrue(
            "expected the banner's own capped height ($bannerHeight) to equal " +
                "(viewport=$viewportHeight - realBarHeight=$barHeight) * 0.55 = ${expectedBannerHeight}dp " +
                "at font scale $fontScale (register R-1061) — drifted ${driftDp}dp, " +
                "consistent with a still-hard-coded reservation rather than the bar's own real height",
            driftDp < 2f,
        )
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `R_1061 the banner cap reserves exactly the live bars own measured height at font scale 1_0`() {
        assertReservationEqualsRealBarHeight(fontScale = 1f)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `R_1061 the banner cap reserves exactly the live bars own measured height at font scale 2_0`() {
        assertReservationEqualsRealBarHeight(fontScale = 2f)
    }
}
