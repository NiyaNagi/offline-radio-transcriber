package org.ort.app.ui.setup

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.capture.RigVerification
import org.ort.rig.RigCapability
import org.robolectric.RobolectricTestRunner

/**
 * R-084 (ui-conformance-plan WP9) — `Setup-Rig-Usb.dc.html` (S10). `:rig`/`:rig-usb` are unbuilt
 * (register R-084), so [RigStatus.State.Absent] — the only real producer today — must render an
 * honest "no rig support" failed state, never a fabricated USB-attach checklist. WP2's shared
 * `TextField` (its follow-up landed after this screen's first version) now actually captures the
 * frequency the operator is invited to enter by hand.
 */
@RunWith(RobolectricTestRunner::class)
class RadioUsbScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `R_084 Absent renders an honest no-rig-support state, never a fabricated checklist`() {
        composeTestRule.setContent {
            OrtTheme { RadioUsbScreen(rigStatus = RigStatus.State.Absent, onBack = {}, onEnterFrequency = {}) }
        }

        composeTestRule.onNodeWithTag("setup-radio-usb-unsupported").assertIsDisplayed()
        composeTestRule.onNodeWithText("No rig support in this build yet").assertIsDisplayed()
    }

    /**
     * R-344 (validator pass 4, halt): a blank/unparseable frequency must never be submittable at
     * all — `onEnterFrequency` is never invoked with a null (constitution I: never silently lose a
     * fact). `onBack` stays reachable either way.
     *
     * **R-1170** inverted the mechanism, not the guarantee: this used to assert the primary was
     * `assertIsNotEnabled()`. It is now lit and the *tap itself* refuses, which is strictly a
     * stronger test — the click is really performed here, and the sentinel is still untouched.
     */
    @Test
    fun `R_344 a blank field never submits a frequency, however lit the button is`() {
        var entered: Long? = -1L // sentinel distinct from both null and any real value
        var back = false
        composeTestRule.setContent {
            OrtTheme {
                RadioUsbScreen(
                    rigStatus = RigStatus.State.Absent,
                    onBack = { back = true },
                    onEnterFrequency = { entered = it },
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-radio-usb-enter-frequency").assertIsEnabled().performClick()
        assertEquals(-1L, entered) // untouched -- never invoked, not even with null
        composeTestRule.onNodeWithTag("setup-radio-usb-validation").assertIsDisplayed()
        composeTestRule.onNodeWithTag("setup-radio-usb-back").performClick()
        assert(back)
    }

    /** R-1170, the accessibility half. */
    @Test
    fun `R_1170 the frequency notice is a live region a screen reader announces`() {
        composeTestRule.setContent {
            OrtTheme { RadioUsbScreen(rigStatus = RigStatus.State.Absent, onBack = {}, onEnterFrequency = {}) }
        }

        composeTestRule.onNodeWithTag("setup-radio-usb-enter-frequency").performClick()
        composeTestRule.onNodeWithTag("setup-radio-usb-validation")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive))
    }

    /** R-1170: no standing scold on arrival. */
    @Test
    fun `R_1170 no frequency notice is shown before the primary has been tapped`() {
        composeTestRule.setContent {
            OrtTheme { RadioUsbScreen(rigStatus = RigStatus.State.Absent, onBack = {}, onEnterFrequency = {}) }
        }

        composeTestRule.onNodeWithTag("setup-radio-usb-validation").assertDoesNotExist()
    }

    @Test
    fun `R_084 typing a frequency and confirming reports its exact Hz value`() {
        var entered: Long? = null
        composeTestRule.setContent {
            OrtTheme {
                RadioUsbScreen(rigStatus = RigStatus.State.Absent, onBack = {}, onEnterFrequency = { entered = it })
            }
        }

        composeTestRule.onNodeWithTag("setup-radio-usb-frequency-field").performTextInput("145.230")
        composeTestRule.onNodeWithTag("setup-radio-usb-enter-frequency").assertIsEnabled().performClick()

        assertEquals(145_230_000L, entered)
    }

    /**
     * R-344: a zero/negative/unparseable entry must be refused exactly the same as a blank one —
     * [parseMegahertzToHz] already refuses these; this proves the button tracks it live.
     * **R-1170**: was `assertIsNotEnabled()`; now the tap itself refuses and nothing is submitted.
     */
    @Test
    fun `R_344 an unparseable or non-positive value is still refused`() {
        var entered: Long? = -1L
        composeTestRule.setContent {
            OrtTheme {
                RadioUsbScreen(rigStatus = RigStatus.State.Absent, onBack = {}, onEnterFrequency = { entered = it })
            }
        }

        composeTestRule.onNodeWithTag("setup-radio-usb-frequency-field").performTextInput("not a number")
        composeTestRule.onNodeWithTag("setup-radio-usb-enter-frequency").assertIsEnabled().performClick()
        assertEquals(-1L, entered)
    }

    @Test
    fun `R_084 a Connected reading (only reachable via the debug scenario simulator) renders the verified content`() {
        val connected = RigStatus.State.Connected(
            descriptor = "Kenwood TH-D75A",
            bands = listOf(RigStatus.BandState("A", 145_230_000L, "FM", squelchOpen = true)),
        )
        composeTestRule.setContent {
            OrtTheme { RadioUsbScreen(rigStatus = connected, onBack = {}, onEnterFrequency = {}) }
        }

        composeTestRule.onNodeWithText("Reading now".uppercase()).assertIsDisplayed()
    }

    /** R-1019 (register): this defensive fallback shares [RigVerifiedContent] with S11 — a
     * partially verified reading here must read the same "Command set" header (never "Verified
     * command set") the fix for S12 established, not a separate, silently-still-claiming-verified
     * copy of the same content. */
    @Test
    fun `R_1019 a Partial verification renders Command set, never Verified command set`() {
        val connected = RigStatus.State.Connected(
            descriptor = "Kenwood TH-D75A",
            bands = listOf(RigStatus.BandState("A", 145_230_000L, "FM", squelchOpen = true)),
            verification = RigVerification.Partial(setOf(RigCapability.SIGNAL_STRENGTH)),
        )
        composeTestRule.setContent {
            OrtTheme { RadioUsbScreen(rigStatus = connected, onBack = {}, onEnterFrequency = {}) }
        }

        composeTestRule.onNodeWithText("Command set".uppercase()).performScrollTo().assertIsDisplayed()
    }

    // --- parseMegahertzToHz: pure, no Compose ----------------------------------------------------

    @Test
    fun `R_084 parseMegahertzToHz converts a plain MHz value to exact Hz`() {
        assertEquals(145_230_000L, parseMegahertzToHz("145.230"))
    }

    @Test
    fun `R_084 parseMegahertzToHz returns null for blank, zero or unparseable text, never a guess`() {
        assertNull(parseMegahertzToHz(""))
        assertNull(parseMegahertzToHz("   "))
        assertNull(parseMegahertzToHz("not a number"))
        assertNull(parseMegahertzToHz("0"))
        assertNull(parseMegahertzToHz("-5"))
    }
}
