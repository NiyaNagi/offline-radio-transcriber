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

    // --- R-943 (register, reviewer A3 run 4a, design): checklist markers, the mono meta lines, ---
    // --- the elapsed counter and the Input waveform card, `Setup-Verify.dc.html` lines 40-91 --------

    @Test
    fun `R_943 a not-yet-reached check shows the board's dim pending ring, never an invisible fill`() {
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

        composeTestRule.onNodeWithTag("setup-verify-check-resampler-pending-ring").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("setup-verify-check-route-match-pending-ring")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `R_943 the native-rate detail formats Hz with grouped thousands, mono`() {
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

        composeTestRule.onNodeWithText("48 000 Hz · mono · 16-bit").assertIsDisplayed()
    }

    @Test
    fun `R_943 the routed device detail line names the real routed device once matched`() {
        composeTestRule.setContent {
            OrtTheme {
                VerifyScreen(
                    state = VerifyViewState(
                        inputLabel = "USB Audio Device",
                        check = RouteCheckState.InProgress(
                            passed = setOf(RouteCheckStage.NATIVE_RATE, RouteCheckStage.ROUTE_MATCH),
                            nativeRateHz = 48_000,
                            elapsedListeningMillis = 0L,
                            routedDeviceLabel = "USB Audio Device",
                        ),
                    ),
                    onContinue = {},
                    onBack = {},
                    onTryAgain = {},
                    onChooseAnotherInput = {},
                )
            }
        }

        composeTestRule.onNodeWithText("getRoutedDevice() → USB Audio Device").assertIsDisplayed()
    }

    @Test
    fun `R_943 no routed device label yet renders no detail line at all, never a placeholder`() {
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

        composeTestRule.onNodeWithText("getRoutedDevice", substring = true).assertDoesNotExist()
    }

    @Test
    fun `R_943 the elapsed counter renders beside Listening for signal while still listening`() {
        composeTestRule.setContent {
            OrtTheme {
                VerifyScreen(
                    state = VerifyViewState(
                        inputLabel = "USB Audio Device",
                        check = RouteCheckState.InProgress(
                            setOf(RouteCheckStage.NATIVE_RATE, RouteCheckStage.ROUTE_MATCH),
                            48_000,
                            elapsedListeningMillis = 11_000L,
                        ),
                    ),
                    onContinue = {},
                    onBack = {},
                    onTryAgain = {},
                    onChooseAnotherInput = {},
                )
            }
        }

        composeTestRule.onNodeWithText("0:11").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `R_943 the elapsed counter disappears once the signal check has passed`() {
        composeTestRule.setContent {
            OrtTheme {
                VerifyScreen(
                    state = VerifyViewState(
                        inputLabel = "USB Audio Device",
                        check = RouteCheckState.InProgress(
                            setOf(RouteCheckStage.NATIVE_RATE, RouteCheckStage.ROUTE_MATCH, RouteCheckStage.SIGNAL),
                            48_000,
                            elapsedListeningMillis = 11_000L,
                        ),
                    ),
                    onContinue = {},
                    onBack = {},
                    onTryAgain = {},
                    onChooseAnotherInput = {},
                )
            }
        }

        composeTestRule.onNodeWithText("0:11").assertDoesNotExist()
    }

    @Test
    fun `R_943 the Input waveform card renders with the real level samples`() {
        composeTestRule.setContent {
            OrtTheme {
                VerifyScreen(
                    state = VerifyViewState(
                        inputLabel = "USB Audio Device",
                        check = RouteCheckState.InProgress(
                            passed = setOf(RouteCheckStage.NATIVE_RATE, RouteCheckStage.ROUTE_MATCH),
                            nativeRateHz = 48_000,
                            elapsedListeningMillis = 4_000L,
                            levelBars = listOf(0.1f, 0.5f, 0.9f),
                            noiseFloorDbfs = -58.0,
                        ),
                    ),
                    onContinue = {},
                    onBack = {},
                    onTryAgain = {},
                    onChooseAnotherInput = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-verify-input-waveform").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("setup-verify-input-waveform-canvas").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("noise floor −58 dBFS").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `R_943 no real sample yet renders the honest dash, never a fabricated noise floor`() {
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

        composeTestRule.onNodeWithText("noise floor —").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `R_943 signal heard caption renders only once the signal stage has genuinely passed`() {
        composeTestRule.setContent {
            OrtTheme {
                VerifyScreen(
                    state = VerifyViewState(
                        inputLabel = "USB Audio Device",
                        check = RouteCheckState.InProgress(
                            setOf(RouteCheckStage.NATIVE_RATE, RouteCheckStage.ROUTE_MATCH),
                            48_000,
                            4_000L,
                        ),
                    ),
                    onContinue = {},
                    onBack = {},
                    onTryAgain = {},
                    onChooseAnotherInput = {},
                )
            }
        }

        composeTestRule.onNodeWithText("signal heard").assertDoesNotExist()
    }

    /**
     * R-981 (register, design, reopened): the board's own right-hand caption
     * (`Setup-Verify.dc.html` lines 87–90) is a real `space-between` row, not a badge that only
     * exists once heard — `results/ui-audit/setup-verified/S05-verify.png` (still mid-listen)
     * showed the left-hand noise floor with nothing at all on the right, a real gap this test
     * closes: an honest "not yet" — never invented, never blank — while genuinely still listening.
     */
    @Test
    fun `R_981 the waveform card names the real not-yet-heard fact, never rendering blank`() {
        composeTestRule.setContent {
            OrtTheme {
                VerifyScreen(
                    state = VerifyViewState(
                        inputLabel = "USB Audio Device",
                        check = RouteCheckState.InProgress(
                            setOf(RouteCheckStage.NATIVE_RATE, RouteCheckStage.ROUTE_MATCH),
                            48_000,
                            4_000L,
                        ),
                    ),
                    onContinue = {},
                    onBack = {},
                    onTryAgain = {},
                    onChooseAnotherInput = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-verify-input-waveform-signal-caption")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("not yet").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `R_943 signal heard caption renders once the signal stage has passed`() {
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

        composeTestRule.onNodeWithText("signal heard").performScrollTo().assertIsDisplayed()
    }

    // --- formatHzGrouped/formatElapsed: pure formatting helpers -----------------------------------

    @Test
    fun `formatHzGrouped groups thousands with a space, matching the board`() {
        assert(formatHzGrouped(48_000) == "48 000") { formatHzGrouped(48_000) }
        assert(formatHzGrouped(16_000) == "16 000") { formatHzGrouped(16_000) }
        assert(formatHzGrouped(999) == "999") { formatHzGrouped(999) }
    }

    @Test
    fun `formatElapsed formats minutes and zero-padded seconds, matching the board's 0_11`() {
        assert(formatElapsed(11_000L) == "0:11") { formatElapsed(11_000L) }
        assert(formatElapsed(65_000L) == "1:05") { formatElapsed(65_000L) }
        assert(formatElapsed(0L) == "0:00") { formatElapsed(0L) }
    }
}
