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

    private val usb = InputRouteOption("usb-1", "USB Audio Device", "USB · 48 kHz native", isBuiltInMic = false)
    private val mic = InputRouteOption(
        id = "mic-0",
        label = "Built-in microphone",
        subtitle = "Not a radio — capture will refuse this route",
        isBuiltInMic = true,
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
