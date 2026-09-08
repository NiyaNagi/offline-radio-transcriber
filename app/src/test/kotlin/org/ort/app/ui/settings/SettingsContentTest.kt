package org.ort.app.ui.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
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

    // Split into two tests (round 6 fix-up): a single `ComposeContentTestRule` refuses a second
    // `setContent` call within one test ("Cannot call setContent twice per test!") — each half of
    // the double-header regression this covers needs its own fresh composition regardless, so this
    // is not a loss of coverage, just two `@Test` functions instead of one two-act test body.

    @Test
    @Requirement("R-090")
    fun `R_090_settings_root_has_one_header_and_sub_screens_one_drill_in_header — the root draws exactly one`() {
        // Round 6 (WP3's own smoke test find): the root draws its own `ScreenHeader` again
        // (`SettingsRootScreen`'s own doc comment says why) — exactly one "Open navigation" node,
        // never zero (that would be R-130's bug back) and never two (the double-header this fixes).
        composeTestRule.setContent { OrtTheme { SettingsContent(context = context, onDrawer = {}) } }
        composeTestRule.waitUntilTextExists("RECORDS")
        composeTestRule.onAllNodesWithContentDescription("Open navigation").assertCountEquals(1)
    }

    @Test
    @Requirement("R-090")
    fun `R_090_settings_root_has_one_header_and_sub_screens_one_drill_in_header — a sub-screen's own header`() {
        // A sub-screen entered directly via `initialScreen` (WP3's own real entry points) draws
        // only its `DrillInHeader` back chevron — no `ScreenHeader`/drawer icon of its own, since
        // this composable no longer draws one outside the root.
        composeTestRule.setContent {
            OrtTheme { SettingsContent(context = context, onDrawer = {}, initialScreen = SettingsScreenId.STORAGE) }
        }
        composeTestRule.waitUntilTextExists("Storage and retention")
        composeTestRule.onNodeWithContentDescription("Back to Settings").assertExists()
        composeTestRule.onAllNodesWithContentDescription("Open navigation").assertCountEquals(0)
    }
}
