package org.ort.app.ui.setup

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
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

    /** R-280 (validator pass 3): S06 is one of the screens named for the ghost, semi-transparent
     * duplicate of the bottom bar's secondary action reported near the status bar -- proof
     * `MicrophoneScreensTest`'s own `R_220` test already carries for S02b's "Check again". */
    @Test
    fun `R_280 exactly one Try this one again renders, never a ghost duplicate`() {
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
        composeTestRule.onAllNodesWithText("Try this one again").assertCountEquals(1)
    }

    /** R-282 (validator pass 3): at font scale 2.0 the mono "chosen X · routed Y" descriptor line
     * was cut mid-word by the fixed bar (`setup/S06-route-mismatch@2x.png`) — `SetupScaffold`'s own
     * shared `weight(1f)` + `verticalScroll` mechanism already covers this screen like every other;
     * this is the per-screen regression proof `SetupScaffoldTest`'s generic version cannot stand in
     * for, matching `WelcomeScreenTest`'s/`InputScreenTest`'s own R-123 tests for S01/S04. */
    @Test
    fun `R_282 the mono descriptor line scrolls clear of the fixed bar at font scale 2_0`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrtTheme {
                    RouteMismatchScreen(
                        mismatch = mismatch,
                        selectedTypeLabel = "USB audio",
                        onChooseAnotherInput = {},
                        onTryAgain = {},
                    )
                }
            }
        }

        composeTestRule.onNodeWithText(
            "chosen USB Audio Device (USB audio) · routed Built-in microphone (Built-in microphone)",
        ).performScrollTo().assertIsDisplayed()
    }
}
