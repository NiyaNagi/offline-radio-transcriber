package org.ort.app.ui.setup

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.ort.core.capture.CaptureMode
import org.robolectric.RobolectricTestRunner

/** E2-E01/E2-E06 (`spec/e2e-capture-modes-plan.md` WPD) — `Setup-Mode.dc.html` (S00). */
@RunWith(RobolectricTestRunner::class)
class ModeScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `E2_E01 renders the three modes with the board's exact copy and counter 1 of 8`() {
        composeTestRule.setContent {
            OrtTheme { ModeScreen(onChoose = {}) }
        }

        composeTestRule.onNodeWithText("How is the radio connected?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Local microphone").assertIsDisplayed()
        composeTestRule.onNodeWithText("USB-connected radio").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bluetooth-connected radio").assertIsDisplayed()
        composeTestRule.onNodeWithText("1 of 8").assertIsDisplayed()
    }

    @Test
    fun `E2_E01 tapping local microphone reports CaptureMode LOCAL_MICROPHONE`() {
        var chosen: CaptureMode? = null
        composeTestRule.setContent {
            OrtTheme { ModeScreen(onChoose = { chosen = it }) }
        }

        composeTestRule.onNodeWithTag("setup-mode-local-mic").performClick()
        assert(chosen == CaptureMode.LOCAL_MICROPHONE)
    }

    @Test
    fun `E2_E01 tapping USB radio reports CaptureMode USB_RADIO`() {
        var chosen: CaptureMode? = null
        composeTestRule.setContent {
            OrtTheme { ModeScreen(onChoose = { chosen = it }) }
        }

        composeTestRule.onNodeWithTag("setup-mode-usb").performClick()
        assert(chosen == CaptureMode.USB_RADIO)
    }

    @Test
    fun `E2_E01 tapping Bluetooth radio reports CaptureMode BLUETOOTH_RADIO`() {
        var chosen: CaptureMode? = null
        composeTestRule.setContent {
            OrtTheme { ModeScreen(onChoose = { chosen = it }) }
        }

        composeTestRule.onNodeWithTag("setup-mode-bluetooth").performClick()
        assert(chosen == CaptureMode.BLUETOOTH_RADIO)
    }

    /** R-851 (validator V8, device): the Bluetooth-connected-radio row rendered with no leading
     * icon at all, unlike the other two rows — `design/canvas/Setup-Mode.dc.html`'s own glyph
     * (now `OrtIcons.bluetooth`) closes the gap. All three rows carry one icon each. */
    @Test
    fun `R_851 all three mode rows render a leading icon`() {
        composeTestRule.setContent {
            OrtTheme { ModeScreen(onChoose = {}) }
        }

        composeTestRule.onAllNodesWithTag("navigation-row-icon", useUnmergedTree = true).assertCountEquals(3)
    }
}
