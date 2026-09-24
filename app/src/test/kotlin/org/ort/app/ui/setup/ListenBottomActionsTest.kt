package org.ort.app.ui.setup

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
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
import org.robolectric.annotation.Config

/**
 * **[ListenScreen]'s pinned action block** — what the primary says, when it refuses and what it
 * offers instead, split out of `VerifyScreenTest` when P39 merged three screens into one and its
 * tests with them.
 *
 * The split is by subject, not by line count: these are about the one control that decides whether
 * the operator proceeds, and the rule R-1170 settled for it (never disabled for validation; lit,
 * refusing on tap, and saying what is missing). Constitution IV is enforced *here*, in the tap —
 * `VERIFY_NOT_PASSED_YET` — rather than by greying a control out of the focus order, which is exactly
 * why these tests deserve to be read together.
 */
@RunWith(RobolectricTestRunner::class)
// P39: the merged Listen screen is genuinely taller than Robolectric's 320x470dp default, so these
// assertions are made at the tour's own geometry rather than at a viewport no real device has.
@Config(qualifiers = "w390dp-h844dp-420dpi")
class ListenBottomActionsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** See `VerifyScreenTest`'s identical helper: `inputVerified` is derived from the check because in
     * production a passed check and an unverified store cannot coexist. */
    private fun listenStateFor(inputLabel: String, check: RouteCheckState?) = ListenViewState(
        routes = emptyList(),
        selectedId = "usb-1",
        inputLabel = inputLabel,
        check = check,
        checkRunning = true,
        inputVerified = check is RouteCheckState.Passed,
    )

    // --- R-121 (validator finding, halt): RouteCheckState.TimedOut ----------------------------

    @Test
    fun `R_121 a timeout keeps the two checks that already passed ticked and fails the signal check honestly`() {
        composeTestRule.setContent {
            OrtTheme {
                ListenScreen(
                    state = listenStateFor(inputLabel = "USB Audio Device", check = RouteCheckState.TimedOut),
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

        // R-1018 (register, device pass 2): the pinned bar's own real design-spacing fix (12dp ->
        // 24dp above the safe area, SetupScaffold.kt) leaves 12dp less room for this unconstrained
        // test root's scrollable content than before -- these two checks (above the paragraph below
        // in the board's own order) are asserted first, while nothing has scrolled past them yet;
        // scrolling to the paragraph afterward can otherwise carry them off the top of the viewport,
        // the same "grew below the fold" shape R-1005c's own test file comment already documents.
        composeTestRule.onNodeWithTag("setup-verify-check-native-rate").assertIsDisplayed()
        composeTestRule.onNodeWithTag("setup-verify-check-route-match").assertIsDisplayed()
        composeTestRule.onNodeWithText("No signal heard in 30 s on USB Audio Device")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `R_121 a timeout never leaves Continue as the only way forward, and the back chevron always exists`() {
        composeTestRule.setContent {
            OrtTheme {
                ListenScreen(
                    state = listenStateFor(inputLabel = "USB Audio Device", check = RouteCheckState.TimedOut),
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

        composeTestRule.onNodeWithTag("setup-listen-continue").assertDoesNotExist()
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
                ListenScreen(
                    state = listenStateFor(inputLabel = "USB Audio Device", check = RouteCheckState.TimedOut),
                    onBack = {},
                    actions = ListenActions(
                        onContinue = {},
                        onTryAgain = { tryAgain = true },
                        onChooseAnotherInput = { chooseAnother = true },
                        onSelect = {},
                        onRefresh = {},
                        onVerify = {},
                    ),
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
                ListenScreen(
                    state = listenStateFor(inputLabel = "USB Audio Device", check = RouteCheckState.TimedOut),
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
        composeTestRule.onAllNodesWithText("Choose another input").assertCountEquals(1)
    }

    // --- R-284 (validator pass 3): "Choose a different input" during the listening check --------

    @Test
    fun `R_284 the listening check offers Choose a different input, not just the disabled Continue`() {
        var chosenDifferent = false
        composeTestRule.setContent {
            OrtTheme {
                ListenScreen(
                    state = listenStateFor(
                        inputLabel = "USB Audio Device",
                        check = RouteCheckState.InProgress(
                            setOf(RouteCheckStage.NATIVE_RATE, RouteCheckStage.ROUTE_MATCH),
                            48_000,
                            11_000L,
                        ),
                    ),
                    onBack = {},
                    actions = ListenActions(
                        onContinue = {},
                        onTryAgain = {},
                        onChooseAnotherInput = { chosenDifferent = true },
                        onSelect = {},
                        onRefresh = {},
                        onVerify = {},
                    ),
                )
            }
        }

        // R-1170: lit, not disabled — the escape link beside it is what R-284 is about.
        composeTestRule.onNodeWithTag("setup-listen-continue").assertIsEnabled()
        composeTestRule.onNodeWithTag("setup-verify-choose-different").assertIsDisplayed().performClick()
        assert(chosenDifferent)
    }

    @Test
    fun `R_284 the link is gone once every check has already passed`() {
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

        composeTestRule.onNodeWithTag("setup-verify-choose-different").assertDoesNotExist()
    }

    // --- R-1170: the primary stays lit, and the verification gate still genuinely blocks ----------

    /**
     * R-1170 **and constitution IV**, together — this is the regression the R-1170 change most
     * risks and the reason this test exists at all. Keeping `Continue` lit must not become a way to
     * walk past a route that has not verified: *"a route that is not the selected device halts
     * capture"* is absolute, and `RouteCheckState.Mismatch` is the one deliberate hard halt in the
     * whole flow. Lit, tapped, and `onContinue` is still never called.
     */
    @Test
    fun `R_1170 an unverified route still cannot proceed, however lit the button is`() {
        var continued = false
        composeTestRule.setContent {
            OrtTheme {
                ListenScreen(
                    state = listenStateFor(
                        inputLabel = "USB Audio Device",
                        check = RouteCheckState.InProgress(setOf(RouteCheckStage.NATIVE_RATE), 48_000, 0L),
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

        composeTestRule.onNodeWithTag("setup-listen-continue").assertIsEnabled().performClick()
        assert(!continued)
        composeTestRule.onNodeWithTag("setup-listen-validation").assertIsDisplayed()
    }

    /** R-1170: the same, for a state that carries no check at all — nothing has even been tried,
     * which is no more a licence to proceed than a half-finished check. */
    @Test
    fun `R_1170 a route with no check at all still cannot proceed`() {
        var continued = false
        composeTestRule.setContent {
            OrtTheme {
                ListenScreen(
                    state = listenStateFor(inputLabel = "USB Audio Device", check = null),
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

        composeTestRule.onNodeWithTag("setup-listen-continue").assertIsEnabled().performClick()
        assert(!continued)
    }

    /** R-1170, the accessibility half: the refusal is announced, not merely drawn. */
    @Test
    fun `R_1170 the verify notice is a live region a screen reader announces`() {
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

        composeTestRule.onNodeWithTag("setup-listen-continue").performClick()
        composeTestRule.onNodeWithTag("setup-listen-validation")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive))
    }

    /** R-1170: no standing scold on arrival — the notice answers a tap. */
    @Test
    fun `R_1170 no verify notice is shown before the primary has been tapped`() {
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

        composeTestRule.onNodeWithTag("setup-listen-validation").assertDoesNotExist()
    }
}
