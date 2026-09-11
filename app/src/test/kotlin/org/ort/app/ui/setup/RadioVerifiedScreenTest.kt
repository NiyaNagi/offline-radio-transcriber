package org.ort.app.ui.setup

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.ort.pipeline.capture.RigStatus
import org.robolectric.RobolectricTestRunner

/** R-084 (ui-conformance-plan WP9) — `Setup-Rig-Verified.dc.html` (S11). Reachable today only via
 * the debug scenario simulator constructing a real [RigStatus.State.Connected] directly (see
 * [RadioUsbScreenTest]'s identical caveat) — the rendering itself is real, the entry path is not. */
@RunWith(RobolectricTestRunner::class)
class RadioVerifiedScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val connected = RigStatus.State.Connected(
        descriptor = "Kenwood TH-D75A",
        bands = listOf(
            RigStatus.BandState("A", 145_230_000L, "FM", squelchOpen = true),
            RigStatus.BandState("B", 146_960_000L, "FM", squelchOpen = false),
        ),
    )

    @Test
    fun `R_084 shows the connected title, both bands and the verified command list`() {
        composeTestRule.setContent {
            OrtTheme {
                RadioVerifiedScreen(state = connected, onContinue = {}, onChangeRadio = {}, onReconnect = {})
            }
        }

        // R-903: the manufacturer prefix is dropped, matching R-845's own CF06 treatment.
        composeTestRule.onNodeWithText("TH-D75A connected").assertIsDisplayed()
        composeTestRule.onNodeWithTag("setup-radio-verified-band-A").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("setup-radio-verified-band-B").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Squelch state — attributes each over to a band")
            .performScrollTo()
            .assertIsDisplayed()
    }

    /** R-903 (reviewer A2, run 3, design): the 9dp green marker dot beside the title, present only
     * for a genuinely `Connected` reading — never on the `Stale` header, which is not connected. */
    @Test
    fun `R_903 the connected marker dot renders beside the title`() {
        composeTestRule.setContent {
            OrtTheme {
                RadioVerifiedScreen(state = connected, onContinue = {}, onChangeRadio = {}, onReconnect = {})
            }
        }

        composeTestRule.onNodeWithTag("setup-radio-verified-marker").assertIsDisplayed()
    }

    @Test
    fun `R_084 Continue and Change radio both invoke their own callback`() {
        var continued = false
        var changed = false
        composeTestRule.setContent {
            OrtTheme {
                RadioVerifiedScreen(
                    state = connected,
                    onContinue = { continued = true },
                    onChangeRadio = { changed = true },
                    onReconnect = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-radio-verified-continue").performClick()
        assert(continued)
        composeTestRule.onNodeWithTag("setup-radio-verified-change").performClick()
        assert(changed)
    }

    // --- R-125 (validator finding, halt): Stale must render, never a blank screen --------------

    private val stale = RigStatus.State.Stale(lastKnown = connected, sinceMillis = 0L)

    @Test
    fun `R_125 a Stale rig renders the last-known reading, never a blank screen`() {
        composeTestRule.setContent {
            OrtTheme { RadioVerifiedScreen(state = stale, onContinue = {}, onChangeRadio = {}, onReconnect = {}) }
        }

        // R-903: the manufacturer prefix is dropped here too, for the same reason as the
        // Connected title -- both read the same RigStatus.Connected.descriptor.
        composeTestRule.onNodeWithText("TH-D75A — last known").assertIsDisplayed()
        composeTestRule.onNodeWithTag("setup-radio-verified-band-A").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("setup-radio-verified-reconnect").assertIsDisplayed()
        composeTestRule.onNodeWithTag("setup-radio-verified-change").assertIsDisplayed()
        // No Continue on a stale reading -- Reconnect/Change radio are the only ways forward.
        composeTestRule.onNodeWithTag("setup-radio-verified-continue").assertDoesNotExist()
        // R-903: the connected marker dot never renders here -- a stale reading is not connected.
        composeTestRule.onNodeWithTag("setup-radio-verified-marker").assertDoesNotExist()
    }

    @Test
    fun `R_125 Reconnect and Change radio on a Stale rig both invoke their own callback`() {
        var reconnected = false
        var changed = false
        composeTestRule.setContent {
            OrtTheme {
                RadioVerifiedScreen(
                    state = stale,
                    onContinue = {},
                    onChangeRadio = { changed = true },
                    onReconnect = { reconnected = true },
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-radio-verified-reconnect").performClick()
        assert(reconnected)
        composeTestRule.onNodeWithTag("setup-radio-verified-change").performClick()
        assert(changed)
    }

    /** E2-E12 (`spec/e2e-capture-modes-plan.md` WPD) — the subtitle names the transport. */
    @Test
    fun `E2_E12 the subtitle names the transport when one is supplied`() {
        composeTestRule.setContent {
            OrtTheme {
                RadioVerifiedScreen(
                    state = connected,
                    onContinue = {},
                    onChangeRadio = {},
                    onReconnect = {},
                    transportLabel = "Bluetooth SPP",
                )
            }
        }

        composeTestRule.onNodeWithText("Bluetooth SPP · identified and verified").assertIsDisplayed()
    }

    // --- R-903 (reviewer A2, run 3, design): the measured verify duration and AC-133's ------------
    // --- "same command set as USB" clause -----------------------------------------------------------

    @Test
    fun `R_903 a measured verify duration renders in the subtitle`() {
        composeTestRule.setContent {
            OrtTheme {
                RadioVerifiedScreen(
                    state = connected,
                    onContinue = {},
                    onChangeRadio = {},
                    onReconnect = {},
                    transportLabel = "Bluetooth SPP",
                    verifyDurationSeconds = 1.2,
                )
            }
        }

        composeTestRule.onNodeWithText("Bluetooth SPP · identified and verified in 1.2 s").assertIsDisplayed()
    }

    @Test
    fun `R_903 no measured duration renders the plain clause, never a fabricated number`() {
        composeTestRule.setContent {
            OrtTheme {
                RadioVerifiedScreen(
                    state = connected,
                    onContinue = {},
                    onChangeRadio = {},
                    onReconnect = {},
                    transportLabel = "USB serial",
                    verifyDurationSeconds = null,
                )
            }
        }

        composeTestRule.onNodeWithText("USB serial · identified and verified").assertIsDisplayed()
    }

    @Test
    fun `R_903 AC_133 same command set as USB renders only when asked to`() {
        composeTestRule.setContent {
            OrtTheme {
                RadioVerifiedScreen(
                    state = connected,
                    onContinue = {},
                    onChangeRadio = {},
                    onReconnect = {},
                    transportLabel = "Bluetooth SPP",
                    verifyDurationSeconds = 1.2,
                    sameCommandSetAsUsb = true,
                )
            }
        }

        composeTestRule.onNodeWithText("Bluetooth SPP · identified and verified in 1.2 s · same command set as USB")
            .assertIsDisplayed()
    }

    @Test
    fun `radioVerifiedSubtitle joins every real clause the board draws`() {
        assertEquals(
            "Bluetooth SPP · identified and verified in 1.2 s · same command set as USB",
            radioVerifiedSubtitle(
                transportLabel = "Bluetooth SPP",
                verifyDurationSeconds = 1.2,
                sameCommandSetAsUsb = true,
            ),
        )
    }

    @Test
    fun `radioVerifiedSubtitle omits the duration clause when nothing was measured`() {
        assertEquals(
            "USB serial · identified and verified",
            radioVerifiedSubtitle(
                transportLabel = "USB serial",
                verifyDurationSeconds = null,
                sameCommandSetAsUsb = false,
            ),
        )
    }

    @Test
    fun `radioVerifiedSubtitle with no transportLabel still returns a real, capitalised sentence`() {
        assertEquals(
            "Identified and verified",
            radioVerifiedSubtitle(transportLabel = null, verifyDurationSeconds = null, sameCommandSetAsUsb = false),
        )
    }

    @Test
    fun `sameCommandSetAsUsb is true only for identical, non-empty Bluetooth and USB capability sets`() {
        val identical = mapOf(
            org.ort.rig.RigTransportKind.BLUETOOTH_SPP to setOf(org.ort.rig.RigCapability.FREQUENCY),
            org.ort.rig.RigTransportKind.USB_SERIAL to setOf(org.ort.rig.RigCapability.FREQUENCY),
        )
        assertTrue(sameCommandSetAsUsb(identical, org.ort.rig.RigTransportKind.BLUETOOTH_SPP))
    }

    @Test
    fun `sameCommandSetAsUsb is false when the two transports declare different capabilities`() {
        val different = mapOf(
            org.ort.rig.RigTransportKind.BLUETOOTH_SPP to setOf(org.ort.rig.RigCapability.FREQUENCY),
            org.ort.rig.RigTransportKind.USB_SERIAL to
                setOf(org.ort.rig.RigCapability.FREQUENCY, org.ort.rig.RigCapability.SQUELCH_STATE),
        )
        assertFalse(sameCommandSetAsUsb(different, org.ort.rig.RigTransportKind.BLUETOOTH_SPP))
    }

    @Test
    fun `sameCommandSetAsUsb is false when the current transport is not Bluetooth at all`() {
        val identical = mapOf(
            org.ort.rig.RigTransportKind.BLUETOOTH_SPP to setOf(org.ort.rig.RigCapability.FREQUENCY),
            org.ort.rig.RigTransportKind.USB_SERIAL to setOf(org.ort.rig.RigCapability.FREQUENCY),
        )
        assertFalse(sameCommandSetAsUsb(identical, org.ort.rig.RigTransportKind.USB_SERIAL))
        assertFalse(sameCommandSetAsUsb(identical, null))
    }

    @Test
    fun `sameCommandSetAsUsb is false when either transport is undeclared or both are empty`() {
        assertFalse(
            sameCommandSetAsUsb(
                mapOf(org.ort.rig.RigTransportKind.BLUETOOTH_SPP to setOf(org.ort.rig.RigCapability.FREQUENCY)),
                org.ort.rig.RigTransportKind.BLUETOOTH_SPP,
            ),
        )
        assertFalse(
            sameCommandSetAsUsb(
                mapOf(
                    org.ort.rig.RigTransportKind.BLUETOOTH_SPP to emptySet(),
                    org.ort.rig.RigTransportKind.USB_SERIAL to emptySet(),
                ),
                org.ort.rig.RigTransportKind.BLUETOOTH_SPP,
            ),
        )
    }
}
