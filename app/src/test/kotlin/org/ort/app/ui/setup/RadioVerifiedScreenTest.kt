package org.ort.app.ui.setup

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.ort.pipeline.capture.RigStatus
import org.robolectric.RobolectricTestRunner

/** R-084 (ui-conformance-plan WP9) — `Setup-Rig-Verified.dc.html` (S11). Reachable today only via
 * the debug scenario simulator constructing a real [RigStatus.State.Connected] directly (see
 * [RadioUsbScreenTest]'s identical caveat) — the rendering itself is real, the entry path is not. */
@RunWith(RobolectricTestRunner::class)
class RadioVerifiedScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val connected = RigStatus.State.Connected(
        descriptor = "Kenwood TH-D75A",
        bands = listOf(
            RigStatus.BandState("A", 145_230_000L, "FM", squelchOpen = true),
            RigStatus.BandState("B", 146_960_000L, "FM", squelchOpen = false),
        ),
    )

    @Test
    fun `R_084 shows the connected title, both bands and the verified command list`() {
        composeTestRule.setContent {
            OrtTheme {
                RadioVerifiedScreen(state = connected, onContinue = {}, onChangeRadio = {}, onReconnect = {})
            }
        }

        composeTestRule.onNodeWithText("Kenwood TH-D75A connected").assertIsDisplayed()
        composeTestRule.onNodeWithTag("setup-radio-verified-band-A").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("setup-radio-verified-band-B").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Squelch state — attributes each over to a band")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `R_084 Continue and Change radio both invoke their own callback`() {
        var continued = false
        var changed = false
        composeTestRule.setContent {
            OrtTheme {
                RadioVerifiedScreen(
                    state = connected,
                    onContinue = { continued = true },
                    onChangeRadio = { changed = true },
                    onReconnect = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-radio-verified-continue").performClick()
        assert(continued)
        composeTestRule.onNodeWithTag("setup-radio-verified-change").performClick()
        assert(changed)
    }

    // --- R-125 (validator finding, halt): Stale must render, never a blank screen --------------

    private val stale = RigStatus.State.Stale(lastKnown = connected, sinceMillis = 0L)

    @Test
    fun `R_125 a Stale rig renders the last-known reading, never a blank screen`() {
        composeTestRule.setContent {
            OrtTheme { RadioVerifiedScreen(state = stale, onContinue = {}, onChangeRadio = {}, onReconnect = {}) }
        }

        composeTestRule.onNodeWithText("Kenwood TH-D75A — last known").assertIsDisplayed()
        composeTestRule.onNodeWithTag("setup-radio-verified-band-A").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("setup-radio-verified-reconnect").assertIsDisplayed()
        composeTestRule.onNodeWithTag("setup-radio-verified-change").assertIsDisplayed()
        // No Continue on a stale reading -- Reconnect/Change radio are the only ways forward.
        composeTestRule.onNodeWithTag("setup-radio-verified-continue").assertDoesNotExist()
    }

    @Test
    fun `R_125 Reconnect and Change radio on a Stale rig both invoke their own callback`() {
        var reconnected = false
        var changed = false
        composeTestRule.setContent {
            OrtTheme {
                RadioVerifiedScreen(
                    state = stale,
                    onContinue = {},
                    onChangeRadio = { changed = true },
                    onReconnect = { reconnected = true },
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-radio-verified-reconnect").performClick()
        assert(reconnected)
        composeTestRule.onNodeWithTag("setup-radio-verified-change").performClick()
        assert(changed)
    }
}
