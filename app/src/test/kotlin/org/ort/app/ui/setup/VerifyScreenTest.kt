package org.ort.app.ui.setup

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner

/** R-081 (ui-conformance-plan WP9) — `Setup-Verify.dc.html` (S05). */
@RunWith(RobolectricTestRunner::class)
class VerifyScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `R_081 Continue is disabled while checks are still in progress`() {
        composeTestRule.setContent {
            OrtTheme {
                VerifyScreen(
                    state = VerifyViewState(
                        inputLabel = "USB Audio Device",
                        check = RouteCheckState.InProgress(setOf(RouteCheckStage.NATIVE_RATE), 48_000, 0L),
                    ),
                    onContinue = {},
                    onBack = {},
                    onTryAgain = {},
                    onChooseAnotherInput = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-verify-continue").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("setup-verify-check-native-rate").assertIsDisplayed()
    }

    @Test
    fun `R_081 Continue enables once every check has passed, and invokes the callback`() {
        var continued = false
        composeTestRule.setContent {
            OrtTheme {
                VerifyScreen(
                    state = VerifyViewState(
                        inputLabel = "USB Audio Device",
                        check = RouteCheckState.Passed(48_000, null),
                    ),
                    onContinue = { continued = true },
                    onBack = {},
                    onTryAgain = {},
                    onChooseAnotherInput = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-verify-continue").assertIsEnabled()
        composeTestRule.onNodeWithTag("setup-verify-continue").performClick()
        assert(continued)
    }

    @Test
    fun `R_081 the back chevron is present even while checks are still in progress`() {
        var back = false
        composeTestRule.setContent {
            OrtTheme {
                VerifyScreen(
                    state = VerifyViewState(
                        inputLabel = "USB Audio Device",
                        check = RouteCheckState.InProgress(setOf(RouteCheckStage.NATIVE_RATE), 48_000, 0L),
                    ),
                    onContinue = {},
                    onBack = { back = true },
                    onTryAgain = {},
                    onChooseAnotherInput = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-back").performClick()
        assert(back)
    }

    // --- R-121 (validator finding, halt): RouteCheckState.TimedOut ----------------------------

    @Test
    fun `R_121 a timeout keeps the two checks that already passed ticked and fails the signal check honestly`() {
        composeTestRule.setContent {
            OrtTheme {
                VerifyScreen(
                    state = VerifyViewState(inputLabel = "USB Audio Device", check = RouteCheckState.TimedOut),
                    onContinue = {},
                    onBack = {},
                    onTryAgain = {},
                    onChooseAnotherInput = {},
                )
            }
        }

        composeTestRule.onNodeWithText("No signal heard in 30 s on USB Audio Device")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("setup-verify-check-native-rate").assertIsDisplayed()
        composeTestRule.onNodeWithTag("setup-verify-check-route-match").assertIsDisplayed()
    }

    @Test
    fun `R_121 a timeout never leaves Continue as the only way forward, and the back chevron always exists`() {
        composeTestRule.setContent {
            OrtTheme {
                VerifyScreen(
                    state = VerifyViewState(inputLabel = "USB Audio Device", check = RouteCheckState.TimedOut),
                    onContinue = {},
                    onBack = {},
                    onTryAgain = {},
                    onChooseAnotherInput = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-verify-continue").assertDoesNotExist()
        composeTestRule.onNodeWithTag("setup-verify-try-again").assertIsDisplayed()
        composeTestRule.onNodeWithTag("setup-verify-choose-another").assertIsDisplayed()
        composeTestRule.onNodeWithTag("setup-back").assertIsDisplayed()
    }

    @Test
    fun `R_121 Try again and Choose another input on a timeout both invoke their own callback`() {
        var tryAgain = false
        var chooseAnother = false
        composeTestRule.setContent {
            OrtTheme {
                VerifyScreen(
                    state = VerifyViewState(inputLabel = "USB Audio Device", check = RouteCheckState.TimedOut),
                    onContinue = {},
                    onBack = {},
                    onTryAgain = { tryAgain = true },
                    onChooseAnotherInput = { chooseAnother = true },
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-verify-try-again").performClick()
        assert(tryAgain)
        composeTestRule.onNodeWithTag("setup-verify-choose-another").performClick()
        assert(chooseAnother)
    }

    /** R-280 (validator pass 3): S05's own timed-out "Choose another input" was named among the
     * screens where a ghost, semi-transparent duplicate of the bottom bar's secondary action was
     * reported near the status bar -- proof `MicrophoneScreensTest`'s own `R_220` test already
     * carries for S02b's "Check again". */
    @Test
    fun `R_280 exactly one Choose another input renders on a timeout, never a ghost duplicate`() {
        composeTestRule.setContent {
            OrtTheme {
                VerifyScreen(
                    state = VerifyViewState(inputLabel = "USB Audio Device", check = RouteCheckState.TimedOut),
                    onContinue = {},
                    onBack = {},
                    onTryAgain = {},
                    onChooseAnotherInput = {},
                )
            }
        }
        composeTestRule.onAllNodesWithText("Choose another input").assertCountEquals(1)
    }

    // --- R-284 (validator pass 3): "Choose a different input" during the listening check --------

    @Test
    fun `R_284 the listening check offers Choose a different input, not just the disabled Continue`() {
        var chosenDifferent = false
        composeTestRule.setContent {
            OrtTheme {
                VerifyScreen(
                    state = VerifyViewState(
                        inputLabel = "USB Audio Device",
                        check = RouteCheckState.InProgress(
                            setOf(RouteCheckStage.NATIVE_RATE, RouteCheckStage.ROUTE_MATCH),
                            48_000,
                            11_000L,
                        ),
                    ),
                    onContinue = {},
                    onBack = {},
                    onTryAgain = {},
                    onChooseAnotherInput = { chosenDifferent = true },
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-verify-continue").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("setup-verify-choose-different").assertIsDisplayed().performClick()
        assert(chosenDifferent)
    }

    @Test
    fun `R_284 the link is gone once every check has already passed`() {
        composeTestRule.setContent {
            OrtTheme {
                VerifyScreen(
                    state = VerifyViewState(
                        inputLabel = "USB Audio Device",
                        check = RouteCheckState.Passed(48_000, null),
                    ),
                    onContinue = {},
                    onBack = {},
                    onTryAgain = {},
                    onChooseAnotherInput = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-verify-choose-different").assertDoesNotExist()
    }
}
