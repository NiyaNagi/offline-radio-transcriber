package org.ort.app.ui.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.export.ExportFileFormat
import org.ort.app.export.ExportRequest
import org.ort.app.export.ExportRequestScope
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner

/**
 * Register R-1009 (WPX): the real `Save file` producer replacing the always-`FailedState`
 * screen. `state()` mirrors `SettingsPolling.export`'s own real shape, never a board literal.
 *
 * `Save file` and the `Format` chips sit below the fold of the screen's own (outer, vertical)
 * scroll — every click on either scrolls to it first via the `export-screen-scroll` tag. A bare
 * `performScrollTo()` is not enough here: `FilterChipRow` nests its own *horizontal* scroll, so
 * `performScrollTo()`'s "nearest scrollable ancestor" rule scrolls that one, never the outer
 * vertical container the chip also needs to be vertically visible in — found by adding a debug
 * print inside the chip's own `onClick` and seeing it never fire despite `performScrollTo()`
 * "succeeding" with no exception. `performScrollToNode` against the outer container's own tag is
 * the fix, the same technique (by node, not the ambiguous `hasScrollAction()`, since two
 * scrollables exist on this screen) `SettingsDiagnosticsScreenTest`'s own `Save bundle` click uses.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsExportScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun state() = SettingsExportViewState(
        tonightOverCount = 12,
        tonightSpanLabel = "22:04 – 05:11",
        allSessionCount = 6,
        allOverCount = 214,
    )

    private fun scrollToText(text: String) {
        composeTestRule.onNodeWithTag("export-screen-scroll").performScrollToNode(hasText(text))
    }

    @Test
    fun `R_1009 Save file is enabled by default (Tonight, the initial scope)`() {
        composeTestRule.setContent {
            OrtTheme { SettingsExportScreen(state = state(), onBack = {}) }
        }
        scrollToText("Save file")
        composeTestRule.onNodeWithText("Save file").assertIsEnabled()
    }

    @Test
    fun `R_1009 selecting A range of nights disables Save file and shows the honest reason`() {
        composeTestRule.setContent {
            OrtTheme { SettingsExportScreen(state = state(), onBack = {}) }
        }
        composeTestRule.onNodeWithTag("export-scope-range").performClick()
        scrollToText("Save file")
        composeTestRule.onNodeWithText("Save file").assertIsNotEnabled()
        composeTestRule.onNodeWithText("A range of nights is not available in this build").assertExists()
    }

    @Test
    fun `R_1009 selecting Everything keeps Save file enabled, no fake unavailability`() {
        composeTestRule.setContent {
            OrtTheme { SettingsExportScreen(state = state(), onBack = {}) }
        }
        composeTestRule.onNodeWithTag("export-scope-everything").performClick()
        scrollToText("Save file")
        composeTestRule.onNodeWithText("Save file").assertIsEnabled()
        composeTestRule.onNodeWithText("Export is not available in this build").assertDoesNotExist()
    }

    @Test
    fun `R_1009 clicking Save file with the default selections requests Tonight ADIF with transcripts`() {
        var captured: ExportRequest? = null
        composeTestRule.setContent {
            OrtTheme { SettingsExportScreen(state = state(), onBack = {}, onSaveFile = { captured = it }) }
        }
        scrollToText("Save file")
        composeTestRule.onNodeWithText("Save file").performClick()
        assert(
            captured == ExportRequest(ExportRequestScope.TONIGHT, ExportFileFormat.ADIF, includeTranscripts = true),
        ) {
            "expected the default Tonight/ADIF/transcripts-on request, got $captured"
        }
    }

    @Test
    fun `R_1009 selecting Everything and CSV requests exactly that scope and format`() {
        var captured: ExportRequest? = null
        composeTestRule.setContent {
            OrtTheme { SettingsExportScreen(state = state(), onBack = {}, onSaveFile = { captured = it }) }
        }
        composeTestRule.onNodeWithTag("export-scope-everything").performClick()
        scrollToText("CSV")
        composeTestRule.onNodeWithText("CSV").performClick()
        scrollToText("Save file")
        composeTestRule.onNodeWithText("Save file").performClick()
        assert(captured?.scope == ExportRequestScope.EVERYTHING) { "expected EVERYTHING, got $captured" }
        assert(captured?.format == ExportFileFormat.CSV) { "expected CSV, got $captured" }
    }

    @Test
    fun `R_1009 unchecking Transcripts requests includeTranscripts false`() {
        var captured: ExportRequest? = null
        composeTestRule.setContent {
            OrtTheme { SettingsExportScreen(state = state(), onBack = {}, onSaveFile = { captured = it }) }
        }
        composeTestRule.onNodeWithTag("export-checkbox-transcripts").performClick()
        scrollToText("Save file")
        composeTestRule.onNodeWithText("Save file").performClick()
        assert(captured?.includeTranscripts == false) { "expected includeTranscripts=false, got $captured" }
    }

    @Test
    fun `R_1009 Digest, history and audio checkboxes are unaffected by a tap — not yet wired`() {
        composeTestRule.setContent {
            OrtTheme { SettingsExportScreen(state = state(), onBack = {}) }
        }
        composeTestRule.onNodeWithTag("export-checkbox-digest").performClick()
        composeTestRule.onNodeWithTag("export-checkbox-history").performClick()
        composeTestRule.onNodeWithTag("export-checkbox-audio").performClick()
        // Three rows, each still stating honestly that a tap changes nothing real — never
        // silently becoming checked (constitution I).
        composeTestRule.onAllNodesWithText("not yet written by this exporter").assertCountEquals(3)
    }

    @Test
    fun `R_135 the never-included promise banner renders regardless of scope`() {
        composeTestRule.setContent {
            OrtTheme { SettingsExportScreen(state = state(), onBack = {}) }
        }
        composeTestRule.onNodeWithTag("export-scope-range").performClick()
        composeTestRule.onNodeWithText(
            "Never exported, by any option: voiceprints, names you gave stations, notes, your " +
                "location, the level of any signal that would locate you.",
        ).assertExists()
    }
}
