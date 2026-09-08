package org.ort.app.ui.setup

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
        refused = false,
        typeLabel = "USB audio",
    )
    private val mic = InputRouteOption(
        id = "mic-0",
        label = "Built-in microphone",
        subtitle = "Built-in microphone — not a radio, capture will refuse this route",
        refused = true,
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
