package org.ort.app.ui.setup

import androidx.compose.ui.test.assertIsDisplayed
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

/** R-081 (ui-conformance-plan WP9) — `Setup-Verify.dc.html` (S05). */
@RunWith(RobolectricTestRunner::class)
class VerifyScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `R_081 Continue is disabled while checks are still in progress`() {
        composeTestRule.setContent {
            OrtTheme {
                VerifyScreen(
                    state = VerifyViewState(
                        inputLabel = "USB Audio Device",
                        check = RouteCheckState.InProgress(setOf(RouteCheckStage.NATIVE_RATE), 48_000, 0L),
                    ),
                    onContinue = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-verify-continue").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("setup-verify-check-native-rate").assertIsDisplayed()
    }

    @Test
    fun `R_081 Continue enables once every check has passed, and invokes the callback`() {
        var continued = false
        composeTestRule.setContent {
            OrtTheme {
                VerifyScreen(
                    state = VerifyViewState(
                        inputLabel = "USB Audio Device",
                        check = RouteCheckState.Passed(48_000, null),
                    ),
                    onContinue = { continued = true },
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-verify-continue").assertIsEnabled()
        composeTestRule.onNodeWithTag("setup-verify-continue").performClick()
        assert(continued)
    }
}
