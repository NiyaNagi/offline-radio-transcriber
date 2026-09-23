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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner

/** R-081 (ui-conformance-plan WP9) — `Setup-Input.dc.html` (S04). */
@RunWith(RobolectricTestRunner::class)
class InputScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val usb = InputRouteOption(
        id = "usb-1",
        label = "USB Audio Device",
        subtitle = "USB audio · 48 kHz native",
        advisory = null,
        typeLabel = "USB audio",
    )
    private val mic = InputRouteOption(
        id = "mic-0",
        label = "Built-in microphone",
        subtitle = "Built-in microphone — captures the room, not the radio",
        advisory = RouteAdvisory.ROOM_AUDIO,
        typeLabel = "Built-in microphone",
    )

    /**
     * R-081, amended by **R-1170**: this used to assert `setup-input-verify` was *disabled* until a
     * route was selected. It is now lit at all times and refuses on tap instead — the disabled
     * assertion was inverted rather than deleted, and the half of R-081 this test actually
     * establishes (tapping a row selects it) is unchanged. See the R-1170 tests below for the
     * behaviour that replaced it.
     */
    @Test
    fun `R_081 Verify this input stays lit with no route selected, tapping a row selects it`() {
        var selected: String? = null
        composeTestRule.setContent {
            OrtTheme {
                InputScreen(
                    state = InputViewState(routes = listOf(usb, mic), selectedId = null),
                    onSelect = { selected = it },
                    onRefresh = {},
                    onVerify = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-input-verify").assertIsEnabled()
        composeTestRule.onNodeWithTag("setup-input-route-usb-1").performClick()
        assert(selected == "usb-1")
    }

    // --- R-1170: never disable a primary button for validation state -------------------------------

    /**
     * R-1170 (register; the operator's own words on the device, *"keep the continue button lit up at
     * all times"*). `enabled = state.selectedId != null` gave no feedback about what was wrong, and
     * `disabled` removes the control from the focus order, so a screen-reader user could not reach
     * the thing blocking them at all. The button is lit; the tap validates and refuses.
     */
    @Test
    fun `R_1170 Verify this input is enabled with nothing selected and tapping it does not advance`() {
        var verified = false
        composeTestRule.setContent {
            OrtTheme {
                InputScreen(
                    state = InputViewState(routes = listOf(usb, mic), selectedId = null),
                    onSelect = {},
                    onRefresh = {},
                    onVerify = { verified = true },
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-input-verify").assertIsEnabled().performClick()
        assert(!verified) // the gate still holds -- lit is not the same as permissive
        composeTestRule.onNodeWithTag("setup-input-validation").assertIsDisplayed()
    }

    /** R-1170: the accessibility half — the notice is a live region, so TalkBack announces it the
     * moment the refusal happens rather than leaving the operator to hunt for it. */
    @Test
    fun `R_1170 the notice is a live region a screen reader announces`() {
        composeTestRule.setContent {
            OrtTheme {
                InputScreen(
                    state = InputViewState(routes = listOf(usb, mic), selectedId = null),
                    onSelect = {},
                    onRefresh = {},
                    onVerify = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-input-verify").performClick()
        composeTestRule.onNodeWithTag("setup-input-validation")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive))
    }

    /** R-1170: nothing is said before the operator has asked for anything — the notice answers a
     * tap, it is not a standing scold on arrival. */
    @Test
    fun `R_1170 no notice is shown before the primary has been tapped`() {
        composeTestRule.setContent {
            OrtTheme {
                InputScreen(
                    state = InputViewState(routes = listOf(usb, mic), selectedId = null),
                    onSelect = {},
                    onRefresh = {},
                    onVerify = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-input-validation").assertDoesNotExist()
    }

    /** R-1170: the notice clears itself once the missing thing is supplied, and the primary then
     * does what it always said it would. */
    @Test
    fun `R_1170 with a route selected the notice is gone and the primary advances`() {
        var verified = false
        composeTestRule.setContent {
            OrtTheme {
                InputScreen(
                    state = InputViewState(routes = listOf(usb, mic), selectedId = "usb-1"),
                    onSelect = {},
                    onRefresh = {},
                    onVerify = { verified = true },
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-input-verify").assertIsEnabled().performClick()
        assert(verified)
        composeTestRule.onNodeWithTag("setup-input-validation").assertDoesNotExist()
    }

    /** R-1170: what the tap says is specific to *why* it refused — an empty enumeration is hardware
     * that is not attached, an unchosen row is a decision not yet made, and they are not the same
     * sentence (constitution I). Asserted as "these two differ and neither is null", not against the
     * copy itself, which a designer may legitimately change tomorrow (constitution II). */
    @Test
    fun `R_1170 inputValidationMessage distinguishes no routes from no choice, and is null once chosen`() {
        val none = InputViewState(routes = emptyList(), selectedId = null)
        val unchosen = InputViewState(routes = listOf(usb, mic), selectedId = null)
        val chosen = InputViewState(routes = listOf(usb, mic), selectedId = "usb-1")

        assert(inputValidationMessage(chosen) == null)
        assert(inputValidationMessage(none) != null)
        assert(inputValidationMessage(unchosen) != null)
        assert(inputValidationMessage(none) != inputValidationMessage(unchosen))
    }

    /** R-850 (validator V8, device): the subtitle read "Which of these is the radio?" — the board's
     * own copy is "Which of these carries the radio's audio?" (S04 asks which INPUT carries the
     * audio, not which device the radio itself is). */
    @Test
    fun `R_850 the subtitle matches the board's own copy`() {
        composeTestRule.setContent {
            OrtTheme {
                InputScreen(
                    state = InputViewState(routes = listOf(usb), selectedId = null),
                    onSelect = {},
                    onRefresh = {},
                    onVerify = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Which of these carries the radio's audio?").assertIsDisplayed()
    }

    @Test
    fun `R_081 Verify this input is enabled once a route is selected`() {
        var verified = false
        composeTestRule.setContent {
            OrtTheme {
                InputScreen(
                    state = InputViewState(routes = listOf(usb), selectedId = "usb-1"),
                    onSelect = {},
                    onRefresh = {},
                    onVerify = { verified = true },
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-input-verify").performClick()
        assert(verified)
    }

    /**
     * **AC-128 (FR-CAP-2b, FR-CAP-10).** The built-in mic must be choosable exactly as the USB
     * route is: tapping it selects it, and `Verify this input` unlocks. D33 makes local-microphone
     * capture a named mode, so "the operator can actually get through setup with only a phone" is
     * the property under test, not merely that the row rendered.
     */
    @Test
    fun `AC_128 the built-in mic row is selectable and unlocks Verify, exactly as a USB route does`() {
        var selected: String? = null
        composeTestRule.setContent {
            OrtTheme {
                InputScreen(
                    state = InputViewState(routes = listOf(usb, mic), selectedId = selected),
                    onSelect = { selected = it },
                    onRefresh = {},
                    onVerify = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-input-route-mic-0").performClick()
        assert(selected == "mic-0") { "the mic must be a real selection (FR-CAP-3a), got $selected" }
    }

    /** AC-128: with the mic as the only input — a phone with no adapter attached — setup is still
     * completable. The old enumerator labelled this route as one capture would refuse, which left
     * the only available choice on such a device looking like a dead end. */
    @Test
    fun `AC_128 a device offering only the built-in mic can still reach Verify`() {
        composeTestRule.setContent {
            OrtTheme {
                InputScreen(
                    state = InputViewState(routes = listOf(mic), selectedId = "mic-0"),
                    onSelect = {},
                    onRefresh = {},
                    onVerify = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-input-verify").assertIsEnabled()
    }

    @Test
    fun `R_081 Refresh invokes its callback`() {
        var refreshed = false
        composeTestRule.setContent {
            OrtTheme {
                InputScreen(
                    state = InputViewState(routes = emptyList(), selectedId = null),
                    onSelect = {},
                    onRefresh = { refreshed = true },
                    onVerify = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-input-refresh").performClick()
        assert(refreshed)
    }

    // --- D33/E2-E06 (spec/e2e-capture-modes-plan.md WPD): the preset chip -----------------------

    @Test
    fun `E2_E06 a non-null presetLabel renders the preset chip naming the mode`() {
        composeTestRule.setContent {
            OrtTheme {
                InputScreen(
                    state = InputViewState(
                        routes = listOf(usb),
                        selectedId = "usb-1",
                        presetLabel = "USB-connected radio",
                    ),
                    onSelect = {},
                    onRefresh = {},
                    onVerify = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-input-preset-chip").assertExists()
    }

    @Test
    fun `E2_E06 a null presetLabel omits the chip entirely, eg once the operator has overridden it`() {
        composeTestRule.setContent {
            OrtTheme {
                InputScreen(
                    state = InputViewState(routes = listOf(usb), selectedId = "usb-1", presetLabel = null),
                    onSelect = {},
                    onRefresh = {},
                    onVerify = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-input-preset-chip").assertDoesNotExist()
    }

    // --- R-816 (reviewer finding, halt, FR-CAP-9): presetChipStateFor never claims a preset -----
    // --- that matched no enumerated route ----------------------------------------------------------

    @Test
    fun `R_816 the mode's preferred route present reads the normal preset-by chip`() {
        val usbRoute = usb.copy(routeKind = org.ort.core.capture.AudioRouteKind.USB)
        val state = presetChipStateFor(
            captureMode = org.ort.core.capture.CaptureMode.USB_RADIO,
            modeOverridden = false,
            routes = listOf(usbRoute),
        )

        assert(state.modeLabel == "USB-connected radio") { "got $state" }
        assert(state.presetUnavailableText == null) { "got $state" }
    }

    @Test
    fun `R_816 no capture mode chosen yet reads no chip at all`() {
        val state = presetChipStateFor(captureMode = null, modeOverridden = false, routes = listOf(usb))

        assert(state.modeLabel == null && state.presetUnavailableText == null) { "got $state" }
    }

    @Test
    fun `R_816 an overridden axis reads no chip, even if the preset route is present`() {
        val usbRoute = usb.copy(routeKind = org.ort.core.capture.AudioRouteKind.USB)
        val state = presetChipStateFor(
            captureMode = org.ort.core.capture.CaptureMode.USB_RADIO,
            modeOverridden = true,
            routes = listOf(usbRoute),
        )

        assert(state.modeLabel == null && state.presetUnavailableText == null) { "got $state" }
    }

    /**
     * The exact scenario named in the reviewer finding: a device offering only the built-in mic
     * under USB-connected-radio mode. Driven through the real [InputRouteEnumerator] over
     * [org.ort.capture.android.fake.FakeAudioIo] (constitution II — the same fake capture's own
     * tests use), not a hand-authored [InputRouteOption] list, so this proves the real
     * `routeKind` resolution feeds [presetChipStateFor] honestly end to end.
     */
    @Test
    fun `R_816 USB mode with only the built-in mic attached shows the none-attached chip, never a false preset`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val io = org.ort.capture.android.fake.FakeAudioIo(
            devices = listOf(
                org.ort.capture.android.AudioDeviceDescriptor(
                    "mic-0",
                    org.ort.capture.android.AudioDeviceKind.BUILT_IN_MIC,
                    "Built-in microphone",
                ),
            ),
        )
        val routes = InputRouteEnumerator(context, io).list()

        val state = presetChipStateFor(
            captureMode = org.ort.core.capture.CaptureMode.USB_RADIO,
            modeOverridden = false,
            routes = routes,
        )

        assert(state.modeLabel == null) { "must never claim a preset that matched no route, got $state" }
        assert(
            state.presetUnavailableText == "preset USB audio — none attached, choose a route",
        ) { "got $state" }
    }

    // --- R-902 (reviewer A2, run 3, spec): presetInputRouteFor pre-selects only an unambiguous ---
    // --- single match, never guessing among several ------------------------------------------------

    @Test
    fun `R_902 exactly one enumerated route of the preset kind pre-selects it`() {
        val usbRoute = usb.copy(routeKind = org.ort.core.capture.AudioRouteKind.USB)
        val route = presetInputRouteFor(
            captureMode = org.ort.core.capture.CaptureMode.USB_RADIO,
            routes = listOf(usbRoute, mic),
        )

        assert(route?.id == "usb-1") { "got $route" }
    }

    @Test
    fun `R_902 several routes of the preset kind pre-select none, never guessing which one`() {
        val usbA = usb.copy(id = "usb-1", routeKind = org.ort.core.capture.AudioRouteKind.USB)
        val usbB = usb.copy(id = "usb-2", routeKind = org.ort.core.capture.AudioRouteKind.USB)
        val route = presetInputRouteFor(
            captureMode = org.ort.core.capture.CaptureMode.USB_RADIO,
            routes = listOf(usbA, usbB),
        )

        assert(route == null) { "got $route" }
    }

    @Test
    fun `R_902 no route of the preset kind pre-selects none`() {
        val route = presetInputRouteFor(
            captureMode = org.ort.core.capture.CaptureMode.USB_RADIO,
            routes = listOf(mic),
        )

        assert(route == null) { "got $route" }
    }

    @Test
    fun `R_902 no capture mode chosen yet pre-selects none`() {
        val usbRoute = usb.copy(routeKind = org.ort.core.capture.AudioRouteKind.USB)
        val route = presetInputRouteFor(captureMode = null, routes = listOf(usbRoute))

        assert(route == null) { "got $route" }
    }

    // --- R-852 (validator V8, spec, FR-CAP-9): an explicit pick with no matching preset route ----
    // --- is still a real override, not silently ignored --------------------------------------------

    @Test
    fun `R_852 no route matched the preset at all -- any pick is an override`() {
        val isOverride = isAudioRouteOverride(
            captureMode = org.ort.core.capture.CaptureMode.USB_RADIO,
            presetRouteId = null,
            selectedId = "mic-0",
        )

        assert(isOverride) { "picking a route under the -none attached- chip must record an override" }
    }

    @Test
    fun `R_852 picking exactly the preset route is never an override`() {
        val isOverride = isAudioRouteOverride(
            captureMode = org.ort.core.capture.CaptureMode.USB_RADIO,
            presetRouteId = "usb-1",
            selectedId = "usb-1",
        )

        assert(!isOverride) { "the honest preset match itself must never read as an override" }
    }

    @Test
    fun `R_852 picking a different route than the preset match is an override`() {
        val isOverride = isAudioRouteOverride(
            captureMode = org.ort.core.capture.CaptureMode.USB_RADIO,
            presetRouteId = "usb-1",
            selectedId = "mic-0",
        )

        assert(isOverride)
    }

    @Test
    fun `R_852 no capture mode chosen at all is never an override`() {
        val isOverride = isAudioRouteOverride(captureMode = null, presetRouteId = null, selectedId = "mic-0")

        assert(!isOverride)
    }
}
