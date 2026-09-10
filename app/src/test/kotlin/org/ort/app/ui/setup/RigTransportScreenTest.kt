package org.ort.app.ui.setup

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.ort.rig.RigTransportKind
import org.robolectric.RobolectricTestRunner

/** E2-E09 (`spec/e2e-capture-modes-plan.md` WPD) — `Setup-Rig-Transport.dc.html` (S09b). */
@RunWith(RobolectricTestRunner::class)
class RigTransportScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val bluetooth = RigTransportOption(
        kind = RigTransportKind.BLUETOOTH_SPP,
        label = "Bluetooth SPP",
        subLabel = "preset by your mode · pair in system settings first",
        capabilities = listOf("frequency", "squelch"),
        costLine = "Drops more often than a cable — capture continues, frequency marked stale",
        isPreset = true,
    )
    private val usb = RigTransportOption(
        kind = RigTransportKind.USB_SERIAL,
        label = "USB serial",
        subLabel = "USB-C to the radio's data port · CDC, no driver",
        capabilities = listOf("frequency", "squelch"),
        costLine = "USB permission does not survive a re-plug",
        isPreset = false,
    )

    private fun state(selected: RigTransportKind? = RigTransportKind.BLUETOOTH_SPP) = RigTransportViewState(
        rigDisplayName = "TH-D75A",
        options = listOf(bluetooth, usb),
        selected = selected,
    )

    @Test
    fun `E2_E09 shows both transports with capabilities and the cost of each`() {
        composeTestRule.setContent {
            OrtTheme { RigTransportScreen(state = state(), onSelect = {}, onConnect = {}, onBack = {}) }
        }

        composeTestRule.onNodeWithText("How is the TH-D75A linked?").assertIsDisplayed()
        composeTestRule.onNodeWithTag("setup-rig-transport-option-bluetooth_spp").assertIsDisplayed()
        composeTestRule.onNodeWithTag("setup-rig-transport-option-usb_serial").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `E2_E09 the preset transport is preselected and Connect names it`() {
        composeTestRule.setContent {
            OrtTheme { RigTransportScreen(state = state(selected = RigTransportKind.BLUETOOTH_SPP), onSelect = {}, onConnect = {}, onBack = {}) }
        }

        composeTestRule.onNodeWithText("Connect over Bluetooth SPP").assertIsDisplayed()
    }

    @Test
    fun `E2_E09 selecting the other transport updates which one Connect targets`() {
        var selected: RigTransportKind? = null
        composeTestRule.setContent {
            OrtTheme {
                RigTransportScreen(
                    state = state(selected = RigTransportKind.BLUETOOTH_SPP),
                    onSelect = { selected = it },
                    onConnect = {},
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-rig-transport-option-usb_serial").performScrollTo().performClick()
        assert(selected == RigTransportKind.USB_SERIAL)
    }

    @Test
    fun `E2_E09 Connect is disabled until a transport is selected`() {
        composeTestRule.setContent {
            OrtTheme { RigTransportScreen(state = state(selected = null), onSelect = {}, onConnect = {}, onBack = {}) }
        }

        composeTestRule.onNodeWithTag("setup-rig-transport-connect").assertIsNotEnabled()
    }

    @Test
    fun `E2_E09 Connect invokes its callback once a transport is selected`() {
        var connected = false
        composeTestRule.setContent {
            OrtTheme { RigTransportScreen(state = state(), onSelect = {}, onConnect = { connected = true }, onBack = {}) }
        }

        composeTestRule.onNodeWithTag("setup-rig-transport-connect").assertIsEnabled().performClick()
        assert(connected)
    }

    @Test
    fun `E2_E09 Back invokes its callback`() {
        var back = false
        composeTestRule.setContent {
            OrtTheme { RigTransportScreen(state = state(), onSelect = {}, onConnect = {}, onBack = { back = true }) }
        }

        composeTestRule.onNodeWithTag("setup-rig-transport-back").performClick()
        assert(back)
    }
}
