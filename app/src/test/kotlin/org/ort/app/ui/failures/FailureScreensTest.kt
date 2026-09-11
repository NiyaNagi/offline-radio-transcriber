package org.ort.app.ui.failures

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * WP11b, register R-100: each `Fail*` composable is a pure function of its own view-state — no
 * `Context`, no live holder read — so every one of them renders from a plain, hand-built state
 * here. V7 (font scale 2.0, "must not clip the action"): one takeover ([FailRouteScreen]) and one
 * banner ([FailDisconnectBanner]) are proven not to clip their primary action at maximum system
 * font scale, matching `ReaderAccessibilityTest`'s own pattern.
 */
@RunWith(RobolectricTestRunner::class)
// WPC3's own F9/F23 retry-ladder cases pushed this over detekt's LargeClass threshold — kept as
// one class deliberately (this file's own doc comment: every `Fail*` composable's rendering
// lives here in one place), the same call `ReaderActivityDestinationSmokeTest.kt` already makes
// for the identical reason.
@Suppress("LargeClass")
class FailureScreensTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val maxFontScale = 2f

    @Test
    fun `F1_route the takeover carries its copy, rows and both actions, testTags reachable`() {
        var chosenInput = false
        var endedSession = false
        composeTestRule.setContent {
            OrtTheme {
                FailRouteScreen(
                    state = RouteViewState(
                        expectedLabel = "USB Audio Device",
                        actualLabel = "Built-in microphone",
                        sinceLabel = "02:41:12",
                        elapsedLabel = "3:09:40",
                        oversKeptCount = 188,
                    ),
                    onChooseInputAgain = { chosenInput = true },
                    onEndSession = { endedSession = true },
                )
            }
        }

        composeTestRule.onNodeWithTag("failure-route-screen").assertIsDisplayed()
        composeTestRule.onNodeWithText("Halted").assertIsDisplayed()
        composeTestRule.onNodeWithText("Audio switched to the built-in microphone").assertIsDisplayed()
        composeTestRule.onNodeWithTag("failure-route-choose-input").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("failure-route-end-session").assertIsDisplayed().performClick()
        assert(chosenInput)
        assert(endedSession)
    }

    @Test
    fun `V7 the route takeover does not clip its primary action at maximum font scale`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = maxFontScale)) {
                OrtTheme {
                    FailRouteScreen(
                        state = RouteViewState(
                            expectedLabel = "USB Audio Device",
                            actualLabel = "Built-in microphone",
                            sinceLabel = "02:41:12",
                            elapsedLabel = "3:09:40",
                            oversKeptCount = 188,
                        ),
                        onChooseInputAgain = {},
                        onEndSession = {},
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("failure-route-choose-input").assertIsDisplayed().assertHeightIsAtLeast(44.dp)
        composeTestRule.onNodeWithTag("failure-route-end-session").assertIsDisplayed().assertHeightIsAtLeast(44.dp)
    }

    @Test
    fun `R_126 the subtitle follows Fail-Route's HH_MM_SS after H_MM_SS N overs kept pattern`() {
        composeTestRule.setContent {
            OrtTheme {
                FailRouteScreen(
                    state = RouteViewState(
                        expectedLabel = "USB Audio Device",
                        actualLabel = "Built-in microphone",
                        sinceLabel = "02:41:12",
                        elapsedLabel = "3:09:40",
                        oversKeptCount = 188,
                    ),
                    onChooseInputAgain = {},
                    onEndSession = {},
                )
            }
        }

        composeTestRule.onNodeWithText("02:41:12 · after 3:09:40 · 188 overs kept").assertIsDisplayed()
    }

    @Test
    fun `R_126 with no session to measure against, the subtitle never invents an elapsed time`() {
        composeTestRule.setContent {
            OrtTheme {
                FailRouteScreen(
                    state = RouteViewState(
                        expectedLabel = "USB Audio Device",
                        actualLabel = "Built-in microphone",
                        sinceLabel = "02:41:12",
                        elapsedLabel = "0:00:00",
                        oversKeptCount = 0,
                        sessionElapsedKnown = false,
                    ),
                    onChooseInputAgain = {},
                    onEndSession = {},
                )
            }
        }

        composeTestRule.onNodeWithText("02:41:12 · capture will not continue on this route").assertIsDisplayed()
    }

    @Test
    fun `R_126 Why this halts opens a sheet citing the halt rule, and closes`() {
        composeTestRule.setContent {
            OrtTheme {
                FailRouteScreen(
                    state = RouteViewState(
                        expectedLabel = "USB Audio Device",
                        actualLabel = "Built-in microphone",
                        sinceLabel = "02:41:12",
                        elapsedLabel = "3:09:40",
                        oversKeptCount = 188,
                    ),
                    onChooseInputAgain = {},
                    onEndSession = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("failure-route-why-this-halts-sheet").assertDoesNotExist()
        composeTestRule.onNodeWithText("Why this halts").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("failure-route-why-this-halts-sheet").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "A route that is not the selected device halts capture (FR-CAP-3) — recording the room " +
                "instead of the radio is the highest-consequence silent failure in the system. The built-in " +
                "mic is a legitimate choice on its own (FR-CAP-3a), but never a silent substitute for a USB " +
                "device that dropped off the bus. Every retained recording carries the exact input rate and " +
                "resampler identity that produced it (FR-CAP-2a); a route swapped mid-session would make " +
                "that identity a lie for whatever came after. Route changes are re-verified against this " +
                "same rule for the rest of the session (FR-RUN-13).",
        ).assertIsDisplayed()
        composeTestRule.onNodeWithTag("failure-route-why-this-halts-close").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("failure-route-why-this-halts-sheet").assertDoesNotExist()
    }

    @Test
    fun `R_127 every takeover and banner in this package compiles the status-bar inset without crashing`() {
        // Robolectric's WindowInsets.statusBars resolves to zero in this harness (no real system
        // bar to measure), so the pixel offset V1 caught by screenshot cannot be asserted here —
        // this proves the modifier chain is valid and every screen still renders its content with
        // it applied, the same way `SetupScaffold.kt`'s own (already-correct) screens do.
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    FailRouteScreen(
                        state = RouteViewState("USB Audio Device", "Built-in microphone", "02:41:12", "0:00:01", 1),
                        onChooseInputAgain = {},
                        onEndSession = {},
                    )
                    FailStorageHaltScreen(state = StorageHaltViewState("50 MB free", "100 MB"), onFreeUpSpace = {})
                    FailUsbScreen(
                        state = UsbViewState("03:44", "03:47", 4),
                        onGrantPermission = {},
                        onContinueWithoutRadio = {},
                    )
                }
            }
        }

        composeTestRule.onAllNodesWithTag("failure-route-screen").assertCountEquals(1)
        composeTestRule.onAllNodesWithTag("failure-storage-halt-screen").assertCountEquals(1)
        composeTestRule.onAllNodesWithTag("failure-usb-screen").assertCountEquals(1)
    }

    @Test
    fun `F2_disconnect the banner carries its copy and both actions`() {
        var retried = false
        var chose = false
        composeTestRule.setContent {
            OrtTheme {
                FailDisconnectBanner(
                    state = DisconnectViewState("USB Audio Device", "02:54:08"),
                    onRetry = { retried = true },
                    onChooseAnotherInput = { chose = true },
                )
            }
        }

        composeTestRule.onNodeWithTag("failure-disconnect-banner").assertIsDisplayed()
        composeTestRule.onNodeWithText("Input disconnected — retrying").assertIsDisplayed()
        composeTestRule.onNodeWithText("Retry now").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithText("Choose another input").assertIsDisplayed().performClick()
        assert(retried)
        assert(chose)
    }

    @Test
    fun `V7 the disconnect banner does not clip its actions at maximum font scale`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = maxFontScale)) {
                OrtTheme {
                    FailDisconnectBanner(
                        state = DisconnectViewState("USB Audio Device", "02:54:08"),
                        onRetry = {},
                        onChooseAnotherInput = {},
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Retry now").assertIsDisplayed()
        composeTestRule.onNodeWithText("Choose another input").assertIsDisplayed()
    }

    @Test
    fun `F3_level a quiet reading never surfaces the enum name`() {
        composeTestRule.setContent {
            OrtTheme {
                FailLevelBanner(state = LevelViewState(LevelProblem.QUIET, -34f, "03:31"))
            }
        }
        composeTestRule.onNodeWithText("Too quiet since 03:31 — speech peaks at -34 dBFS").assertIsDisplayed()
    }

    @Test
    fun `F5_killed carries a dismiss action`() {
        var dismissed = false
        composeTestRule.setContent {
            OrtTheme {
                FailKilledBanner(
                    state = KilledViewState("03:12:40", "3 h 36 m"),
                    onOpenBatterySettings = {},
                    onDismiss = { dismissed = true },
                )
            }
        }
        composeTestRule.onNodeWithText("OK").assertIsDisplayed().performClick()
        assert(dismissed)
    }

    @Test
    fun `F6_storage the halt takeover carries its one recovery action`() {
        composeTestRule.setContent {
            OrtTheme {
                FailStorageHaltScreen(
                    state = StorageHaltViewState("50 MB free", "100 MB"),
                    onFreeUpSpace = {},
                )
            }
        }
        composeTestRule.onNodeWithTag("failure-storage-halt-screen").assertIsDisplayed()
        composeTestRule.onNodeWithText("Halted").assertIsDisplayed()
        composeTestRule.onNodeWithTag("failure-storage-halt-free-space").assertIsDisplayed()
    }

    @Test
    fun `F9_rig both recovery actions are wired`() {
        var reconnected = false
        var setByHand = false
        composeTestRule.setContent {
            OrtTheme {
                FailRigBanner(
                    state = RigViewState("TH-D75A", "04:02"),
                    onReconnect = { reconnected = true },
                    onSetFrequencyByHand = { setByHand = true },
                )
            }
        }
        composeTestRule.onNodeWithText("Reconnect").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithText("Set the frequency by hand").assertIsDisplayed().performClick()
        assert(reconnected)
        assert(setByHand)
    }

    // WPC3 (FR-RIG-15/FR-CAP-5): F9's and F23's real retry-ladder sentence, the identical
    // "Retry N of M, next in S s." phrasing on both banners now that RigStatus.State.Stale and
    // InputStatus.State.Lost both carry the same real fields.

    @Test
    @Requirement("FR-RIG-15")
    fun `FR_RIG_15 F9 renders the real retry ladder sentence when the caller has one`() {
        composeTestRule.setContent {
            OrtTheme {
                FailRigBanner(
                    state = RigViewState(
                        deviceLabel = "TH-D75A",
                        sinceLabel = "04:02",
                        retryAttempt = 2,
                        retryTotal = 5,
                        nextRetrySeconds = 12,
                    ),
                    onReconnect = {},
                    onSetFrequencyByHand = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Retry 2 of 5, next in 12 s.", substring = true).assertIsDisplayed()
    }

    @Test
    @Requirement("FR-RIG-15")
    fun `FR_RIG_15 F9 renders no retry sentence for a caller that predates WPC3, never fabricated`() {
        composeTestRule.setContent {
            OrtTheme {
                FailRigBanner(
                    state = RigViewState(deviceLabel = "TH-D75A", sinceLabel = "04:02"),
                    onReconnect = {},
                    onSetFrequencyByHand = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Retry", substring = true).assertDoesNotExist()
    }

    @Test
    @Requirement("FR-CAP-5")
    fun `FR_CAP_5 F23 renders the real retry ladder sentence when the caller has one`() {
        composeTestRule.setContent {
            OrtTheme {
                FailBluetoothAudioDroppedBanner(
                    state = BluetoothAudioDroppedViewState(
                        deviceLabel = "Handheld BT",
                        droppedAtLabel = "04:33",
                        retryAttempt = 3,
                        retryTotal = 8,
                        nextRetrySeconds = 20,
                        rigLinkStillUp = true,
                    ),
                    onRetryNow = {},
                    onSwitchToWiredInput = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Retry 3 of 8, next in 20 s.", substring = true).assertIsDisplayed()
    }

    @Test
    @Requirement("FR-CAP-5")
    fun `FR_CAP_5 F23 reads Retrying automatically for a caller with no real ladder position`() {
        composeTestRule.setContent {
            OrtTheme {
                FailBluetoothAudioDroppedBanner(
                    state = BluetoothAudioDroppedViewState(
                        deviceLabel = "Handheld BT",
                        droppedAtLabel = "04:33",
                        rigLinkStillUp = true,
                    ),
                    onRetryNow = {},
                    onSwitchToWiredInput = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Retrying automatically.", substring = true).assertIsDisplayed()
    }

    @Test
    fun `R_254 F8 the Rate row and chart read Not measured with fewer than two backlog samples`() {
        composeTestRule.setContent {
            OrtTheme {
                FailBacklogBanner(state = BacklogViewState(waitingCount = 41))
            }
        }
        composeTestRule.onNodeWithText("Not measured").assertIsDisplayed()
        composeTestRule.onNodeWithTag("failure-backlog-chart-not-measured").assertIsDisplayed()
    }

    @Test
    fun `R_254 F8 the Rate row shows the Band Pass B split and sub-line, and the chart's real axis times`() {
        composeTestRule.setContent {
            OrtTheme {
                FailBacklogBanner(
                    state = BacklogViewState(
                        waitingCount = 112,
                        queueHistory = listOf(0.3f, 0.6f, 1f),
                        queueHistoryOldestLabel = "18:50",
                        queueHistoryNewestLabel = "19:20",
                        growthRateLabel = "Band 2.4 overs/min · Pass B 1.6/min",
                        rateSubLabel = "tier 2, RTF 0.62 while the net runs",
                    ),
                )
            }
        }
        composeTestRule.onNodeWithText("Band 2.4 overs/min · Pass B 1.6/min").assertIsDisplayed()
        composeTestRule.onNodeWithText("tier 2, RTF 0.62 while the net runs").assertIsDisplayed()
        composeTestRule.onNodeWithText("18:50").assertIsDisplayed()
        composeTestRule.onNodeWithText("19:20").assertIsDisplayed()
        composeTestRule.onNodeWithTag("failure-backlog-chart").assertIsDisplayed()
    }

    @Test
    fun `F15_call the acknowledgement banner dismisses`() {
        var dismissed = false
        composeTestRule.setContent {
            OrtTheme {
                FailCallBanner(state = CallViewState("38 s"), onDismiss = { dismissed = true })
            }
        }
        composeTestRule.onNodeWithText("Resumed after a 38 s call. The gap is in the log.").assertIsDisplayed()
        composeTestRule.onNodeWithTag("failure-call-dismiss").assertIsDisplayed().performClick()
        assert(dismissed)
    }

    @Test
    fun `F16_usb the takeover dialog carries both recovery actions`() {
        composeTestRule.setContent {
            OrtTheme {
                FailUsbScreen(
                    state = UsbViewState("03:44", "03:47", 4),
                    onGrantPermission = {},
                    onContinueWithoutRadio = {},
                )
            }
        }
        composeTestRule.onNodeWithTag("failure-usb-screen").assertIsDisplayed()
        composeTestRule.onNodeWithText("The rig needs USB permission again").assertIsDisplayed()
        composeTestRule.onNodeWithTag("failure-usb-grant-permission").assertIsDisplayed()
        composeTestRule.onNodeWithTag("failure-usb-continue-without-radio").assertIsDisplayed()
    }

    @Test
    fun `F19_reconcile lists both mismatch directions and both actions`() {
        composeTestRule.setContent {
            OrtTheme {
                FailReconcileScreen(
                    state = ReconcileViewState(
                        recordsNoFile = listOf(ReconcileRecord("W7NPC", "Fri 4 Sep 01:22 · 28.4 s", null)),
                        filesNoRecord = listOf(ReconcileFile("a/x.flac", "6.2 s", null)),
                        causeText = "The OS stopped the app with writes in flight.",
                    ),
                    onImport = {},
                    onLeaveAsIs = {},
                )
            }
        }
        composeTestRule.onNodeWithTag("failure-reconcile-screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("failure-reconcile-import").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("failure-reconcile-leave").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `F20_migration shows the rebuild action`() {
        composeTestRule.setContent {
            OrtTheme {
                FailMigrationScreen(
                    state = MigrationViewState(
                        versionLabel = "Updated to 1.1.0",
                        headline = "The records did not fully carry over",
                        steps = listOf(MigrationStep("Audio untouched", "38.2 GB", ok = true)),
                    ),
                    onRebuildNow = {},
                    onSaveDiagnosticBundle = {},
                )
            }
        }
        composeTestRule.onNodeWithTag("failure-migration-rebuild").assertIsDisplayed()
    }

    @Test
    fun `F21_asset-swap shows the options and the Done action`() {
        composeTestRule.setContent {
            OrtTheme {
                FailAssetSwapScreen(
                    state = AssetSwapViewState(
                        activeLabel = "2026.08 active",
                        stagedLabel = "2026.09 staged",
                        options = listOf(
                            AssetSwapOption("Wait", "the default"),
                            AssetSwapOption("Swap now", "starts a new session"),
                        ),
                        selectedOption = 0,
                    ),
                    onSelectOption = {},
                    onDone = {},
                )
            }
        }
        // Register R-448: the new "‹ Models and lexicon" header pushes the options down a little —
        // the fixed Done bar (R-292/R-151) still needs no scroll; the option row does now.
        composeTestRule.onNodeWithTag("failure-asset-swap-option-0").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("failure-asset-swap-done").assertIsDisplayed()
    }

    @Test
    fun `F22_calibration shows the chart and the install action`() {
        composeTestRule.setContent {
            OrtTheme {
                FailCalibrationScreen(
                    state = CalibrationViewState(
                        sinceLabel = "1 Sep",
                        scoreLabel = "0.90",
                        accuracyLabel = "78%",
                        points = listOf(0.3f to 0.22f),
                        calibrationVersion = "2026.09-a",
                        correctionsCount = 22,
                        correctionsNeeded = 100,
                    ),
                    onInstall = {},
                )
            }
        }
        composeTestRule.onNodeWithTag("failure-calibration-chart").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("failure-calibration-install").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `F14_clock and F17_interrupted render as informational, non-amber cards`() {
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    FailClockCard(state = ClockViewState("PDT → PST", "8 h 30 m", "23:10", "06:40"))
                    FailInterruptedCard(state = InterruptedViewState(3, "03:12 – 06:48"))
                }
            }
        }
        composeTestRule.onNodeWithTag("failure-clock-card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("failure-interrupted-card").assertIsDisplayed()
    }

    @Test
    fun `R_147 F14 becomes a full screen with the facts table and the Around the change log`() {
        composeTestRule.setContent {
            OrtTheme {
                FailClockScreen(
                    state = ClockViewState(
                        offsetChangeLabel = "PDT → PST",
                        ranForLabel = "8 h 30 m",
                        startedLabel = "23:10",
                        endedLabel = "06:40",
                        nightLabel = "Overnight, Sat 31 Oct",
                        windowLabel = "23:10 – 06:40 · 8 h 30 m · the clock went back at 02:00",
                        logRows = listOf(ClockLogRow("01:58:40", "before", "W7NPC", "copy on the repeater")),
                    ),
                    onContinue = {},
                )
            }
        }
        // Register R-448: the new "‹ Earlier nights" header pushes the rest of the screen down a
        // little, so everything after it now needs a scroll to reach — the same "content can
        // always scroll clear" contract R-151/R-123/R-292 already establish elsewhere.
        composeTestRule.onNodeWithTag("failure-clock-screen").assertIsDisplayed()
        composeTestRule.onNodeWithText("Overnight, Sat 31 Oct").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Around the change", ignoreCase = true).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("W7NPC").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `R_147 F17 becomes a full screen with the facts and the 3 overs list`() {
        composeTestRule.setContent {
            OrtTheme {
                FailInterruptedScreen(
                    state = InterruptedViewState(
                        overCount = 3,
                        gapLabel = "03:12 – 06:48",
                        backlogLabel = "3 overs waiting",
                        overs = listOf(InterruptedOverRow("03:12:31", "audio only", "no transcript yet")),
                    ),
                    onContinue = {},
                )
            }
        }
        composeTestRule.onNodeWithTag("failure-interrupted-screen").assertIsDisplayed()
        composeTestRule.onNodeWithText("The 1 overs", ignoreCase = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("03:12:31").assertIsDisplayed()
    }

    @Test
    fun `R_148 F19 Play is honestly disabled on an orphan file with no reachable player`() {
        composeTestRule.setContent {
            OrtTheme {
                FailReconcileScreen(
                    state = ReconcileViewState(
                        recordsNoFile = emptyList(),
                        filesNoRecord = listOf(ReconcileFile("a/x.flac", "6.2 s", null, playable = false)),
                        causeText = "cause",
                    ),
                    onImport = {},
                    onLeaveAsIs = {},
                    onPlay = { },
                )
            }
        }
        composeTestRule.onNodeWithTag("failure-reconcile-play-0").performScrollTo()
        composeTestRule.onNodeWithText("Play").assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    fun `R_148 F21 every option row carries its sub-line, and the lexicon rows carry marker dots`() {
        composeTestRule.setContent {
            OrtTheme {
                FailAssetSwapScreen(
                    state = AssetSwapViewState(
                        activeLabel = "2026.08 · active · this session",
                        stagedLabel = "2026.09 · staged · next session",
                        options = listOf(
                            AssetSwapOption("Wait for the session to end", "the default · nothing else to do"),
                            AssetSwapOption("Stop capture, swap, start a new session", "tonight's log ends here"),
                        ),
                        selectedOption = 0,
                    ),
                    onSelectOption = {},
                    onDone = {},
                )
            }
        }
        // Register R-292: the scaffold's content slot is now genuinely height-constrained (never
        // renders behind the fixed bottom bar at any scroll offset), so content past what the
        // reduced viewport shows at rest needs a real scroll to reach — the same "content can
        // always scroll clear" contract R-151/R-123 already established elsewhere in this file.
        composeTestRule.onNodeWithText("2026.08 · active · this session").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("2026.09 · staged · next session").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("the default · nothing else to do").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("tonight's log ends here").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `R_148 F22 carries the axis label, the over-confident caption and the closing paragraph`() {
        composeTestRule.setContent {
            OrtTheme {
                FailCalibrationScreen(
                    state = CalibrationViewState(
                        sinceLabel = "1 Sep",
                        scoreLabel = "0.90",
                        accuracyLabel = "78%",
                        points = listOf(0.3f to 0.22f),
                        calibrationVersion = "2026.09-a",
                        correctionsCount = 22,
                        correctionsNeeded = 100,
                    ),
                    onInstall = {},
                )
            }
        }
        composeTestRule.onNodeWithText("score →").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "Dots below the diagonal are over-confident. The high-score end is where it drifted.",
        ).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("failure-calibration-closing").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `R_151 F20 migration scrolls its content above the action bar at maximum font scale`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = maxFontScale)) {
                OrtTheme {
                    FailMigrationScreen(
                        state = MigrationViewState(
                            versionLabel = "Updated to 1.1.0",
                            headline = "The records did not fully carry over",
                            steps = listOf(
                                MigrationStep("Audio untouched", "38.2 GB", ok = true),
                                MigrationStep(
                                    "Activity patterns need rebuilding",
                                    "the hour-bucket table changed shape · 4,318 overs marked · runs in the background",
                                    ok = false,
                                ),
                            ),
                        ),
                        onRebuildNow = {},
                        onSaveDiagnosticBundle = {},
                    )
                }
            }
        }
        // The rebuild button lives in the fixed action bar, not the scrolled content — it needs no
        // scroll to be on screen at all; that is exactly what R-151 asks for (never behind the bar).
        composeTestRule.onNodeWithTag("failure-migration-rebuild").assertIsDisplayed()
        composeTestRule.onNodeWithText("runs in the background", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `R_151 F21 asset-swap scrolls its content above the Done bar at maximum font scale`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = maxFontScale)) {
                OrtTheme {
                    FailAssetSwapScreen(
                        state = AssetSwapViewState(
                            activeLabel = "2026.08 active",
                            stagedLabel = "2026.09 staged",
                            options = listOf(
                                AssetSwapOption("Wait", "the default"),
                                AssetSwapOption("Swap now", "starts a new session"),
                            ),
                            selectedOption = 0,
                        ),
                        onSelectOption = {},
                        onDone = {},
                    )
                }
            }
        }
        // Same as the migration case above: the Done button is in the fixed action bar.
        composeTestRule.onNodeWithTag("failure-asset-swap-done").assertIsDisplayed()
        composeTestRule.onNodeWithTag("failure-asset-swap-option-1").performScrollTo().assertIsDisplayed()
    }

    private val storageTimeline = listOf(
        StorageTimelineStage("12:28:15 · warned at 3 nights left", "notification and status surface", reached = true),
        StorageTimelineStage("Not reached", "500 MB hard floor", reached = false),
    )

    @Test
    fun `R_300 F6 How this unfolded stays expanded by default at normal font scale, matching the board`() {
        composeTestRule.setContent {
            OrtTheme {
                FailStorageWarningBanner(
                    state = StorageWarningViewState(
                        nightsLeftLabel = "2.4",
                        freeLabel = "6.0 GB free",
                        timeline = storageTimeline,
                    ),
                    onFreeUpSpace = {},
                    onOpenRetentionSettings = {},
                )
            }
        }
        composeTestRule.onNodeWithTag("failure-storage-timeline").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("failure-storage-timeline-toggle").assertCountEquals(0)
    }

    @Test
    fun `R_300 F6 How this unfolded collapses behind a Details disclosure at large font scale`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = maxFontScale)) {
                OrtTheme {
                    FailStorageWarningBanner(
                        state = StorageWarningViewState(
                            nightsLeftLabel = "2.4",
                            freeLabel = "6.0 GB free",
                            timeline = storageTimeline,
                        ),
                        onFreeUpSpace = {},
                        onOpenRetentionSettings = {},
                    )
                }
            }
        }
        composeTestRule.onAllNodesWithTag("failure-storage-timeline").assertCountEquals(0)
        composeTestRule.onNodeWithTag("failure-storage-timeline-toggle").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("failure-storage-timeline").assertIsDisplayed()
    }
}
