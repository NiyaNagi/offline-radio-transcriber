package org.ort.app.ui.failures

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.After
import org.junit.Assert.assertEquals
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
