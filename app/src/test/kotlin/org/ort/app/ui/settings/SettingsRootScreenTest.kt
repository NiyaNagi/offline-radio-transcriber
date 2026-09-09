package org.ort.app.ui.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.components.OrtIcons
import org.ort.app.ui.theme.OrtTheme
import org.ort.testing.Requirement
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
    fun `R_130 round 6 draws exactly one drawer icon of its own again — see the file's own kdoc`() {
        // Round 4's R-130 fix removed this screen's own `ScreenHeader` on the premise that
        // `OrtNavHost`'s host header always covers the whole `SETTINGS` destination — true only
        // while entry always started here. Round 6 (WP3's `initialScreen` smoke test) found that
        // premise broken for a *sub*-screen entered directly, so the root now draws its own header
        // again — exactly one, never zero (that regression) and never two (the original bug).
        composeTestRule.setContent { OrtTheme { SettingsRootScreen(state = state(), onDrawer = {}, onOpen = {}) } }

        composeTestRule.onAllNodesWithContentDescription("Open navigation").assertCountEquals(1)
    }

    @Test
    fun `R_253 every settings-root row has a real, distinct leading icon, not a null fallback`() {
        // `iconFor` (internal, this file's own doc comment) is what `SettingsRootScreen`'s `NavRow`
        // calls for each row's `icon` — asserted directly rather than through the Compose semantics
        // tree, since `NavRow`'s icon is decorative (`contentDescription = null`, per the guide's
        // own "icon + real text, never icon-only" rule) and so carries no queryable semantics node
        // to assert against.
        val icons = SettingsScreenId.entries.associateWith(::iconFor)

        // TIER and ABOUT are the two documented fallbacks (this file's own kdoc); every other row
        // gets its own distinct glyph.
        val distinctExcludingFallbacks = icons.filterKeys {
            it != SettingsScreenId.TIER && it != SettingsScreenId.ABOUT
        }
        assert(distinctExcludingFallbacks.values.toSet().size == distinctExcludingFallbacks.size) {
            "expected every non-fallback row to carry its own distinct icon, got $icons"
        }
    }

    @Test
    @Requirement("R-451")
    fun `R_451 the Input and level row draws the board's own recorder glyph, never OrtIcons capture`() {
        // `OrtIcons.capture` is the board's 8-ray sunburst for a different concept (see this file's
        // own R-451 note on `iconFor`'s kdoc) — the CAPTURE row must draw the locally-built
        // recorder-glyph icon instead, never fall back to that unrelated shared one.
        assert(iconFor(SettingsScreenId.CAPTURE) === SettingsInputAndLevelIcon) {
            "expected the CAPTURE row's icon to be SettingsInputAndLevelIcon"
        }
        assert(iconFor(SettingsScreenId.CAPTURE) !== OrtIcons.capture) {
            "CAPTURE must no longer draw OrtIcons.capture, the wrong (sunburst) glyph"
        }
    }
}
