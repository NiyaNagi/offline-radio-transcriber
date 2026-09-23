package org.ort.app.ui.setup

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
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
            OrtTheme {
                RigTransportScreen(
                    state = state(selected = RigTransportKind.BLUETOOTH_SPP),
                    onSelect = {},
                    onConnect = {},
                    onBack = {},
                )
            }
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

    /**
     * E2-E09, amended by **R-1170**: this used to assert `setup-rig-transport-connect` was
     * *disabled* until a transport was selected. The assertion is inverted, not deleted — the
     * button is lit and refuses on tap instead; the R-1170 tests below pin that it still does not
     * connect with nothing chosen.
     */
    @Test
    fun `E2_E09 Connect stays lit until a transport is selected`() {
        composeTestRule.setContent {
            OrtTheme { RigTransportScreen(state = state(selected = null), onSelect = {}, onConnect = {}, onBack = {}) }
        }

        composeTestRule.onNodeWithTag("setup-rig-transport-connect").assertIsEnabled()
    }

    // --- R-1170: never disable a primary button for validation state -------------------------------

    /** R-1170: lit with nothing selected, and tapping it says what to pick rather than connecting
     * over a transport the operator never chose. */
    @Test
    fun `R_1170 Connect with nothing selected does not connect and says what to pick`() {
        var connected = false
        composeTestRule.setContent {
            OrtTheme {
                RigTransportScreen(
                    state = state(selected = null),
                    onSelect = {},
                    onConnect = { connected = true },
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-rig-transport-connect").assertIsEnabled().performClick()
        assert(!connected)
        composeTestRule.onNodeWithTag("setup-rig-transport-validation").assertIsDisplayed()
    }

    /** R-1170, the accessibility half. */
    @Test
    fun `R_1170 the transport notice is a live region a screen reader announces`() {
        composeTestRule.setContent {
            OrtTheme { RigTransportScreen(state = state(selected = null), onSelect = {}, onConnect = {}, onBack = {}) }
        }

        composeTestRule.onNodeWithTag("setup-rig-transport-connect").performClick()
        composeTestRule.onNodeWithTag("setup-rig-transport-validation")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive))
    }

    /** R-1170: no standing scold on arrival. */
    @Test
    fun `R_1170 no transport notice is shown before the primary has been tapped`() {
        composeTestRule.setContent {
            OrtTheme { RigTransportScreen(state = state(selected = null), onSelect = {}, onConnect = {}, onBack = {}) }
        }

        composeTestRule.onNodeWithTag("setup-rig-transport-validation").assertDoesNotExist()
    }

    /** R-1170, pure: the refusal names the rig the operator is looking at, not a generic "make a
     * selection" — the whole question on S09b is how *this* rig is wired. */
    @Test
    fun `R_1170 rigTransportValidationMessage names the rig, and is null once a transport is chosen`() {
        assert(rigTransportValidationMessage(state()) == null)
        val missing = rigTransportValidationMessage(state(selected = null))
        assert(missing != null && missing.contains("TH-D75A"))
    }

    @Test
    fun `E2_E09 Connect invokes its callback once a transport is selected`() {
        var connected = false
        composeTestRule.setContent {
            OrtTheme {
                RigTransportScreen(
                    state = state(),
                    onSelect = {},
                    onConnect = { connected = true },
                    onBack = {},
                )
            }
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

    // --- D33/E2-E09 (WPI's setup-rig-transport-preset scenario): presetRigTransportFor only ------
    // --- names a transport the chosen rig's own descriptor actually supports ------------------------

    @Test
    fun `presetRigTransportFor returns the mode preset when the rig supports it`() {
        val preset = presetRigTransportFor(
            modePresetKind = org.ort.core.capture.RigTransportKind.BLUETOOTH_SPP,
            supportedTransports = setOf(RigTransportKind.BLUETOOTH_SPP, RigTransportKind.USB_SERIAL),
        )

        assert(preset == RigTransportKind.BLUETOOTH_SPP) { "got $preset" }
    }

    @Test
    fun `presetRigTransportFor returns null when the rig does not support the mode preset`() {
        val preset = presetRigTransportFor(
            modePresetKind = org.ort.core.capture.RigTransportKind.BLUETOOTH_SPP,
            supportedTransports = setOf(RigTransportKind.USB_SERIAL),
        )

        assert(preset == null) { "must never guess a transport the rig cannot actually prove it supports, got $preset" }
    }

    @Test
    fun `presetRigTransportFor returns null when the mode has no rig-transport preset at all`() {
        val preset = presetRigTransportFor(
            modePresetKind = null,
            supportedTransports = setOf(RigTransportKind.BLUETOOTH_SPP, RigTransportKind.USB_SERIAL),
        )

        assert(preset == null) { "got $preset" }
    }
}
