package org.ort.app.ui.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner

/**
 * R-090 (`Settings.dc.html`): the grouped-rows root — every section renders its rows, and tapping
 * one calls back with that row's [SettingsScreenId], never a hardcoded destination.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsRootScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun state() = SettingsRootViewState(
        sections = listOf(
            SettingsSectionViewState(
                label = "Capture",
                rows = listOf(
                    SettingsRowViewState("Input and level", "USB Audio Device · verified", SettingsScreenId.CAPTURE),
                    SettingsRowViewState("Radio", "No radio configured", SettingsScreenId.RIG),
                    SettingsRowViewState("Tier and capability", "Tier 3 of 3", SettingsScreenId.TIER),
                ),
            ),
            SettingsSectionViewState(
                label = "Records",
                rows = listOf(
                    SettingsRowViewState(
                        "Storage and retention",
                        "0.0 GB audio · no budget set",
                        SettingsScreenId.STORAGE,
                    ),
                    SettingsRowViewState("Models and lexicon", "0 of 4 assets installed", SettingsScreenId.ASSETS),
                    SettingsRowViewState("Export", "Log, transcripts and digests", SettingsScreenId.EXPORT),
                ),
            ),
        ),
    )

    @Test
    fun `R_090 every row renders its label and sub-line`() {
        composeTestRule.setContent { OrtTheme { SettingsRootScreen(state = state(), onDrawer = {}, onOpen = {}) } }

        composeTestRule.onNodeWithContentDescription("Input and level. USB Audio Device · verified").assertExists()
        composeTestRule.onNodeWithContentDescription("Models and lexicon. 0 of 4 assets installed").assertExists()
    }

    @Test
    fun `R_090 tapping a row calls back with that row's screen id, not a hardcoded one`() {
        var opened: SettingsScreenId? = null
        composeTestRule.setContent {
            OrtTheme { SettingsRootScreen(state = state(), onDrawer = {}, onOpen = { opened = it }) }
        }

        composeTestRule.onNodeWithContentDescription("Radio. No radio configured").performClick()

        assert(opened == SettingsScreenId.RIG) { "expected RIG, got $opened" }
    }

    @Test
    fun `R_130 draws no drawer icon of its own — the host's ScreenHeader is the only one`() {
        composeTestRule.setContent { OrtTheme { SettingsRootScreen(state = state(), onDrawer = {}, onOpen = {}) } }

        composeTestRule.onAllNodesWithContentDescription("Open navigation").assertCountEquals(0)
    }
}
