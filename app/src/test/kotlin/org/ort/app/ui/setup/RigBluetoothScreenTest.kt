package org.ort.app.ui.setup

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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

/** E2-E10/E2-E11 (`spec/e2e-capture-modes-plan.md` WPD) — `Setup-Rig-Bluetooth.dc.html` (S10b). */
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
                    onUseUsbInstead = {},
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
                    onUseUsbInstead = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Handheld BT").assertIsDisplayed()
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
                    onUseUsbInstead = {},
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
                    onUseUsbInstead = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-rig-bt-pair-in-settings").performClick()
        composeTestRule.onNodeWithTag("setup-rig-bt-refresh").performClick()
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
                    onUseUsbInstead = {},
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
                    onUseUsbInstead = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-rig-bt-continue").assertIsEnabled().performClick()
        assert(continued)
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
                    onUseUsbInstead = {},
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
                    onUseUsbInstead = {},
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
                    onUseUsbInstead = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-rig-bt-lost-banner").performScrollTo().assertIsDisplayed()
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
                    onUseUsbInstead = { usedUsb = true },
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-rig-bt-use-usb").performClick()
        assert(usedUsb)
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
                        onUseUsbInstead = {},
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
                            onUseUsbInstead = {},
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
}
