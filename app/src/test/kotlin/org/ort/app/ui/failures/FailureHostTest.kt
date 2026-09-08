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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.ort.capture.android.AudioDeviceDescriptor
import org.ort.capture.android.AudioDeviceKind
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.InputStatus
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.capture.ShedStatus
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
}
