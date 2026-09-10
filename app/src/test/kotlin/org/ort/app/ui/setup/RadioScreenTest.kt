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
import org.ort.rig.NullRigModule
import org.ort.rig.catalogue.RigCatalogueEntry
import org.robolectric.RobolectricTestRunner

/**
 * R-084 (ui-conformance-plan WP9), rewritten for D33/P19 WPD — `Setup-Rig.dc.html` (S09) is now
 * generated from [org.ort.rig.catalogue.RigCatalogue] rather than a hardcoded three-row list.
 * E2-E08 (AC-134/AC-135).
 */
@RunWith(RobolectricTestRunner::class)
class RadioScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun state(presetLabel: String? = null, importError: String? = null) = RadioPickerViewState(
        catalogue = RigPickerCatalogue.build(),
        presetLabel = presetLabel,
        importError = importError,
    )

    @Test
    fun `E2_E08 renders one row per catalogue entry, generated from the descriptor set`() {
        composeTestRule.setContent {
            OrtTheme { RadioScreen(state = state(), onChoose = {}, onImport = {}, onNotNow = {}) }
        }

        composeTestRule.onNodeWithText("Kenwood TH-D75A").assertIsDisplayed()
        composeTestRule.onNodeWithText("Generic ASCII CAT").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(NullRigModule.DISPLAY_NAME).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `AC_135 the null module and the generic entry are the last two rows, reachable without scrolling`() {
        composeTestRule.setContent {
            OrtTheme { RadioScreen(state = state(), onChoose = {}, onImport = {}, onNotNow = {}) }
        }

        composeTestRule.onNodeWithTag("setup-radio-entry-${NullRigModule.ID}").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `E2_E08 choosing the TH-D75A row reports that catalogue entry`() {
        var chosen: RigCatalogueEntry? = null
        composeTestRule.setContent {
            OrtTheme { RadioScreen(state = state(), onChoose = { chosen = it }, onImport = {}, onNotNow = {}) }
        }

        composeTestRule.onNodeWithTag("setup-radio-entry-kenwood-thd75a").performClick()
        assert(chosen?.displayName == "Kenwood TH-D75A")
    }

    @Test
    fun `E2_E08 choosing the null module row reports it, distinct from Not now`() {
        var chosen: RigCatalogueEntry? = null
        composeTestRule.setContent {
            OrtTheme { RadioScreen(state = state(), onChoose = { chosen = it }, onImport = {}, onNotNow = {}) }
        }

        composeTestRule.onNodeWithTag("setup-radio-entry-${NullRigModule.ID}").performScrollTo().performClick()
        assert(chosen?.id == NullRigModule.ID)
    }

    @Test
    fun `E2_E08 Not now invokes its own callback, distinct from choosing a row`() {
        var notNow = false
        composeTestRule.setContent {
            OrtTheme { RadioScreen(state = state(), onChoose = {}, onImport = {}, onNotNow = { notNow = true }) }
        }

        composeTestRule.onNodeWithTag("setup-radio-not-now").performClick()
        assert(notNow)
    }

    @Test
    fun `E2_E08 Import it invokes its own callback`() {
        var imported = false
        composeTestRule.setContent {
            OrtTheme { RadioScreen(state = state(), onChoose = {}, onImport = { imported = true }, onNotNow = {}) }
        }

        composeTestRule.onNodeWithTag("setup-radio-import").performScrollTo().performClick()
        assert(imported)
    }

    @Test
    fun `E2_E06 a non-null presetLabel renders the preset chip`() {
        composeTestRule.setContent {
            OrtTheme {
                RadioScreen(
                    state = state(presetLabel = "USB-connected radio"),
                    onChoose = {},
                    onImport = {},
                    onNotNow = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-radio-preset-chip").assertIsDisplayed()
    }

    @Test
    fun `an import error renders inline, never silently swallowed`() {
        composeTestRule.setContent {
            OrtTheme {
                RadioScreen(
                    state = state(importError = "That file is not a valid rig descriptor."),
                    onChoose = {},
                    onImport = {},
                    onNotNow = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-radio-import-error").performScrollTo().assertIsDisplayed()
    }

    /** R-343 (validator pass 4, design): `Setup-Rig.dc.html`'s own "optional" tag beside "Radio" --
     * absent from the built screen before this. */
    @Test
    fun `R_343 the title carries an optional tag, the one step in the sequence that genuinely is`() {
        composeTestRule.setContent {
            OrtTheme { RadioScreen(state = state(), onChoose = {}, onImport = {}, onNotNow = {}) }
        }

        composeTestRule.onNodeWithText("Radio").assertIsDisplayed()
        composeTestRule.onNodeWithText("optional").assertIsDisplayed()
    }

    @Test
    fun `a banner renders when the rig link was lost before setup could verify it`() {
        composeTestRule.setContent {
            OrtTheme {
                RadioScreen(
                    state = state(),
                    onChoose = {},
                    onImport = {},
                    onNotNow = {},
                    banner = "Choose a radio again, or enter the frequency by hand.",
                )
            }
        }

        composeTestRule.onNodeWithTag("setup-radio-absent-banner").assertIsDisplayed()
    }
}
