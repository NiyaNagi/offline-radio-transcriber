package org.ort.app.ui.setup

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
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** R-081 (ui-conformance-plan WP9) — `Setup-Verify.dc.html` (S05). */
@RunWith(RobolectricTestRunner::class)
// P39: the merged Listen screen is genuinely taller than Robolectric's 320x470dp default, so these
// assertions are made at the tour's own geometry rather than at a viewport no real device has.
@Config(qualifiers = "w390dp-h844dp-420dpi")
class VerifyScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /**
     * P39 (D58): the route check is a section of [ListenScreen] now, not a screen of its own, so every
     * assertion below drives the real merged screen with the route list above it.
     *
     * [ListenViewState.inputVerified] is derived from the check rather than passed in, because in
     * production `SetupActivity.onVerifyStateChanged` writes `SetupStore.inputVerified` the instant a
     * check passes — a passed check beside an unverified store is a state the app cannot produce, and
     * a fixture that could produce it would be testing something that never happens.
     */
    private fun listenStateFor(inputLabel: String, check: RouteCheckState?) = ListenViewState(
        routes = emptyList(),
        selectedId = "usb-1",
        inputLabel = inputLabel,
        check = check,
        checkRunning = true,
        inputVerified = check is RouteCheckState.Passed,
    )

    /**
     * R-1169: the one fact that says whether the OEM was applying its own gain and noise
     * suppression to this capture, named beside the rate it opened at. Before this there was no way
     * to tell from a capture at all.
     */
    @Test
    @Requirement("FR-CAP-1", "R-1169")
    fun `FR_CAP_1 the native rate check names the audio source the open actually obtained`() {
        composeTestRule.setContent {
            OrtTheme {
                ListenScreen(
                    state = listenStateFor(
                        inputLabel = "USB Audio Device",
                        check = RouteCheckState.Passed(48_000, null, audioSourceLabel = "unprocessed"),
                    ),
                    onBack = {},
                    actions = ListenActions(
                        onContinue = {},
                        onTryAgain = {},
                        onChooseAnotherInput = {},
                        onSelect = {},
                        onRefresh = {},
                        onVerify = {},
                    ),
                )
            }
        }

        composeTestRule.onNodeWithText("48 000 Hz · mono · 16-bit · unprocessed").assertExists()
    }

    @Test
    @Requirement("FR-CAP-1", "R-1169")
    fun `FR_CAP_1 a check that reports no audio source names the rate alone, never a guessed source`() {
        composeTestRule.setContent {
            OrtTheme {
                ListenScreen(
                    state = listenStateFor(
                        inputLabel = "USB Audio Device",
                        check = RouteCheckState.Passed(48_000, null),
                    ),
                    onBack = {},
                    actions = ListenActions(
                        onContinue = {},
                        onTryAgain = {},
                        onChooseAnotherInput = {},
                        onSelect = {},
                        onRefresh = {},
                        onVerify = {},
                    ),
                )
            }
        }

        composeTestRule.onNodeWithText("48 000 Hz · mono · 16-bit").assertExists()
    }

    /**
     * R-081, amended by **R-1170**: this used to assert `setup-listen-continue` was *disabled*
     * while checks were in progress. The button is now lit at all times and refuses on tap — the
     * disabled assertion was inverted, not deleted, and the R-1170 tests at the foot of this file
     * pin the thing that actually matters: an unverified route still cannot proceed
     * (constitution IV — `ROUTE_MISMATCH` is the one deliberate hard halt in the flow).
     */
    @Test
    fun `R_081 Continue stays lit while checks are still in progress`() {
        composeTestRule.setContent {
            OrtTheme {
                ListenScreen(
                    state = listenStateFor(
                        inputLabel = "USB Audio Device",
                        check = RouteCheckState.InProgress(setOf(RouteCheckStage.NATIVE_RATE), 48_000, 0L),
                    ),
                    onBack = {},
                    actions = ListenActions(
                        onContinue = {},
                        onTryAgain = {},
                        onChooseAnotherInput = {},
                        onSelect = {},
                        onRefresh = {},
                        onVerify = {},
                    ),
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-listen-continue").assertIsEnabled()
        composeTestRule.onNodeWithTag("setup-verify-check-native-rate").assertIsDisplayed()
    }

    @Test
    fun `R_081 Continue enables once every check has passed, and invokes the callback`() {
        var continued = false
        composeTestRule.setContent {
            OrtTheme {
                ListenScreen(
                    state = listenStateFor(
                        inputLabel = "USB Audio Device",
                        check = RouteCheckState.Passed(48_000, null),
                    ),
                    onBack = {},
                    actions = ListenActions(
                        onContinue = { continued = true },
                        onTryAgain = {},
                        onChooseAnotherInput = {},
                        onSelect = {},
                        onRefresh = {},
                        onVerify = {},
                    ),
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-listen-continue").assertIsEnabled()
        composeTestRule.onNodeWithTag("setup-listen-continue").performClick()
        assert(continued)
    }

    @Test
    fun `R_081 the back chevron is present even while checks are still in progress`() {
        var back = false
        composeTestRule.setContent {
            OrtTheme {
                ListenScreen(
                    state = listenStateFor(
                        inputLabel = "USB Audio Device",
                        check = RouteCheckState.InProgress(setOf(RouteCheckStage.NATIVE_RATE), 48_000, 0L),
                    ),
                    onBack = { back = true },
                    actions = ListenActions(
                        onContinue = {},
                        onTryAgain = {},
                        onChooseAnotherInput = {},
                        onSelect = {},
                        onRefresh = {},
                        onVerify = {},
                    ),
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-back").performClick()
        assert(back)
    }

    // --- R-943 (register, reviewer A3 run 4a, design): checklist markers, the mono meta lines, ---
    // --- the elapsed counter and the Input waveform card, `Setup-Verify.dc.html` lines 40-91 --------

    @Test
    fun `R_943 a not-yet-reached check shows the board's dim pending ring, never an invisible fill`() {
        composeTestRule.setContent {
            OrtTheme {
                ListenScreen(
                    state = listenStateFor(
                        inputLabel = "USB Audio Device",
                        check = RouteCheckState.InProgress(setOf(RouteCheckStage.NATIVE_RATE), 48_000, 0L),
                    ),
                    onBack = {},
                    actions = ListenActions(
                        onContinue = {},
                        onTryAgain = {},
                        onChooseAnotherInput = {},
                        onSelect = {},
                        onRefresh = {},
                        onVerify = {},
                    ),
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
                ListenScreen(
                    state = listenStateFor(
                        inputLabel = "USB Audio Device",
                        check = RouteCheckState.InProgress(setOf(RouteCheckStage.NATIVE_RATE), 48_000, 0L),
                    ),
                    onBack = {},
                    actions = ListenActions(
                        onContinue = {},
                        onTryAgain = {},
                        onChooseAnotherInput = {},
                        onSelect = {},
                        onRefresh = {},
                        onVerify = {},
                    ),
                )
            }
        }

        composeTestRule.onNodeWithText("48 000 Hz · mono · 16-bit").assertIsDisplayed()
    }

    @Test
    fun `R_943 the routed device detail line names the real routed device once matched`() {
        composeTestRule.setContent {
            OrtTheme {
                ListenScreen(
                    state = listenStateFor(
                        inputLabel = "USB Audio Device",
                        check = RouteCheckState.InProgress(
                            passed = setOf(RouteCheckStage.NATIVE_RATE, RouteCheckStage.ROUTE_MATCH),
                            nativeRateHz = 48_000,
                            elapsedListeningMillis = 0L,
                            routedDeviceLabel = "USB Audio Device",
                        ),
                    ),
                    onBack = {},
                    actions = ListenActions(
                        onContinue = {},
                        onTryAgain = {},
                        onChooseAnotherInput = {},
                        onSelect = {},
                        onRefresh = {},
                        onVerify = {},
                    ),
                )
            }
        }

        composeTestRule.onNodeWithText("getRoutedDevice() → USB Audio Device").assertIsDisplayed()
    }

    @Test
    fun `R_943 no routed device label yet renders no detail line at all, never a placeholder`() {
        composeTestRule.setContent {
            OrtTheme {
                ListenScreen(
                    state = listenStateFor(
                        inputLabel = "USB Audio Device",
                        check = RouteCheckState.InProgress(setOf(RouteCheckStage.NATIVE_RATE), 48_000, 0L),
                    ),
                    onBack = {},
                    actions = ListenActions(
                        onContinue = {},
                        onTryAgain = {},
                        onChooseAnotherInput = {},
                        onSelect = {},
                        onRefresh = {},
                        onVerify = {},
                    ),
                )
            }
        }

        composeTestRule.onNodeWithText("getRoutedDevice", substring = true).assertDoesNotExist()
    }

    @Test
    fun `R_943 the elapsed counter renders beside Listening for signal while still listening`() {
        composeTestRule.setContent {
            OrtTheme {
                ListenScreen(
                    state = listenStateFor(
                        inputLabel = "USB Audio Device",
                        check = RouteCheckState.InProgress(
                            setOf(RouteCheckStage.NATIVE_RATE, RouteCheckStage.ROUTE_MATCH),
                            48_000,
                            elapsedListeningMillis = 11_000L,
                        ),
                    ),
                    onBack = {},
                    actions = ListenActions(
                        onContinue = {},
                        onTryAgain = {},
                        onChooseAnotherInput = {},
                        onSelect = {},
                        onRefresh = {},
                        onVerify = {},
                    ),
                )
            }
        }

        composeTestRule.onNodeWithText("0:11").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `R_943 the elapsed counter disappears once the signal check has passed`() {
        composeTestRule.setContent {
            OrtTheme {
                ListenScreen(
                    state = listenStateFor(
                        inputLabel = "USB Audio Device",
                        check = RouteCheckState.InProgress(
                            setOf(RouteCheckStage.NATIVE_RATE, RouteCheckStage.ROUTE_MATCH, RouteCheckStage.SIGNAL),
                            48_000,
                            elapsedListeningMillis = 11_000L,
                        ),
                    ),
                    onBack = {},
                    actions = ListenActions(
                        onContinue = {},
                        onTryAgain = {},
                        onChooseAnotherInput = {},
                        onSelect = {},
                        onRefresh = {},
                        onVerify = {},
                    ),
                )
            }
        }

        composeTestRule.onNodeWithText("0:11").assertDoesNotExist()
    }

    @Test
    fun `R_943 the Input waveform card renders with the real level samples`() {
        composeTestRule.setContent {
            OrtTheme {
                ListenScreen(
                    state = listenStateFor(
                        inputLabel = "USB Audio Device",
                        check = RouteCheckState.InProgress(
                            passed = setOf(RouteCheckStage.NATIVE_RATE, RouteCheckStage.ROUTE_MATCH),
                            nativeRateHz = 48_000,
                            elapsedListeningMillis = 4_000L,
                            levelBars = listOf(0.1f, 0.5f, 0.9f),
                            noiseFloorDbfs = -58.0,
                        ),
                    ),
                    onBack = {},
                    actions = ListenActions(
                        onContinue = {},
                        onTryAgain = {},
                        onChooseAnotherInput = {},
                        onSelect = {},
                        onRefresh = {},
                        onVerify = {},
                    ),
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
                ListenScreen(
                    state = listenStateFor(
                        inputLabel = "USB Audio Device",
                        check = RouteCheckState.InProgress(setOf(RouteCheckStage.NATIVE_RATE), 48_000, 0L),
                    ),
                    onBack = {},
                    actions = ListenActions(
                        onContinue = {},
                        onTryAgain = {},
                        onChooseAnotherInput = {},
                        onSelect = {},
                        onRefresh = {},
                        onVerify = {},
                    ),
                )
            }
        }

        composeTestRule.onNodeWithText("noise floor —").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `R_943 signal heard caption renders only once the signal stage has genuinely passed`() {
        composeTestRule.setContent {
            OrtTheme {
                ListenScreen(
                    state = listenStateFor(
                        inputLabel = "USB Audio Device",
                        check = RouteCheckState.InProgress(
                            setOf(RouteCheckStage.NATIVE_RATE, RouteCheckStage.ROUTE_MATCH),
                            48_000,
                            4_000L,
                        ),
                    ),
                    onBack = {},
                    actions = ListenActions(
                        onContinue = {},
                        onTryAgain = {},
                        onChooseAnotherInput = {},
                        onSelect = {},
                        onRefresh = {},
                        onVerify = {},
                    ),
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
                ListenScreen(
                    state = listenStateFor(
                        inputLabel = "USB Audio Device",
                        check = RouteCheckState.InProgress(
                            setOf(RouteCheckStage.NATIVE_RATE, RouteCheckStage.ROUTE_MATCH),
                            48_000,
                            4_000L,
                        ),
                    ),
                    onBack = {},
                    actions = ListenActions(
                        onContinue = {},
                        onTryAgain = {},
                        onChooseAnotherInput = {},
                        onSelect = {},
                        onRefresh = {},
                        onVerify = {},
                    ),
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
                ListenScreen(
                    state = listenStateFor(
                        inputLabel = "USB Audio Device",
                        check = RouteCheckState.Passed(48_000, null),
                    ),
                    onBack = {},
                    actions = ListenActions(
                        onContinue = {},
                        onTryAgain = {},
                        onChooseAnotherInput = {},
                        onSelect = {},
                        onRefresh = {},
                        onVerify = {},
                    ),
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
