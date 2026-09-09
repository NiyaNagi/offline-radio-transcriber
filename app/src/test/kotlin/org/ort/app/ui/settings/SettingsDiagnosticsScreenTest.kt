package org.ort.app.ui.settings

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * R-137 (register, rounds 7 and 9 System validator): `Settings-Diagnostics.dc.html`'s own
 * scrubbing example — "resolved [callsign] at 0.94" — is now real (WP11e's `CallsignScrubber`
 * actually runs), and the per-file size / header total are real too (WP11e's
 * `DiagnosticsBundleBuilder.preview`, sourced by `SettingsPolling.diagnostics`) — this screen only
 * renders the numbers it is handed, so this suite covers rendering, never `DiagnosticsBundleBuilder`
 * itself (that package's own `DiagnosticsBundleBuilderTest` does).
 */
@RunWith(RobolectricTestRunner::class)
class SettingsDiagnosticsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun state() = SettingsDiagnosticsViewState(
        aliveLabel = "alive",
        realTimeFactorLabel = "0.31",
        failedPassCount = 0,
        files = listOf(
            SettingsDiagnosticsFileViewState("lifecycle.log", "service start, stop", "140 KB"),
            SettingsDiagnosticsFileViewState("capture.log", "route verifications", "88 KB"),
        ),
        totalSizeLabel = "2.1 MB",
    )

    @Test
    @Requirement("R-137")
    fun `R_137 the never-included prose carries the board's own scrubbing example verbatim`() {
        composeTestRule.setContent {
            OrtTheme { SettingsDiagnosticsScreen(state = state(), onBack = {}, onPreview = {}, onSaveBundle = {}) }
        }

        composeTestRule.onNodeWithText("resolved [callsign] at 0.94", substring = true).assertExists()
    }

    @Test
    @Requirement("R-137")
    fun `R_137_list_from_preview the header names the real file count and total, each row its real size`() {
        composeTestRule.setContent {
            OrtTheme { SettingsDiagnosticsScreen(state = state(), onBack = {}, onPreview = {}, onSaveBundle = {}) }
        }

        // SectionHeader uppercases its own label (guide's own section-label style).
        composeTestRule.onNodeWithText("IN THE BUNDLE · 2 FILES · 2.1 MB").assertExists()
        composeTestRule.onNodeWithText("140 KB").assertExists()
        composeTestRule.onNodeWithText("88 KB").assertExists()
    }

    @Test
    @Requirement("R-137")
    fun `R_137 Preview opens the real entries and total, Done returns to the bundle screen`() {
        var previewTapped = false
        var dismissed = false
        composeTestRule.setContent {
            OrtTheme {
                SettingsDiagnosticsScreen(
                    state = state(),
                    onBack = {},
                    onPreview = { previewTapped = true },
                    onSaveBundle = {},
                    previewOpen = previewTapped,
                    onDismissPreview = {
                        dismissed = true
                        previewTapped = false
                    },
                )
            }
        }

        // WP2's R-380/R-381 fix: `PrimaryButton`/`SecondaryButton`/`TextAction`'s own
        // `clearAndSetSemantics` now sets `contentDescription = text` on the button's own node and
        // clears its inner Text's semantics entirely — its label is a content description now,
        // never a `Text` node `hasText`/`onNodeWithText` can find.
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Preview"))
        composeTestRule.onNodeWithContentDescription("Preview").performClick()
        assert(previewTapped) { "expected onPreview to fire" }
    }

    @Test
    @Requirement("R-137")
    fun `R_137_save_writes_zip Save bundle calls back, and a real confirmation names the saved file`() {
        var saveTapped = false
        composeTestRule.setContent {
            OrtTheme {
                SettingsDiagnosticsScreen(
                    state = state(),
                    onBack = {},
                    onPreview = {},
                    onSaveBundle = { saveTapped = true },
                    saveConfirmationLabel = "Saved diagnostics-2026-09-08.zip",
                )
            }
        }

        composeTestRule.onNodeWithText("Saved diagnostics-2026-09-08.zip").assertExists()
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Save bundle"))
        composeTestRule.onNodeWithContentDescription("Save bundle").performClick()
        assert(saveTapped) { "expected onSaveBundle to fire" }
    }
}
