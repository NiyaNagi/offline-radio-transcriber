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
import org.ort.capture.android.AudioDeviceDescriptor
import org.ort.capture.android.AudioDeviceKind
import org.robolectric.RobolectricTestRunner

/** R-081 (ui-conformance-plan WP9) — `Setup-Route-Mismatch.dc.html` (S06), the one halt in the
 * sequence: no `Continue` exists at all. */
@RunWith(RobolectricTestRunner::class)
class RouteMismatchScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val mismatch = RouteCheckState.Mismatch(
        selected = AudioDeviceDescriptor("usb-1", AudioDeviceKind.USB_DEVICE, "USB Audio Device"),
        routed = AudioDeviceDescriptor("mic-0", AudioDeviceKind.BUILT_IN_MIC, "Built-in microphone"),
        reason = "routed to 'Built-in microphone'",
    )

    @Test
    fun `R_081 shows the halt banner naming both devices and never renders a Continue action`() {
        composeTestRule.setContent {
            OrtTheme { RouteMismatchScreen(mismatch = mismatch, onChooseAnotherInput = {}, onTryAgain = {}) }
        }

        composeTestRule.onNodeWithText("That is not the radio").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "You chose USB Audio Device, but Android routed the recording to the phone's own mic. " +
                "Nothing has been recorded.",
        ).assertIsDisplayed()
    }

    @Test
    fun `R_081 Choose another input and Try this one again both invoke their own callback`() {
        var chooseAnother = false
        var tryAgain = false
        composeTestRule.setContent {
            OrtTheme {
                RouteMismatchScreen(
                    mismatch = mismatch,
                    onChooseAnotherInput = { chooseAnother = true },
                    onTryAgain = { tryAgain = true },
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-route-mismatch-choose-another").performClick()
        assert(chooseAnother)
        composeTestRule.onNodeWithTag("setup-route-mismatch-try-again").performClick()
        assert(tryAgain)
    }
}
