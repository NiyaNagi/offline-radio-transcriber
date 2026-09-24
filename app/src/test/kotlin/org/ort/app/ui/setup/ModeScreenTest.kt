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
    fun `E2_E01 renders the three modes and its own stage counter`() {
        composeTestRule.setContent {
            OrtTheme { ModeScreen(onChoose = {}) }
        }

        composeTestRule.onNodeWithText("How is the radio connected?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Local microphone").assertIsDisplayed()
        composeTestRule.onNodeWithText("USB-connected radio").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bluetooth-connected radio").assertIsDisplayed()
        // P39: the denominator is derived now, and this is stage 1 of it.
        composeTestRule.onNodeWithText("1 of $SETUP_STEPS_WITHOUT_DOWNLOAD").assertIsDisplayed()
    }

    /**
     * **AC-204**: the microphone rationale is on the surface that asks for it, and there is no
     * dedicated explainer step preceding the system dialog. This screen is that surface — the tap
     * fires the request — so the rationale has to be *here* or it is nowhere.
     *
     * Asserted through the stable tag rather than the wording (constitution II), plus one property of
     * the wording that is not a matter of taste: it names the microphone, which is the word Android's
     * own dialog will use, and the operator has to recognise what they are being asked for.
     */
    @Test
    fun `AC_204 the microphone rationale rides on the mode surface, which is what fires the request`() {
        composeTestRule.setContent {
            OrtTheme { ModeScreen(onChoose = {}) }
        }

        composeTestRule.onNodeWithTag("setup-mode-microphone-rationale").assertIsDisplayed()
        assert(MICROPHONE_RATIONALE.contains("microphone")) {
            "the rationale must name what the system dialog will call it, got: $MICROPHONE_RATIONALE"
        }
    }

    /**
     * D58's *"three rows presented over the destination, no Continue, a tap advances"*. A confirm step
     * for a choice the operator has already made is one more page in a flow whose whole complaint was
     * that it had too many — and a lit `Continue` beside three tappable rows is also two ways to do
     * one thing. Asserted as the absence of any primary at all on this screen.
     */
    @Test
    fun `AC_198 the mode screen has no Continue at all -- a tap is the answer`() {
        composeTestRule.setContent {
            OrtTheme { ModeScreen(onChoose = {}) }
        }

        composeTestRule.onNodeWithText("Continue").assertDoesNotExist()
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
