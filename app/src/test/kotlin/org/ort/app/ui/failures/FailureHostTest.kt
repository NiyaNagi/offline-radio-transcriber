package org.ort.app.ui.failures

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.CaptureStatusMapper
import org.ort.app.ui.screens.CaptureStatusScreen
import org.ort.app.ui.theme.OrtTheme
import org.ort.capture.android.AudioDeviceDescriptor
import org.ort.capture.android.AudioDeviceKind
import org.ort.pipeline.capture.AsrAvailability
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.InputStatus
import org.ort.pipeline.capture.LevelStatus
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.capture.ShedStatus
import org.ort.pipeline.capture.StorageForecast
import org.ort.pipeline.capture.ThermalStatus
import org.ort.pipeline.capture.VadAvailability
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * WP11b, register R-100/R-101: [FailureHost] end to end — a real process-wide signal set before
 * composition, [FailureMapper]/[FailureSignalsPolling] doing the reading, the right composable
 * appearing over [FailureHost]'s own `content`.
 */
@RunWith(RobolectricTestRunner::class)
class FailureHostTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @After
    fun resetHolders() {
        CaptureState.idle(clearSession = true)
        InputStatus.reset()
        RigStatus.reset()
        ShedStatus.reset()
        DebugFailureOverride.clear()
    }

    @Test
    fun `R_101 a route mismatch takes over the whole screen, hiding content beneath`() {
        InputStatus.mismatch(
            expected = AudioDeviceDescriptor("usb-1", AudioDeviceKind.USB_DEVICE, "USB Audio Device"),
            actual = AudioDeviceDescriptor("mic-0", AudioDeviceKind.BUILT_IN_MIC, "Built-in microphone"),
        )

        composeTestRule.setContent {
            OrtTheme {
                FailureHost(sessionId = null) {
                    Text("underlying destination", modifier = Modifier.fillMaxSize())
                }
            }
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("failure-route-screen").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("failure-route-screen").assertIsDisplayed()
    }

    // checklist row E2-G05 (F23's banner).
    @Test
    @Requirement("FR-CAP-5")
    fun `FR_CAP_5 a Bluetooth-audio drop shows F23's banner with its real recovery actions`() {
        CaptureState.capturing("s1")
        val btDevice = AudioDeviceDescriptor("bt-1", AudioDeviceKind.BLUETOOTH, "Handheld BT")
        InputStatus.opened(btDevice, 16_000, "none", true, true, 0L)
        InputStatus.lost(sinceMillis = 0L)
        var retried = false
        var switched = false

        composeTestRule.setContent {
            OrtTheme {
                FailureHost(
                    sessionId = null,
                    actions = FailureHostActions(
                        onRetryInput = { retried = true },
                        onSwitchToWiredInput = { switched = true },
                    ),
                ) {
                    Text("underlying destination", modifier = Modifier.fillMaxSize())
                }
            }
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("failure-bluetooth-audio-banner").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("failure-bluetooth-audio-banner").assertIsDisplayed()

        composeTestRule.onNodeWithText("Retry now", substring = true).performClick()
        assertTrue(retried)
        composeTestRule.onNodeWithText("Switch to a wired input", substring = true).performClick()
        assertTrue(switched)
    }

    @Test
    fun `R_100 with nothing wrong, only the host's own content renders`() {
        composeTestRule.setContent {
            OrtTheme {
                FailureHost(sessionId = null) {
                    Text("underlying destination", modifier = Modifier.fillMaxSize())
                }
            }
        }

        composeTestRule.onNodeWithText("underlying destination").assertIsDisplayed()
        assertEquals(0, composeTestRule.onAllNodesWithTag("failure-route-screen").fetchSemanticsNodes().size)
    }

    @Test
    fun `R_164 a banner never covers the header — its top bound clears the 44dp header height`() {
        // `backlog/T01-threads-live-header.png` — the finding this proves against: the banner used
        // to be a fixed top overlay covering the whole header (drawer icon, live dot, search).
        ShedStatus.update(level = 3, backlog = 41)

        composeTestRule.setContent {
            OrtTheme {
                FailureHost(sessionId = null) {
                    Text("underlying destination", modifier = Modifier.fillMaxSize())
                }
            }
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("failure-banner-overlay").fetchSemanticsNodes().isNotEmpty()
        }
        val top = composeTestRule.onNodeWithTag("failure-banner-overlay").getUnclippedBoundsInRoot().top
        assertTrue("banner top ($top) must clear the 44dp header", top >= 44.dp)
    }

    @Test
    fun `R_178 at font scale 2_0 a taller banner still pushes content below its own bottom bound`() {
        // Register R-178: a banner that wraps its copy onto more lines at maximum font scale must
        // not just visually cover more of the content beneath it — the content itself moves down.
        ShedStatus.update(level = 3, backlog = 41)

        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrtTheme {
                    FailureHost(sessionId = null) { contentTopPadding ->
                        // A 44dp stand-in for the real `ScreenHeader` `OrtNavHost.kt` always renders
                        // above its own destination content — `contentTopPadding` is only ever meant
                        // to apply *after* that header, exactly the shape `NavHostBody` composes.
                        Column(modifier = Modifier.fillMaxSize()) {
                            Box(modifier = Modifier.fillMaxWidth().height(44.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(top = contentTopPadding)
                                    .testTag("test-content-below-banner"),
                            ) {
                                Text("underlying destination")
                            }
                        }
                    }
                }
            }
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("failure-banner-overlay").fetchSemanticsNodes().isNotEmpty()
        }
        val bannerBottom = composeTestRule.onNodeWithTag("failure-banner-overlay").getUnclippedBoundsInRoot().bottom
        val contentTop = composeTestRule.onNodeWithTag("test-content-below-banner").getUnclippedBoundsInRoot().top
        assertTrue(
            "content top ($contentTop) must be at or below the banner's bottom ($bannerBottom)",
            contentTop >= bannerBottom,
        )
    }

    @Test
    fun `R_147 a clock-DST override takes over the whole screen, like F19-F22, not a small card`() {
        DebugFailureOverride.show(
            FailurePresentation.Clock(
                ClockViewState(
                    offsetChangeLabel = "PDT → PST",
                    ranForLabel = "8 h 30 m",
                    startedLabel = "23:10",
                    endedLabel = "06:40",
                ),
            ),
        )

        composeTestRule.setContent {
            OrtTheme {
                FailureHost(sessionId = null) {
                    Text("underlying destination", modifier = Modifier.fillMaxSize())
                }
            }
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("failure-clock-screen").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("failure-clock-screen").assertIsDisplayed()
        assertEquals(0, composeTestRule.onAllNodesWithTag("failure-clock-card").fetchSemanticsNodes().size)
    }

    @Test
    fun `F9_rig a stale rig renders the rig banner over the content`() {
        RigStatus.stale(
            lastKnown = RigStatus.State.Connected("TH-D75A", emptyList()),
            sinceMillis = 0L,
        )

        composeTestRule.setContent {
            OrtTheme {
                FailureHost(sessionId = null) {
                    Text("underlying destination", modifier = Modifier.fillMaxSize())
                }
            }
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("failure-rig-banner").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("failure-rig-banner").assertIsDisplayed()
        composeTestRule.onNodeWithText("underlying destination").assertIsDisplayed()
    }

    /**
     * Register R-300 (halt, V2 pass 3): the `Fail-Storage` banner plus its "How this unfolded"
     * card, at font scale 2.0, used to render as an unbounded fixed block that consumed nearly the
     * whole viewport — `storage-warn/N04-capture-status-banner@2x-pass3.png` — squeezing
     * `Capture-Status`'s own title and `capture-status-stop` into a sliver behind the live bar,
     * with Stop's tap target collapsing to `[0,0][0,0]`. Composed exactly the way
     * `ReaderActivity.kt` wires `FailureHost`/`OrtNavHost` together — [CaptureStatusScreen] reads
     * `contentTopPadding` the same way every other destination does; this package's own fix is
     * capping the banner (and therefore `contentTopPadding`) to a bounded fraction of the
     * viewport, never touching `CaptureStatusScreen` itself (already scrollable, Stop already at
     * its own top — WP4's half, unmodified here).
     *
     * Follow-up (found on `main`, under load): the original version passed `sessionId = "s1"` — a
     * session id that does not exist — into [FailureHost], whose own poll loop
     * (`FailureSignalsPolling.current`) then ran *real* Room queries against it before the first
     * `presentation` update, and waited for the result with a wall-clock
     * `composeTestRule.waitUntil(timeoutMillis = 5_000)`. Under load, real DB dispatch plus a real
     * 5 s wall-clock budget is exactly what made this flaky (WP9 and this package's own gate both
     * hit it). [DebugFailureOverride.show] already wins outright over every real signal
     * (`FailureMapper.map`'s own first line), so the session id this poll loop reads was never
     * actually load-bearing for *this* test — `sessionId = null` skips every Room query
     * (`FailureSignalsPolling.current`'s own `if (sessionId != null)` guard), leaving the poll
     * loop's first iteration fully synchronous. Combined with `mainClock.autoAdvance = false` and
     * one explicit `advanceTimeByFrame()` (not a wall-clock wait at all — a single, bounded virtual
     * frame), the banner is deterministically composed before any assertion runs.
     */
    @Test
    fun `R_300 the bounded storage banner leaves Capture-Status Stop reachable within one scroll, font scale 2_0`() {
        DebugFailureOverride.show(
            FailurePresentation.StorageWarning(
                StorageWarningViewState(
                    nightsLeftLabel = "2.4",
                    freeLabel = "6.0 GB free",
                    timeline = listOf(
                        StorageTimelineStage(
                            label = "12:28:15 · warned at 3 nights left",
                            detail = "notification and status surface · retention set to 30 nights",
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
        val captureStatus = CaptureStatusMapper.from(
            captureState = CaptureState.State.Capturing,
            shedLevel = 0,
            backlog = 0,
            thermal = ThermalStatus.State.Nominal(0, null),
            rig = RigStatus.State.Absent,
            storage = StorageForecast.State.ThreeNightsLeft(6_000_000_000L, 0L, 2.4),
            asr = AsrAvailability.State.NotYetChecked,
            vad = VadAvailability.State.Real,
            input = InputStatus.State.None,
            level = LevelStatus.State.NotMeasured,
            nowMillis = 5_430_000L,
            sinceLabel = "11:00:00",
            elapsedLabel = "1:30:54",
            heartbeatSecondsAgo = 2L,
            isAlive = true,
            transmissionCount = 12,
            rejectedCount = 0,
            failedCount = 0,
            gapCount = 0,
            batteryPercent = 80,
            batteryCharging = true,
            batteryExemptionReportsIgnoring = false,
        )

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrtTheme {
                    // `sessionId = null` — see this test's own kdoc for exactly why the original
                    // `"s1"` was the source of the flake, not a signal this assertion needs.
                    FailureHost(sessionId = null) { contentTopPadding ->
                        CaptureStatusScreen(
                            state = captureStatus,
                            modifier = Modifier.padding(top = contentTopPadding),
                            onStop = {},
                        )
                    }
                }
            }
        }
        // One bounded virtual frame — not a wall-clock wait — lets the `LaunchedEffect`'s first,
        // now fully synchronous poll iteration land in the composition.
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("failure-banner-overlay").assertIsDisplayed()

        val stopNode = composeTestRule.onNodeWithTag("capture-status-stop")
        val boundsBeforeScroll = stopNode.getUnclippedBoundsInRoot()
        val boundsWidth = boundsBeforeScroll.right - boundsBeforeScroll.left
        val boundsHeight = boundsBeforeScroll.bottom - boundsBeforeScroll.top
        assertTrue(
            "Stop's tap target must have a real, non-zero size ($boundsBeforeScroll), never [0,0][0,0]",
            boundsWidth > 0.dp && boundsHeight > 0.dp,
        )

        // "at most one scroll" — CaptureStatusScreen's own internal verticalScroll is what reaches
        // it; this package's fix does not need it to already be visible with zero scrolling.
        stopNode.performScrollTo().assertIsDisplayed()
    }

    // R-550: investigated, not fixed from this package's row — see this package's own report for
    // the full measured account (a real, if thin, ~1dp gap at rest; this package's only lead-
    // approved lever, `ui/screens/CaptureStatusContent.kt`'s outer `modifier`, can only shrink
    // `CaptureStatusScreen`'s whole box and cannot insert a gap between the scrollable Column and
    // the sibling live bar inside it — confirmed to make the gap *negative* in one measured
    // configuration rather than fix it). No test added here for the reason `AGENTS.md` names: a
    // half-fix is not shipped, and the real fix needs a line inside `CaptureStatusScreen.kt`
    // itself (WP4's row, outside even the one exception this package was granted).

    /**
     * Register R-883 (halt, validator V11, font scale 2.0): on the real device the banner's own
     * measured bottom and the destination content's own top were pixel-for-pixel equal
     * (`uiautomator`'s own dump had both at `y=1262`) — an exact touch, not a real overlap, but
     * V11's own screenshot read the zero gap as the banner's last (cut-off) line bleeding into the
     * "Tonight" heading below it. `R_178` above already proves the `>=` case (content never
     * strictly above the banner); this proves the *stronger* claim R-883 actually needs — a real,
     * non-zero gap always separates them, whatever the banner's own height ends up being.
     */
    @Test
    @Requirement("R-883")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `R_883 the destination content clears the banner by a real gap, not just an exact touch`() {
        RigStatus.stale(
            lastKnown = RigStatus.State.Connected("TH-D75A", emptyList()),
            sinceMillis = 0L,
        )

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrtTheme {
                    FailureHost(sessionId = null) { contentTopPadding ->
                        Column(modifier = Modifier.fillMaxSize()) {
                            Box(modifier = Modifier.fillMaxWidth().height(44.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(top = contentTopPadding)
                                    .testTag("test-content-below-banner"),
                            ) {
                                Text("Tonight")
                            }
                        }
                    }
                }
            }
        }
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("failure-rig-banner").assertIsDisplayed()

        val bannerBottom = composeTestRule.onNodeWithTag("failure-banner-overlay").getUnclippedBoundsInRoot().bottom
        val contentTop = composeTestRule.onNodeWithTag("test-content-below-banner").getUnclippedBoundsInRoot().top
        // A floor, not FailureHost.kt's own exact private BANNER_CONTENT_CLEARANCE value (`xl` =
        // 30.dp today) — not importable across this file boundary anyway (Kotlin top-level
        // `private` is file-scoped, the way `R_300`'s own test already treats
        // `BANNER_MAX_HEIGHT_FRACTION` as an implementation detail, not an imported symbol) — this
        // only needs to prove a *real, meaningful* gap exists, not pin the exact tuned value. On
        // the real device this floor also has to clear a structural offset this test's own tiny
        // 44dp header stand-in does not reproduce (`ScreenHeader`'s real height vs. the banner's
        // own internal header clearance) — tuned against the device, not this test alone; see
        // `BANNER_CONTENT_CLEARANCE`'s own kdoc.
        val expectedClearance = 8.dp
        assertTrue(
            "content top ($contentTop) must clear the banner's own bottom ($bannerBottom) by a " +
                "real gap of at least $expectedClearance — register R-883, V11's own screenshot " +
                "found them touching exactly, which read as the banner's own last line bleeding " +
                "into the heading below",
            contentTop >= bannerBottom + expectedClearance,
        )
    }

    /**
     * Register R-883 (halt, validator V11, font scale 2.0): the banner was already reachable by a
     * real scroll on the device (confirmed directly: swiping it reveals both F9 actions past the
     * fold) — V11's own complaint was that nothing on screen ever said so ("no scroll affordance
     * reaches it"). This proves the hint shows exactly while more is hidden and clears the moment
     * a real max-scroll reaches the banner's own end, never lingering once nothing more is cut off.
     */
    @Test
    @Requirement("R-883")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `R_883 a scroll hint shows while F9's own message is cut off, and clears once fully scrolled`() {
        RigStatus.stale(
            lastKnown = RigStatus.State.Connected("TH-D75A", emptyList()),
            sinceMillis = 0L,
        )

        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrtTheme {
                    FailureHost(sessionId = null) {
                        Text("underlying destination", modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("failure-rig-banner").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("failure-banner-scroll-hint").assertIsDisplayed()

        // Default (auto-advancing) clock here, unlike `R_300`/the other `R_883` test above: a real
        // `ScrollBy` dispatches through the scrollable's own fling/animation machinery, which needs
        // real frames to settle — `R_613`'s own established technique runs with the same default.
        composeTestRule.onNodeWithTag("failure-banner-scroll-content")
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, Float.MAX_VALUE) }
        composeTestRule.waitForIdle()

        assertTrue(
            "expected the scroll hint to clear once a real max-scroll reaches the banner's own " +
                "end — nothing is left cut off for it to point at",
            composeTestRule.onAllNodesWithTag("failure-banner-scroll-hint").fetchSemanticsNodes().isEmpty(),
        )
    }
}
