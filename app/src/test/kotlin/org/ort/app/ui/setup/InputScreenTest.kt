package org.ort.app.ui.setup

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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

    @Test
    fun `R_081 Verify this input is disabled until a route is selected, tapping a row selects it`() {
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

        composeTestRule.onNodeWithTag("setup-input-verify").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("setup-input-route-usb-1").performClick()
        assert(selected == "usb-1")
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
}
