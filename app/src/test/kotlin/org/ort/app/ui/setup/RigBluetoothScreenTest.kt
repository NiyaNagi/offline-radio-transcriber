package org.ort.app.ui.setup

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** E2-E10/E2-E11 (`spec/e2e-capture-modes-plan.md` WPD) — `Setup-Rig-Bluetooth.dc.html` (S10b).
 * `LargeClass` suppressed (R-1013/R-1014's own round pushed it over the threshold): this is
 * already one self-contained cluster — every state S10b's own checklist and banner can reach, one
 * screen — the same reasoning `WpiScenariosTest`'s own suppression documents for an identical
 * shape, not a class that grew by accident. */
@Suppress("LargeClass")
@RunWith(RobolectricTestRunner::class)
class RigBluetoothScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val sppDevice = PairedDevice("TH-D75A", "D8:3A:DD:41:0C:7F", sppCapable = true)
    private val headsetDevice = PairedDevice("Handheld BT", "11:22:33:44:55:66", sppCapable = false)
    private val unknownDevice = PairedDevice("Mystery", "AA:BB:CC:DD:EE:FF", sppCapable = null)

    private fun state(
        devices: List<PairedDevice> = listOf(sppDevice, headsetDevice),
        selectedAddress: String? = null,
        linkState: RigLinkState? = null,
    ) = RigBluetoothViewState(
        rigDisplayName = "TH-D75A",
        devices = devices,
        selectedAddress = selectedAddress,
        linkState = linkState,
    )

    @Test
    fun `E2_E10 lists paired devices, SPP-capable selectable`() {
        var selected: String? = null
        composeTestRule.setContent {
            OrtTheme {
                RigBluetoothScreen(
                    state = state(),
                    onSelectDevice = { selected = it },
                    onPairInSettings = {},
                    onRefresh = {},
                    onContinue = {},
                    onContinueWithoutConnecting = {},
                    onUseUsbInstead = {},
                    onRequestBluetoothPermission = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-rig-bt-device-D8:3A:DD:41:0C:7F").performClick()
        assert(selected == "D8:3A:DD:41:0C:7F")
    }

    @Test
    fun `E2_E10 a headset-only device is listed but tapping it never selects it`() {
        var selected: String? = "unset"
        composeTestRule.setContent {
            OrtTheme {
                RigBluetoothScreen(
                    state = state(),
                    onSelectDevice = { selected = it },
                    onPairInSettings = {},
                    onRefresh = {},
                    onContinue = {},
                    onContinueWithoutConnecting = {},
                    onUseUsbInstead = {},
                    onRequestBluetoothPermission = {},
                )
            }
        }

        // R-1018 (register, device pass 2): the pinned bar's own real design-spacing fix (12dp ->
        // 24dp above the safe area, SetupScaffold.kt) leaves 12dp less room in this unconstrained
        // test root, pushing this second device row below the fold — the same "grew below the
        // fold" shape R-1005c's own test file comment already documents for this file's own bar.
        composeTestRule.onNodeWithText("Handheld BT").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("setup-rig-bt-device-11:22:33:44:55:66").performClick()
        assert(selected == "unset")
    }

    @Test
    fun `E2_E10 an unknown-capability device is shown as unknown and still selectable`() {
        var selected: String? = null
        composeTestRule.setContent {
            OrtTheme {
                RigBluetoothScreen(
                    state = state(devices = listOf(unknownDevice)),
                    onSelectDevice = { selected = it },
                    onPairInSettings = {},
                    onRefresh = {},
                    onContinue = {},
                    onContinueWithoutConnecting = {},
                    onUseUsbInstead = {},
                    onRequestBluetoothPermission = {},
                )
            }
        }

        composeTestRule.onNodeWithText("serial-port support unknown", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("setup-rig-bt-device-AA:BB:CC:DD:EE:FF").performClick()
        assert(selected == "AA:BB:CC:DD:EE:FF")
    }

    @Test
    fun `E2_E10 Pair in system settings and Refresh invoke their own callbacks`() {
        var paired = false
        var refreshed = false
        composeTestRule.setContent {
            OrtTheme {
                RigBluetoothScreen(
                    state = state(),
                    onSelectDevice = {},
                    onPairInSettings = { paired = true },
                    onRefresh = { refreshed = true },
                    onContinue = {},
                    onContinueWithoutConnecting = {},
                    onUseUsbInstead = {},
                    onRequestBluetoothPermission = {},
                )
            }
        }

        // R-1005c: the three-action pinned bar is now tall enough that this row sits below the
        // fold in this test's own (unconstrained-height) root -- performScrollTo() first, the same
        // pattern this file's own banner assertions already use below.
        composeTestRule.onNodeWithTag("setup-rig-bt-pair-in-settings").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("setup-rig-bt-refresh").performScrollTo().performClick()
        assert(paired)
        assert(refreshed)
    }

    @Test
    fun `E2_E10 Continue is disabled until the checklist reaches Verified`() {
        composeTestRule.setContent {
            OrtTheme {
                RigBluetoothScreen(
                    state = state(selectedAddress = sppDevice.address, linkState = RigLinkState.Open),
                    onSelectDevice = {},
                    onPairInSettings = {},
                    onRefresh = {},
                    onContinue = {},
                    onContinueWithoutConnecting = {},
                    onUseUsbInstead = {},
                    onRequestBluetoothPermission = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-rig-bt-continue").assertIsNotEnabled()
    }

    @Test
    fun `E2_E10 Continue enables once Verified is reached and invokes its callback`() {
        var continued = false
        composeTestRule.setContent {
            OrtTheme {
                RigBluetoothScreen(
                    state = state(
                        selectedAddress = sppDevice.address,
                        linkState = RigLinkState.Verified(listOf("FQ", "BY")),
                    ),
                    onSelectDevice = {},
                    onPairInSettings = {},
                    onRefresh = {},
                    onContinue = { continued = true },
                    onContinueWithoutConnecting = {},
                    onUseUsbInstead = {},
                    onRequestBluetoothPermission = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-rig-bt-continue").assertIsEnabled().performClick()
        assert(continued)
    }

    // --- R-1013/R-1014: the two new terminal RigLinkState outcomes ------------------------------

    /** R-1014: partial success — Continue enables the same as a full Verified. */
    @Test
    fun `R_1014 Continue enables on VerifyTimedOut and invokes its own callback`() {
        var continued = false
        composeTestRule.setContent {
            OrtTheme {
                RigBluetoothScreen(
                    state = state(
                        selectedAddress = sppDevice.address,
                        linkState = RigLinkState.VerifyTimedOut(
                            rigId = "kenwood-thd75a",
                            seenCapabilities = listOf("frequency"),
                            missingCapabilities = listOf("squelch", "signal strength"),
                            timeoutMillis = 12_000L,
                        ),
                    ),
                    onSelectDevice = {},
                    onPairInSettings = {},
                    onRefresh = {},
                    onContinue = { continued = true },
                    onContinueWithoutConnecting = {},
                    onUseUsbInstead = {},
                    onRequestBluetoothPermission = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-rig-bt-continue").assertIsEnabled().performClick()
        assert(continued)
    }

    /** R-1013: nothing ever answered — Continue must stay disabled, distinct from R-1014's own
     * partial-success case above. */
    @Test
    fun `R_1013 Continue stays disabled on IdentifyTimedOut`() {
        composeTestRule.setContent {
            OrtTheme {
                RigBluetoothScreen(
                    state = state(
                        selectedAddress = sppDevice.address,
                        linkState = RigLinkState.IdentifyTimedOut("kenwood-thd75a", timeoutMillis = 6_000L),
                    ),
                    onSelectDevice = {},
                    onPairInSettings = {},
                    onRefresh = {},
                    onContinue = {},
                    onContinueWithoutConnecting = {},
                    onUseUsbInstead = {},
                    onRequestBluetoothPermission = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-rig-bt-continue").assertIsNotEnabled()
    }

    /** R-1013: "Continue without connecting" must stay reachable — the one forward path when
     * nothing ever answered. */
    @Test
    fun `R_1013 Continue without connecting stays available on IdentifyTimedOut`() {
        var continuedWithoutConnecting = false
        composeTestRule.setContent {
            OrtTheme {
                RigBluetoothScreen(
                    state = state(
                        selectedAddress = sppDevice.address,
                        linkState = RigLinkState.IdentifyTimedOut("kenwood-thd75a", timeoutMillis = 6_000L),
                    ),
                    onSelectDevice = {},
                    onPairInSettings = {},
                    onRefresh = {},
                    onContinue = {},
                    onContinueWithoutConnecting = { continuedWithoutConnecting = true },
                    onUseUsbInstead = {},
                    onRequestBluetoothPermission = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-rig-bt-continue-without-connecting").performClick()
        assert(continuedWithoutConnecting)
    }

    @Test
    fun `R_1013 IdentifyTimedOut renders a banner, never a blank screen`() {
        composeTestRule.setContent {
            OrtTheme {
                RigBluetoothScreen(
                    state = state(
                        selectedAddress = sppDevice.address,
                        linkState = RigLinkState.IdentifyTimedOut("kenwood-thd75a", timeoutMillis = 6_000L),
                    ),
                    onSelectDevice = {},
                    onPairInSettings = {},
                    onRefresh = {},
                    onContinue = {},
                    onContinueWithoutConnecting = {},
                    onUseUsbInstead = {},
                    onRequestBluetoothPermission = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-rig-bt-lost-banner").performScrollTo().assertIsDisplayed()
    }

    /** R-1014: the checklist's own third row names the counts and the missing capabilities — the
     * screen alone must say what proceeding costs, never just "partially verified". */
    @Test
    fun `R_1014 the verify checklist row names how many of how many were seen and which were missing`() {
        composeTestRule.setContent {
            OrtTheme {
                RigBluetoothScreen(
                    state = state(
                        selectedAddress = sppDevice.address,
                        linkState = RigLinkState.VerifyTimedOut(
                            rigId = "kenwood-thd75a",
                            seenCapabilities = listOf("frequency"),
                            missingCapabilities = listOf("squelch", "signal strength"),
                            timeoutMillis = 12_000L,
                        ),
                    ),
                    onSelectDevice = {},
                    onPairInSettings = {},
                    onRefresh = {},
                    onContinue = {},
                    onContinueWithoutConnecting = {},
                    onUseUsbInstead = {},
                    onRequestBluetoothPermission = {},
                )
            }
        }

        composeTestRule
            .onNodeWithText("1 of 3 seen — missing squelch, signal strength", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    /** Constitution VIII: this row's own content changed shape (a second, longer detail line under
     * the label) — bounds at the tour's own width, native graphics, font scale 2.0, the same
     * discipline R-805/R-942/R-1005c already apply to this screen. A long missing-capability list is
     * exactly the row this project's own history (R-805, R-863, R-874, R-942) has broken before. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `R_1014 the verify checklist detail line stays within the real 390dp screen at font scale 2_0`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrtTheme {
                    Box(modifier = Modifier.width(390.dp)) {
                        RigBluetoothScreen(
                            state = state(
                                selectedAddress = sppDevice.address,
                                linkState = RigLinkState.VerifyTimedOut(
                                    rigId = "kenwood-thd75a",
                                    seenCapabilities = listOf("frequency"),
                                    missingCapabilities = listOf("squelch", "signal strength", "memory channel"),
                                    timeoutMillis = 12_000L,
                                ),
                            ),
                            onSelectDevice = {},
                            onPairInSettings = {},
                            onRefresh = {},
                            onContinue = {},
                            onContinueWithoutConnecting = {},
                            onUseUsbInstead = {},
                            onRequestBluetoothPermission = {},
                        )
                    }
                }
            }
        }

        val bounds = composeTestRule
            .onNodeWithTag("setup-rig-bt-checklist-verify")
            .performScrollTo()
            .getUnclippedBoundsInRoot()
        assertTrue(
            "expected the verify checklist row to stay within the real 390dp screen's own right edge " +
                "at font scale 2.0, got right edge ${bounds.right}",
            bounds.right <= 390.dp,
        )
    }

    @Test
    fun `verifyPartialDetail computes the seen-of-total count and the missing labels, pure`() {
        val detail = verifyPartialDetail(
            RigLinkState.VerifyTimedOut(
                rigId = "kenwood-thd75a",
                seenCapabilities = listOf("frequency"),
                missingCapabilities = listOf("squelch", "signal strength"),
                timeoutMillis = 12_000L,
            ),
        )
        assertTrue(detail.contains("1 of 3"))
        assertTrue(detail.contains("squelch"))
        assertTrue(detail.contains("signal strength"))
    }

    @Test
    fun `formatTimeoutSeconds is locale-independent, never a comma-decimal on a real device`() {
        assertTrue(org.ort.app.ui.setup.formatTimeoutSeconds(6_000L) == "6.0s")
        assertTrue(org.ort.app.ui.setup.formatTimeoutSeconds(12_500L) == "12.5s")
    }

    @Test
    fun `E2_E11 a Lost link renders a banner, never a blank screen`() {
        composeTestRule.setContent {
            OrtTheme {
                RigBluetoothScreen(
                    state = state(
                        selectedAddress = sppDevice.address,
                        linkState = RigLinkState.Lost("connection dropped"),
                    ),
                    onSelectDevice = {},
                    onPairInSettings = {},
                    onRefresh = {},
                    onContinue = {},
                    onContinueWithoutConnecting = {},
                    onUseUsbInstead = {},
                    onRequestBluetoothPermission = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-rig-bt-lost-banner").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("setup-rig-bt-continue").assertIsNotEnabled()
    }

    @Test
    fun `E2_E11 a Failed connect renders a banner`() {
        composeTestRule.setContent {
            OrtTheme {
                RigBluetoothScreen(
                    state = state(
                        selectedAddress = sppDevice.address,
                        linkState = RigLinkState.Failed("connection refused"),
                    ),
                    onSelectDevice = {},
                    onPairInSettings = {},
                    onRefresh = {},
                    onContinue = {},
                    onContinueWithoutConnecting = {},
                    onUseUsbInstead = {},
                    onRequestBluetoothPermission = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-rig-bt-lost-banner").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `E2_E11 NoPermission renders a banner, never a SecurityException surfacing as a crash`() {
        composeTestRule.setContent {
            OrtTheme {
                RigBluetoothScreen(
                    state = state(selectedAddress = sppDevice.address, linkState = RigLinkState.NoPermission),
                    onSelectDevice = {},
                    onPairInSettings = {},
                    onRefresh = {},
                    onContinue = {},
                    onContinueWithoutConnecting = {},
                    onUseUsbInstead = {},
                    onRequestBluetoothPermission = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-rig-bt-lost-banner").performScrollTo().assertIsDisplayed()
    }

    /** R-1005b (device field report): the banner used to say "Grant it from Settings" with no
     * button that did so — this proves a real, wired action exists now. */
    @Test
    fun `R_1005b NoPermission banner Grant permission action invokes its own callback`() {
        var requested = false
        composeTestRule.setContent {
            OrtTheme {
                RigBluetoothScreen(
                    state = state(selectedAddress = sppDevice.address, linkState = RigLinkState.NoPermission),
                    onSelectDevice = {},
                    onPairInSettings = {},
                    onRefresh = {},
                    onContinue = {},
                    onContinueWithoutConnecting = {},
                    onUseUsbInstead = {},
                    onRequestBluetoothPermission = { requested = true },
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-rig-bt-lost-banner").performScrollTo()
        composeTestRule.onNodeWithText("Grant permission").performClick()
        assert(requested)
    }

    /** R-1005b: a Lost/Failed banner carries no action of its own (unchanged) — only NoPermission
     * gained one, so "Grant permission" must never appear for either. */
    @Test
    fun `R_1005b a Lost banner never carries the Grant permission action`() {
        composeTestRule.setContent {
            OrtTheme {
                RigBluetoothScreen(
                    state = state(
                        selectedAddress = sppDevice.address,
                        linkState = RigLinkState.Lost("connection dropped"),
                    ),
                    onSelectDevice = {},
                    onPairInSettings = {},
                    onRefresh = {},
                    onContinue = {},
                    onContinueWithoutConnecting = {},
                    onUseUsbInstead = {},
                    onRequestBluetoothPermission = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-rig-bt-lost-banner").performScrollTo().assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Grant permission").assertCountEquals(0)
    }

    @Test
    fun `E2_E11 Use USB instead invokes its own callback`() {
        var usedUsb = false
        composeTestRule.setContent {
            OrtTheme {
                RigBluetoothScreen(
                    state = state(),
                    onSelectDevice = {},
                    onPairInSettings = {},
                    onRefresh = {},
                    onContinue = {},
                    onContinueWithoutConnecting = {},
                    onUseUsbInstead = { usedUsb = true },
                    onRequestBluetoothPermission = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-rig-bt-use-usb").performClick()
        assert(usedUsb)
    }

    // --- R-1005c (device field report — a trapped operator): the new escape hatch ------------------

    /** R-1005c: `Continue without connecting` is real and wired, independent of `Continue`'s own
     * `Verified`-only gate — must fire even while the checklist has not reached `Verified`. */
    @Test
    fun `R_1005c Continue without connecting invokes its own callback regardless of link state`() {
        var continuedWithoutConnecting = false
        composeTestRule.setContent {
            OrtTheme {
                RigBluetoothScreen(
                    state = state(selectedAddress = sppDevice.address, linkState = RigLinkState.Open),
                    onSelectDevice = {},
                    onPairInSettings = {},
                    onRefresh = {},
                    onContinue = {},
                    onContinueWithoutConnecting = { continuedWithoutConnecting = true },
                    onUseUsbInstead = {},
                    onRequestBluetoothPermission = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-rig-bt-continue-without-connecting").performClick()
        assert(continuedWithoutConnecting)
    }

    /** R-1005c: the artboard's own instruction — the consequence caption sits directly beneath the
     * action so a screen reader reads them together; proven the same way `ReadyScreen.kt`'s own
     * `R_342`/`R_361` regression proof is (one merged node carrying both facts), not merely that
     * both strings appear somewhere on screen. */
    @Test
    fun `R_1005c the action and its consequence caption announce as one node`() {
        composeTestRule.setContent {
            OrtTheme {
                RigBluetoothScreen(
                    state = state(),
                    onSelectDevice = {},
                    onPairInSettings = {},
                    onRefresh = {},
                    onContinue = {},
                    onContinueWithoutConnecting = {},
                    onUseUsbInstead = {},
                    onRequestBluetoothPermission = {},
                )
            }
        }

        composeTestRule
            .onNodeWithContentDescription(
                "Continue without connecting. The frequency is logged by hand until you link the radio.",
            )
            .assertHasClickAction()
    }

    /** R-1003/R-1005c: the board has always drawn a back chevron (line 21 of the artboard) —
     * `onBack = null` was a conformance defect, not a design decision. */
    @Test
    fun `R_1003 a real onBack renders the back chevron and invokes it`() {
        var wentBack = false
        composeTestRule.setContent {
            OrtTheme {
                RigBluetoothScreen(
                    state = state(),
                    onSelectDevice = {},
                    onPairInSettings = {},
                    onRefresh = {},
                    onContinue = {},
                    onContinueWithoutConnecting = {},
                    onUseUsbInstead = {},
                    onRequestBluetoothPermission = {},
                    onBack = { wentBack = true },
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-back").performClick()
        assert(wentBack)
    }

    // --- R-1018 (register, device pass 2, polish) ---------------------------------------------------

    /**
     * `Use USB instead` sat 12dp above the safe-area edge where `Setup-Rig-Bluetooth.dc.html` and
     * design-guide §10.1 specify 24dp of design spacing ("the 24px under a pinned action block is
     * breathing room between the last control and the edge of the *safe* area") — `SetupScaffold`'s
     * own bar `Column` applied `OrtSpacing.md` (12dp) to the bottom edge instead. The mechanism
     * itself is correct — `safeAreaBottomPadding()` is applied *outside* this padding, adding the
     * real navigation-bar inset on top of it rather than absorbing it (`SafeArea.kt`'s own doc
     * comment; unchanged here) — only the design-spacing *value* was wrong. `SafeArea.kt`'s own doc
     * comment confirms `WindowInsets.navigationBars` reads zero under this project's Robolectric
     * setup, so this test's own root has no inset contribution to account for: the whole gap below
     * `Use USB instead` here is exactly the design spacing this test asserts, nothing folded in from
     * a real device's own navigation bar.
     */
    @Test
    fun `R_1018 Use USB instead sits 24dp of design spacing above the screen edge`() {
        composeTestRule.setContent {
            OrtTheme {
                Box(modifier = Modifier.width(390.dp).height(844.dp)) {
                    RigBluetoothScreen(
                        state = state(),
                        onSelectDevice = {},
                        onPairInSettings = {},
                        onRefresh = {},
                        onContinue = {},
                        onContinueWithoutConnecting = {},
                        onUseUsbInstead = {},
                        onRequestBluetoothPermission = {},
                    )
                }
            }
        }

        val rootBounds = composeTestRule.onRoot().getUnclippedBoundsInRoot()
        val useUsbBounds = composeTestRule.onNodeWithTag("setup-rig-bt-use-usb").getUnclippedBoundsInRoot()
        val gapDp = (rootBounds.bottom - useUsbBounds.bottom).value

        assertTrue(
            "expected 24dp of design spacing below 'Use USB instead' (no real navigationBars inset " +
                "exists under Robolectric, SafeArea.kt's own doc comment), got ${gapDp}dp",
            kotlin.math.abs(gapDp - 24f) < 0.5f,
        )
    }

    // --- R-1017 (register, device pass 2, the R-880 family) ---------------------------------------

    /**
     * The marker used to centre against the *whole* label+subtitle column — once the subtitle
     * wraps at font scale 2.0, that floats it between the label and its own sub-line instead of
     * beside the label. Proven at both the tour's own 390dp width and the operator's own real
     * device geometry (1260x2772px @ 420dpi = 480x1056dp) — the coordinator's own device pass found
     * this at 480dp specifically because the existing 390dp coverage never asserted this
     * relationship at all, width-dependent or not.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `R_1017 the paired-device marker aligns to the label, not the wrapped subtitle, at 390dp font scale 2_0`() {
        assertMarkerAlignsWithLabel(widthDp = 390)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `R_1017 the paired-device marker aligns to the label, not the wrapped subtitle, at 480dp font scale 2_0`() {
        assertMarkerAlignsWithLabel(widthDp = 480)
    }

    private fun assertMarkerAlignsWithLabel(widthDp: Int) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrtTheme {
                    Box(modifier = Modifier.width(widthDp.dp)) {
                        RigBluetoothScreen(
                            state = state(devices = listOf(sppDevice)),
                            onSelectDevice = {},
                            onPairInSettings = {},
                            onRefresh = {},
                            onContinue = {},
                            onContinueWithoutConnecting = {},
                            onUseUsbInstead = {},
                            onRequestBluetoothPermission = {},
                        )
                    }
                }
            }
        }

        // R-1017: the row's own `.selectable(...)` merges descendant semantics into one node by
        // default, dropping the marker's own testTag from the merged tree entirely (Compose's own
        // merge policy for TestTag) -- `useUnmergedTree = true` is what reaches it directly.
        val markerBounds = composeTestRule
            .onNodeWithTag("radio-row-marker", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val labelBounds = composeTestRule
            .onNodeWithText(sppDevice.name, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val subtitleBounds = composeTestRule
            .onNodeWithText("serial port profile", substring = true, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()

        assertTrue(
            "expected the sub-line to render below the label at ${widthDp}dp, got label bottom " +
                "${labelBounds.bottom} vs subtitle top ${subtitleBounds.top}",
            labelBounds.bottom <= subtitleBounds.top,
        )
        val markerCenterY = (markerBounds.top + markerBounds.bottom) / 2
        assertTrue(
            "expected the marker's own vertical centre ($markerCenterY) to fall within the label's " +
                "own bounds (${labelBounds.top}..${labelBounds.bottom}) at ${widthDp}dp, never migrated " +
                "down toward the wrapped subtitle (top ${subtitleBounds.top})",
            markerCenterY in labelBounds.top..labelBounds.bottom,
        )
        assertTrue(
            "expected the marker to stay above where the subtitle begins at ${widthDp}dp, got marker " +
                "centre $markerCenterY vs subtitle top ${subtitleBounds.top}",
            markerCenterY < subtitleBounds.top,
        )
    }

    /** R-805 (register, tour run): at font scale 2.0 `Refresh` used to render one letter per line
     * down the right edge because `Pair a device in system settings` took the row's width with
     * neither action weighted. `Refresh`'s own bounds must stay wider than tall — a vertically
     * stacked, one-letter-per-line render would instead measure taller than wide. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `R_805 Refresh keeps its intrinsic single-line width at font scale 2_0`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrtTheme {
                    RigBluetoothScreen(
                        state = state(),
                        onSelectDevice = {},
                        onPairInSettings = {},
                        onRefresh = {},
                        onContinue = {},
                        onContinueWithoutConnecting = {},
                        onUseUsbInstead = {},
                        onRequestBluetoothPermission = {},
                    )
                }
            }
        }

        val size = composeTestRule.onNodeWithTag("setup-rig-bt-refresh").fetchSemanticsNode().size
        assert(size.width > size.height) {
            "expected Refresh wider than tall (single line), was ${size.width} x ${size.height}"
        }
    }

    /** R-942 (register, spec, reviewer A3, run 4a): R-863's own `TextAction` fix
     * (`wrapContentWidth(unbounded = true)`) regressed this exact row on a real 390dp screen —
     * "Pair a device in system settings" and "Refresh" rendered on top of each other at font scale
     * 2.0 (`setup-rig-bluetooth/S10b-rig-bluetooth@2x.png`). R-874's own fix (bounded
     * `wrapContentWidth`, `TextAction`'s own doc comment) is the same root cause this row already
     * avoided the *other* way (`weight(1f)` on the leading action, R-805) — this proves the two
     * fixes now agree: no overlap between the two actions, `Refresh` stays whole and inside the
     * row, never past the real screen's own right edge. */
    @Test
    @Requirement("R-942")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `R_942 Pair in system settings and Refresh never overlap on a real 390dp screen at font scale 2_0`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrtTheme {
                    Box(modifier = Modifier.width(390.dp)) {
                        RigBluetoothScreen(
                            state = state(),
                            onSelectDevice = {},
                            onPairInSettings = {},
                            onRefresh = {},
                            onContinue = {},
                            onContinueWithoutConnecting = {},
                            onUseUsbInstead = {},
                            onRequestBluetoothPermission = {},
                        )
                    }
                }
            }
        }

        val pairBounds = composeTestRule.onNodeWithTag("setup-rig-bt-pair-in-settings").getUnclippedBoundsInRoot()
        val refreshBounds = composeTestRule.onNodeWithTag("setup-rig-bt-refresh").getUnclippedBoundsInRoot()
        assertTrue(
            "expected 'Pair a device in system settings' (right ${pairBounds.right}) and 'Refresh' " +
                "(left ${refreshBounds.left}) to never overlap at font scale 2.0, got an overlap",
            pairBounds.right <= refreshBounds.left || pairBounds.bottom <= refreshBounds.top,
        )
        assertTrue(
            "expected 'Refresh' own right edge (${refreshBounds.right}) to stay within the real 390dp " +
                "screen's own right edge at font scale 2.0, never clipped past it",
            refreshBounds.right <= 390.dp,
        )
        val refreshSize = composeTestRule.onNodeWithTag("setup-rig-bt-refresh").fetchSemanticsNode().size
        assert(refreshSize.width > refreshSize.height) {
            "expected Refresh wider than tall (single line, whole word), was " +
                "${refreshSize.width} x ${refreshSize.height}"
        }
    }

    /** R-1005c (design, artboard redrawn 2026-09-12): the pinned block now stacks three actions
     * plus a caption — this project's own history at font scale 2.0 on a 390dp screen (R-874,
     * R-942, R-980) is actions overlapping or wrapping one letter per line. Bounds, at the tour's
     * own width, native graphics — never merely that every node exists. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `R_1005c the three pinned actions never overlap at font scale 2_0 on a real 390dp screen`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrtTheme {
                    Box(modifier = Modifier.width(390.dp)) {
                        RigBluetoothScreen(
                            state = state(),
                            onSelectDevice = {},
                            onPairInSettings = {},
                            onRefresh = {},
                            onContinue = {},
                            onContinueWithoutConnecting = {},
                            onUseUsbInstead = {},
                            onRequestBluetoothPermission = {},
                        )
                    }
                }
            }
        }

        val continueBounds = composeTestRule.onNodeWithTag("setup-rig-bt-continue").getUnclippedBoundsInRoot()
        val withoutConnectingBounds =
            composeTestRule.onNodeWithTag("setup-rig-bt-continue-without-connecting").getUnclippedBoundsInRoot()
        val useUsbBounds = composeTestRule.onNodeWithTag("setup-rig-bt-use-usb").getUnclippedBoundsInRoot()

        assertTrue(
            "expected Continue (bottom ${continueBounds.bottom}) above Continue without connecting " +
                "(top ${withoutConnectingBounds.top}) at font scale 2.0, got an overlap",
            continueBounds.bottom <= withoutConnectingBounds.top,
        )
        assertTrue(
            "expected Continue without connecting (bottom ${withoutConnectingBounds.bottom}) above " +
                "Use USB instead (top ${useUsbBounds.top}) at font scale 2.0, got an overlap",
            withoutConnectingBounds.bottom <= useUsbBounds.top,
        )
        assertTrue(
            "expected every pinned action to stay within the real 390dp screen's own right edge, " +
                "got Continue without connecting right edge ${withoutConnectingBounds.right}",
            withoutConnectingBounds.right <= 390.dp,
        )
    }
}
