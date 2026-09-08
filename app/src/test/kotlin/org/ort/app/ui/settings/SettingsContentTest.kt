package org.ort.app.ui.settings

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * R-090 round 3 (WP3's host find): [SettingsContent.initialScreen] lets a caller land directly on
 * one sub-screen — `Assets` (Now's "Install a model", Setup S12's `Install`), `Storage` (F6's
 * "Free space"), `Rig` (F9's "Reconnect"), `Capture` (N06's `Adjust`) — instead of always opening
 * the `Settings` root first. `null` (the default) keeps the pre-existing root-first behaviour.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun ComposeContentTestRule.waitUntilTextExists(text: String, timeoutMillis: Long = 5_000) {
        waitUntil(timeoutMillis) {
            runCatching { onNodeWithText(text, substring = true).assertExists() }.isSuccess
        }
    }

    @Test
    @Requirement("R-090")
    fun `R_090 no initialScreen opens the Settings root, unchanged from before this parameter existed`() {
        composeTestRule.setContent { OrtTheme { SettingsContent(context = context, onDrawer = {}) } }

        composeTestRule.waitUntilTextExists("RECORDS")
        composeTestRule.onNodeWithText("RECORDS").assertExists()
    }

    @Test
    @Requirement("R-090")
    fun `R_090 initialScreen STORAGE lands directly on Storage, never rendering the root section labels`() {
        composeTestRule.setContent {
            OrtTheme { SettingsContent(context = context, onDrawer = {}, initialScreen = SettingsScreenId.STORAGE) }
        }

        composeTestRule.waitUntilTextExists("Storage and retention")
        composeTestRule.onNodeWithText("Storage and retention").assertExists()
        composeTestRule.onNodeWithText("RECORDS").assertDoesNotExist()
    }

    @Test
    @Requirement("R-090")
    fun `R_090 initialScreen ASSETS lands directly on Models and lexicon`() {
        composeTestRule.setContent {
            OrtTheme { SettingsContent(context = context, onDrawer = {}, initialScreen = SettingsScreenId.ASSETS) }
        }

        composeTestRule.waitUntilTextExists("Models and lexicon")
        composeTestRule.onNodeWithText("Models and lexicon").assertExists()
    }

    @Test
    @Requirement("R-090")
    fun `R_090 initialScreen RIG lands directly on the Rig screen`() {
        composeTestRule.setContent {
            OrtTheme { SettingsContent(context = context, onDrawer = {}, initialScreen = SettingsScreenId.RIG) }
        }

        composeTestRule.waitUntilTextExists("No radio configured")
        composeTestRule.onNodeWithText("No radio configured").assertExists()
        composeTestRule.onNodeWithText("RECORDS").assertDoesNotExist()
    }

    @Test
    @Requirement("R-090")
    fun `R_090 initialScreen CAPTURE lands directly on Input and level`() {
        composeTestRule.setContent {
            OrtTheme { SettingsContent(context = context, onDrawer = {}, initialScreen = SettingsScreenId.CAPTURE) }
        }

        composeTestRule.waitUntilTextExists("Input and level")
        composeTestRule.onNodeWithText("Input and level").assertExists()
        composeTestRule.onNodeWithText("RECORDS").assertDoesNotExist()
    }
}
