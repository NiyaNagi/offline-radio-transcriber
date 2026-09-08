package org.ort.app.ui.setup

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.ort.pipeline.capture.RigStatus
import org.robolectric.RobolectricTestRunner

/**
 * R-084 (ui-conformance-plan WP9) — `Setup-Rig-Usb.dc.html` (S10). `:rig`/`:rig-usb` are unbuilt
 * (register R-084), so [RigStatus.State.Absent] — the only real producer today — must render an
 * honest "no rig support" failed state, never a fabricated USB-attach checklist.
 */
@RunWith(RobolectricTestRunner::class)
class RadioUsbScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `R_084 Absent renders an honest no-rig-support state, never a fabricated checklist`() {
        composeTestRule.setContent {
            OrtTheme { RadioUsbScreen(rigStatus = RigStatus.State.Absent, onBack = {}, onEnterFrequencyInstead = {}) }
        }

        composeTestRule.onNodeWithTag("setup-radio-usb-unsupported").assertIsDisplayed()
        composeTestRule.onNodeWithText("No rig support in this build yet").assertIsDisplayed()
    }

    @Test
    fun `R_084 Enter the frequency instead and Back both invoke their own callback`() {
        var enteredFrequency = false
        var back = false
        composeTestRule.setContent {
            OrtTheme {
                RadioUsbScreen(
                    rigStatus = RigStatus.State.Absent,
                    onBack = { back = true },
                    onEnterFrequencyInstead = { enteredFrequency = true },
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-radio-usb-enter-frequency").performClick()
        assert(enteredFrequency)
        composeTestRule.onNodeWithTag("setup-radio-usb-back").performClick()
        assert(back)
    }

    @Test
    fun `R_084 a Connected reading (only reachable via the debug scenario simulator) renders the verified content`() {
        val connected = RigStatus.State.Connected(
            descriptor = "Kenwood TH-D75A",
            bands = listOf(RigStatus.BandState("A", 145_230_000L, "FM", squelchOpen = true)),
        )
        composeTestRule.setContent {
            OrtTheme { RadioUsbScreen(rigStatus = connected, onBack = {}, onEnterFrequencyInstead = {}) }
        }

        composeTestRule.onNodeWithText("Reading now".uppercase()).assertIsDisplayed()
    }
}
