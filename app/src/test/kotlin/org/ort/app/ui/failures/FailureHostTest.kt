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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
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
import org.robolectric.RobolectricTestRunner

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
}
