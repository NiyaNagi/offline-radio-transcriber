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
            OrtTheme {
                RouteMismatchScreen(
                    mismatch = mismatch,
                    selectedTypeLabel = "USB audio",
                    onChooseAnotherInput = {},
                    onTryAgain = {},
                )
            }
        }

        composeTestRule.onNodeWithText("That is not the radio").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "You chose USB Audio Device, but Android routed the recording to the phone's own mic. " +
                "Nothing has been recorded.",
        ).assertIsDisplayed()
    }

    @Test
    fun `R_122 identically-labelled devices are still told apart by their real type`() {
        // The validator's own emulator: every enumerated route shares the same raw label
        // (`sdk_gphone64_x86_64`) -- the type in parentheses is the only way to tell them apart.
        val sameLabelMismatch = RouteCheckState.Mismatch(
            selected = AudioDeviceDescriptor("usb-1", AudioDeviceKind.USB_DEVICE, "sdk_gphone64_x86_64"),
            routed = AudioDeviceDescriptor("mic-0", AudioDeviceKind.BUILT_IN_MIC, "sdk_gphone64_x86_64"),
            reason = "routed to the built-in mic",
        )
        composeTestRule.setContent {
            OrtTheme {
                RouteMismatchScreen(
                    mismatch = sameLabelMismatch,
                    selectedTypeLabel = "USB audio",
                    onChooseAnotherInput = {},
                    onTryAgain = {},
                )
            }
        }

        composeTestRule.onNodeWithText(
            "chosen sdk_gphone64_x86_64 (USB audio) · routed sdk_gphone64_x86_64 (Built-in microphone)",
        ).performScrollTo().assertIsDisplayed()
    }

    /** R-222 (validator pass 2): the *caller*-supplied [selectedTypeLabel] is what renders for the
     * chosen device -- not a re-derivation from [RouteCheckState.Mismatch.selected]'s own coarse
     * `AudioDeviceKind` (`UNKNOWN` here, which would print "Unknown" if this screen ignored the
     * parameter and fell back to its own [describeDevice]-style resolution). */
    @Test
    fun `R_222 the chosen device's richer type label from S04 is used, never re-derived and lost`() {
        val telephonyMismatch = RouteCheckState.Mismatch(
            selected = AudioDeviceDescriptor("tel-0", AudioDeviceKind.UNKNOWN, "sdk_gphone64_x86_64"),
            routed = AudioDeviceDescriptor("mic-0", AudioDeviceKind.BUILT_IN_MIC, "sdk_gphone64_x86_64"),
            reason = "routed to the built-in mic",
        )
        composeTestRule.setContent {
            OrtTheme {
                RouteMismatchScreen(
                    mismatch = telephonyMismatch,
                    selectedTypeLabel = "Telephony",
                    onChooseAnotherInput = {},
                    onTryAgain = {},
                )
            }
        }

        composeTestRule.onNodeWithText(
            "chosen sdk_gphone64_x86_64 (Telephony) · routed sdk_gphone64_x86_64 (Built-in microphone)",
        ).performScrollTo().assertIsDisplayed()
    }

    /** R-223 (validator pass 2): `Setup-Route-Mismatch.dc.html` draws the header chevron like
     * every other step; it was rendered nowhere at all before this. */
    @Test
    fun `R_223 the back chevron renders and invokes onBack`() {
        var backed = false
        composeTestRule.setContent {
            OrtTheme {
                RouteMismatchScreen(
                    mismatch = mismatch,
                    selectedTypeLabel = "USB audio",
                    onChooseAnotherInput = {},
                    onTryAgain = {},
                    onBack = { backed = true },
                )
            }
        }
        composeTestRule.onNodeWithTag("setup-back").performClick()
        assert(backed)
    }

    @Test
    fun `R_081 Choose another input and Try this one again both invoke their own callback`() {
        var chooseAnother = false
        var tryAgain = false
        composeTestRule.setContent {
            OrtTheme {
                RouteMismatchScreen(
                    mismatch = mismatch,
                    selectedTypeLabel = "USB audio",
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
