package org.ort.app.ui.setup

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.ort.pipeline.capture.RigStatus
import org.robolectric.RobolectricTestRunner

/**
 * R-084 (ui-conformance-plan WP9) — `Setup-Rig-Usb.dc.html` (S10). `:rig`/`:rig-usb` are unbuilt
 * (register R-084), so [RigStatus.State.Absent] — the only real producer today — must render an
 * honest "no rig support" failed state, never a fabricated USB-attach checklist. WP2's shared
 * `TextField` (its follow-up landed after this screen's first version) now actually captures the
 * frequency the operator is invited to enter by hand.
 */
@RunWith(RobolectricTestRunner::class)
class RadioUsbScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `R_084 Absent renders an honest no-rig-support state, never a fabricated checklist`() {
        composeTestRule.setContent {
            OrtTheme { RadioUsbScreen(rigStatus = RigStatus.State.Absent, onBack = {}, onEnterFrequency = {}) }
        }

        composeTestRule.onNodeWithTag("setup-radio-usb-unsupported").assertIsDisplayed()
        composeTestRule.onNodeWithText("No rig support in this build yet").assertIsDisplayed()
    }

    @Test
    fun `R_084 Enter the frequency instead with a blank field reports null, never a fabricated frequency`() {
        var entered: Long? = -1L // sentinel distinct from both null and any real value
        var back = false
        composeTestRule.setContent {
            OrtTheme {
                RadioUsbScreen(
                    rigStatus = RigStatus.State.Absent,
                    onBack = { back = true },
                    onEnterFrequency = { entered = it },
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-radio-usb-enter-frequency").performClick()
        assertNull(entered)
        composeTestRule.onNodeWithTag("setup-radio-usb-back").performClick()
        assert(back)
    }

    @Test
    fun `R_084 typing a frequency and confirming reports its exact Hz value`() {
        var entered: Long? = null
        composeTestRule.setContent {
            OrtTheme {
                RadioUsbScreen(rigStatus = RigStatus.State.Absent, onBack = {}, onEnterFrequency = { entered = it })
            }
        }

        composeTestRule.onNodeWithTag("setup-radio-usb-frequency-field").performTextInput("145.230")
        composeTestRule.onNodeWithTag("setup-radio-usb-enter-frequency").performClick()

        assertEquals(145_230_000L, entered)
    }

    @Test
    fun `R_084 a Connected reading (only reachable via the debug scenario simulator) renders the verified content`() {
        val connected = RigStatus.State.Connected(
            descriptor = "Kenwood TH-D75A",
            bands = listOf(RigStatus.BandState("A", 145_230_000L, "FM", squelchOpen = true)),
        )
        composeTestRule.setContent {
            OrtTheme { RadioUsbScreen(rigStatus = connected, onBack = {}, onEnterFrequency = {}) }
        }

        composeTestRule.onNodeWithText("Reading now".uppercase()).assertIsDisplayed()
    }

    // --- parseMegahertzToHz: pure, no Compose ----------------------------------------------------

    @Test
    fun `R_084 parseMegahertzToHz converts a plain MHz value to exact Hz`() {
        assertEquals(145_230_000L, parseMegahertzToHz("145.230"))
    }

    @Test
    fun `R_084 parseMegahertzToHz returns null for blank, zero or unparseable text, never a guess`() {
        assertNull(parseMegahertzToHz(""))
        assertNull(parseMegahertzToHz("   "))
        assertNull(parseMegahertzToHz("not a number"))
        assertNull(parseMegahertzToHz("0"))
        assertNull(parseMegahertzToHz("-5"))
    }
}
