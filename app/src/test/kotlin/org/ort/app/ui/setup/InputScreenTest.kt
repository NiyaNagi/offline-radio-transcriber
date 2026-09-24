package org.ort.app.ui.setup

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTextExactly
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.components.OrtIcons
import org.ort.app.ui.theme.OrtTheme
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** R-081 (ui-conformance-plan WP9) — `Setup-Input.dc.html` (S04). */
@RunWith(RobolectricTestRunner::class)
// P39: the merged Listen screen is genuinely taller than Robolectric's 320x470dp default, so these
// assertions are made at the tour's own geometry rather than at a viewport no real device has.
@Config(qualifiers = "w390dp-h844dp-420dpi")
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
                ListenScreen(
                    state = ListenViewState(routes = listOf(usb, mic), selectedId = null),
                    actions = ListenActions(
                        onSelect = { selected = it },
                        onRefresh = {},
                        onVerify = {},
                        onContinue = {},
                        onTryAgain = {},
                        onChooseAnotherInput = {},
                    ),
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-input-verify").assertIsEnabled()
        composeTestRule.onNodeWithTag("setup-input-route-usb-1").performClick()
        assert(selected == "usb-1")
    }

    // --- R-1187 (register; the R-1017/R-880 family): the route row's label is bound to its own -----
    // --- control, not floating above it, once the subtitle wraps -----------------------------------

    /**
     * **The defect, exactly.** [org.ort.app.ui.components.RadioRow] centres its marker against the
     * *whole* label+subtitle column, and [InputSection]'s own leading device-type [androidx.compose
     * .material3.Icon] does the same against the row. Once the subtitle wraps — which it does for
     * every route on the emulator, and will for any USB adapter with a long name — the centre of
     * that column has migrated down past the label, so the marker and the icon sit beside the
     * *sub-line* while the label floats above them. The row then reads as a heading followed by an
     * unrelated control, on the one screen a first run cannot avoid and the one whose whole job is
     * picking the right input.
     *
     * Identical in shape to R-1017, which fixed exactly this on `RigBluetoothScreen`'s paired-device
     * rows and left this caller — then one of twelve screens — behind.
     *
     * **The invariant, and why this one.** *The marker's own vertical centre falls within the label's
     * own vertical bounds, and above where the subtitle begins.* That is the geometric statement of
     * "this control belongs to that label", it survives any amount of wrapping, and it is the thing a
     * render test cannot see: every existing assertion on this screen passes with the row broken.
     * The same is asserted of the leading icon, which is a sibling of the row rather than part of it
     * and so had to be aligned separately.
     *
     * Run at 1.0 as well as 2.0 because the emulator capture that filed this row was at 1.0 — the
     * subtitle does not need a large font to wrap, only a long device name.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Requirement("R-1187", "R-1017")
    fun `R_1187 the route row marker and icon sit beside the label, not below it, at font scale 1_0`() {
        assertRouteRowControlsAlignWithLabel(fontScale = 1f)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Requirement("R-1187", "R-1017")
    fun `R_1187 the route row marker and icon sit beside the label, not below it, at font scale 2_0`() {
        assertRouteRowControlsAlignWithLabel(fontScale = 2f)
    }

    /**
     * The emulator's own case, verbatim from `setup-verified/S04-listen.png`: every enumerated route
     * is named `sdk_gphone64_x86_64` and carries a two-line subtitle. [InputRouteEnumerator]'s own
     * `typeLabel` supplies the icon, so this fixture composes the same three pieces production does.
     */
    private fun assertRouteRowControlsAlignWithLabel(fontScale: Float) {
        val route = InputRouteOption(
            id = "usb-1",
            label = "sdk_gphone64_x86_64",
            subtitle = "USB audio · 48 kHz native · captured through the adapter, not the phone's own microphone",
            advisory = null,
            typeLabel = "USB audio",
            icon = OrtIcons.usbAudio,
        )
        composeTestRule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = base.density, fontScale = fontScale),
            ) {
                OrtTheme {
                    ListenScreen(
                        state = ListenViewState(routes = listOf(route), selectedId = "usb-1"),
                        actions = ListenActions(
                            onSelect = {},
                            onRefresh = {},
                            onVerify = {},
                            onContinue = {},
                            onTryAgain = {},
                            onChooseAnotherInput = {},
                        ),
                    )
                }
            }
        }

        // R-1017: the row's own `.selectable(...)` merges descendant semantics, dropping the marker's
        // testTag from the merged tree entirely -- `useUnmergedTree = true` is what reaches it.
        val marker = composeTestRule
            .onNodeWithTag("radio-row-marker", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val icon = composeTestRule
            .onNodeWithTag("setup-input-route-icon-usb-1", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val label = composeTestRule
            .onNodeWithText(route.label, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val subtitle = composeTestRule
            .onNodeWithText("48 kHz native", substring = true, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()

        // The premise: this test says nothing unless the subtitle genuinely wrapped. A single-line
        // `chip` sub-line is shorter than a single-line `control` label, so a taller subtitle is a
        // wrapped one.
        val labelHeight = (label.bottom - label.top).value
        val subtitleHeight = (subtitle.bottom - subtitle.top).value
        assertTrue(
            "the fixture must produce a wrapped subtitle at font scale $fontScale or this test proves " +
                "nothing -- label ${labelHeight}dp vs subtitle ${subtitleHeight}dp",
            subtitleHeight > labelHeight,
        )

        listOf("marker" to marker, "icon" to icon).forEach { (name, bounds) ->
            val centreY = (bounds.top + bounds.bottom) / 2
            assertTrue(
                "expected the $name's vertical centre ($centreY) to fall within the label's own bounds " +
                    "(${label.top}..${label.bottom}) at font scale $fontScale -- a control below its own " +
                    "label reads as an unrelated one",
                centreY in label.top..label.bottom,
            )
            assertTrue(
                "expected the $name to stay above where the wrapped subtitle begins at font scale " +
                    "$fontScale, got centre $centreY vs subtitle top ${subtitle.top}",
                centreY < subtitle.top,
            )
        }
    }

    /**
     * R-1187's accessibility half, and the half a screenshot cannot show. A screen reader must
     * announce the control *with its name*: the label and its sub-line have to be inside the row's
     * own selectable node, not siblings of it.
     *
     * Constitution VIII is explicit that accessibility is judged on a device and never from the test
     * tree — Compose's merged `SemanticsNode` tree and the real `AccessibilityNodeInfo` tree have
     * disagreed on exactly this before (R-342, R-361, both on `ReadyScreen`). This asserts the
     * strongest thing available without one: the node carrying the selection action is the node
     * carrying the text. A `uiautomator dump` still settles it.
     */
    @Test
    @Requirement("R-1187")
    fun `R_1187 the route row's label is inside its own selectable node`() {
        composeTestRule.setContent {
            OrtTheme {
                ListenScreen(
                    state = ListenViewState(routes = listOf(usb, mic), selectedId = "usb-1"),
                    actions = ListenActions(
                        onSelect = {},
                        onRefresh = {},
                        onVerify = {},
                        onContinue = {},
                        onTryAgain = {},
                        onChooseAnotherInput = {},
                    ),
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-input-route-usb-1")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
            .assert(hasTextExactly(usb.label, usb.subtitle!!))
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
                ListenScreen(
                    state = ListenViewState(routes = listOf(usb, mic), selectedId = null),
                    actions = ListenActions(
                        onSelect = {},
                        onRefresh = {},
                        onVerify = { verified = true },
                        onContinue = {},
                        onTryAgain = {},
                        onChooseAnotherInput = {},
                    ),
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-input-verify").assertIsEnabled().performClick()
        assert(!verified) // the gate still holds -- lit is not the same as permissive
        composeTestRule.onNodeWithTag("setup-listen-validation").assertIsDisplayed()
    }

    /** R-1170: the accessibility half — the notice is a live region, so TalkBack announces it the
     * moment the refusal happens rather than leaving the operator to hunt for it. */
    @Test
    fun `R_1170 the notice is a live region a screen reader announces`() {
        composeTestRule.setContent {
            OrtTheme {
                ListenScreen(
                    state = ListenViewState(routes = listOf(usb, mic), selectedId = null),
                    actions = ListenActions(
                        onSelect = {},
                        onRefresh = {},
                        onVerify = {},
                        onContinue = {},
                        onTryAgain = {},
                        onChooseAnotherInput = {},
                    ),
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-input-verify").performClick()
        composeTestRule.onNodeWithTag("setup-listen-validation")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive))
    }

    /** R-1170: nothing is said before the operator has asked for anything — the notice answers a
     * tap, it is not a standing scold on arrival. */
    @Test
    fun `R_1170 no notice is shown before the primary has been tapped`() {
        composeTestRule.setContent {
            OrtTheme {
                ListenScreen(
                    state = ListenViewState(routes = listOf(usb, mic), selectedId = null),
                    actions = ListenActions(
                        onSelect = {},
                        onRefresh = {},
                        onVerify = {},
                        onContinue = {},
                        onTryAgain = {},
                        onChooseAnotherInput = {},
                    ),
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-listen-validation").assertDoesNotExist()
    }

    /** R-1170: the notice clears itself once the missing thing is supplied, and the primary then
     * does what it always said it would. */
    @Test
    fun `R_1170 with a route selected the notice is gone and the primary advances`() {
        var verified = false
        composeTestRule.setContent {
            OrtTheme {
                ListenScreen(
                    state = ListenViewState(routes = listOf(usb, mic), selectedId = "usb-1"),
                    actions = ListenActions(
                        onSelect = {},
                        onRefresh = {},
                        onVerify = { verified = true },
                        onContinue = {},
                        onTryAgain = {},
                        onChooseAnotherInput = {},
                    ),
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-input-verify").assertIsEnabled().performClick()
        assert(verified)
        composeTestRule.onNodeWithTag("setup-listen-validation").assertDoesNotExist()
    }

    /** R-1170: what the tap says is specific to *why* it refused — an empty enumeration is hardware
     * that is not attached, an unchosen row is a decision not yet made, and they are not the same
     * sentence (constitution I). Asserted as "these two differ and neither is null", not against the
     * copy itself, which a designer may legitimately change tomorrow (constitution II). */
    @Test
    fun `R_1170 inputValidationMessage distinguishes no routes from no choice, and is null once chosen`() {
        val none = ListenViewState(routes = emptyList(), selectedId = null)
        val unchosen = ListenViewState(routes = listOf(usb, mic), selectedId = null)
        val chosen = ListenViewState(routes = listOf(usb, mic), selectedId = "usb-1")

        assert(inputValidationMessage(chosen.routes, chosen.selectedId) == null)
        assert(inputValidationMessage(none.routes, none.selectedId) != null)
        assert(inputValidationMessage(unchosen.routes, unchosen.selectedId) != null)
        assert(
            inputValidationMessage(none.routes, none.selectedId) !=
                inputValidationMessage(unchosen.routes, unchosen.selectedId),
        )
    }

    /**
     * R-850 (validator V8, device) was a copy defect: the subtitle read "Which of these is the
     * radio?" when the question is which *input* carries the radio's audio. **P39 subsumes it** — the
     * merged screen asks the whole question in its title and labels the route list itself, and the
     * subtitle it used to get wrong no longer exists. Retargeted from the wording (which constitution
     * II says never to assert, and which this test was asserting) to the stable tag on the section
     * heading, which is what actually tells the operator what the list below it is.
     */
    @Test
    fun `R_850 the route list is labelled as the inputs, not left unnamed`() {
        composeTestRule.setContent {
            OrtTheme {
                ListenScreen(
                    state = ListenViewState(routes = listOf(usb), selectedId = null),
                    actions = ListenActions(
                        onSelect = {},
                        onRefresh = {},
                        onVerify = {},
                        onContinue = {},
                        onTryAgain = {},
                        onChooseAnotherInput = {},
                    ),
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-listen-section-inputs").assertIsDisplayed()
    }

    @Test
    fun `R_081 Verify this input is enabled once a route is selected`() {
        var verified = false
        composeTestRule.setContent {
            OrtTheme {
                ListenScreen(
                    state = ListenViewState(routes = listOf(usb), selectedId = "usb-1"),
                    actions = ListenActions(
                        onSelect = {},
                        onRefresh = {},
                        onVerify = { verified = true },
                        onContinue = {},
                        onTryAgain = {},
                        onChooseAnotherInput = {},
                    ),
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
                ListenScreen(
                    state = ListenViewState(routes = listOf(usb, mic), selectedId = selected),
                    actions = ListenActions(
                        onSelect = { selected = it },
                        onRefresh = {},
                        onVerify = {},
                        onContinue = {},
                        onTryAgain = {},
                        onChooseAnotherInput = {},
                    ),
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
                ListenScreen(
                    state = ListenViewState(routes = listOf(mic), selectedId = "mic-0"),
                    actions = ListenActions(
                        onSelect = {},
                        onRefresh = {},
                        onVerify = {},
                        onContinue = {},
                        onTryAgain = {},
                        onChooseAnotherInput = {},
                    ),
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
                ListenScreen(
                    state = ListenViewState(routes = emptyList(), selectedId = null),
                    actions = ListenActions(
                        onSelect = {},
                        onRefresh = { refreshed = true },
                        onVerify = {},
                        onContinue = {},
                        onTryAgain = {},
                        onChooseAnotherInput = {},
                    ),
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
                ListenScreen(
                    state = ListenViewState(
                        routes = listOf(usb),
                        selectedId = "usb-1",
                        presetLabel = "USB-connected radio",
                    ),
                    actions = ListenActions(
                        onSelect = {},
                        onRefresh = {},
                        onVerify = {},
                        onContinue = {},
                        onTryAgain = {},
                        onChooseAnotherInput = {},
                    ),
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-input-preset-chip").assertExists()
    }

    @Test
    fun `E2_E06 a null presetLabel omits the chip entirely, eg once the operator has overridden it`() {
        composeTestRule.setContent {
            OrtTheme {
                ListenScreen(
                    state = ListenViewState(routes = listOf(usb), selectedId = "usb-1", presetLabel = null),
                    actions = ListenActions(
                        onSelect = {},
                        onRefresh = {},
                        onVerify = {},
                        onContinue = {},
                        onTryAgain = {},
                        onChooseAnotherInput = {},
                    ),
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
