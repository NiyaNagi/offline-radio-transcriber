package org.ort.app.ui.setup

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner

/** E2-E07 (`spec/e2e-capture-modes-plan.md` WPD) — `Setup-Bluetooth-Permission.dc.html` (S02c). */
@RunWith(RobolectricTestRunner::class)
class BluetoothPermissionScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `E2_E07 renders the rationale, the decline banner and counter 2 of 8`() {
        composeTestRule.setContent {
            OrtTheme { BluetoothPermissionScreen(onAllow = {}, onNotNow = {}) }
        }

        composeTestRule.onNodeWithText("Nearby devices").assertIsDisplayed()
        composeTestRule.onNodeWithText("2 of 8").assertIsDisplayed()
        composeTestRule.onNodeWithTag("setup-bt-permission-decline-banner").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `E2_E07 tapping Allow nearby devices invokes onAllow`() {
        var allowed = false
        composeTestRule.setContent {
            OrtTheme { BluetoothPermissionScreen(onAllow = { allowed = true }, onNotNow = {}) }
        }

        composeTestRule.onNodeWithTag("setup-bt-permission-allow").performClick()
        assert(allowed)
    }

    @Test
    fun `E2_E07 tapping Not now use USB instead invokes onNotNow`() {
        var declined = false
        composeTestRule.setContent {
            OrtTheme { BluetoothPermissionScreen(onAllow = {}, onNotNow = { declined = true }) }
        }

        composeTestRule.onNodeWithTag("setup-bt-permission-not-now").performClick()
        assert(declined)
    }
}
