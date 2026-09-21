package org.ort.app.ui.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * P27 (Wave G, `spec/build-plan.md`): NFR-6d, AC-167 — the third-party licence notices screen lists
 * every named component, each drills into its own full, real text (read from the bundled app asset,
 * never a placeholder), and `Back` from a notice returns to the list, never past it.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsLicensesScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    @Requirement("AC-167")
    fun `AC_167 the screen lists Gemma, Whisper, sherpa-onnx, ONNX Runtime and Silero`() {
        composeTestRule.setContent { OrtTheme { SettingsLicensesScreen(context = context, onBack = {}) } }

        composeTestRule.onNodeWithContentDescription("Gemma 3 1B. Gemma Terms of Use").assertExists()
        composeTestRule.onNodeWithContentDescription("Whisper tiny.en. MIT License").assertExists()
        composeTestRule.onNodeWithContentDescription("sherpa-onnx. Apache License 2.0").assertExists()
        composeTestRule.onNodeWithContentDescription("ONNX Runtime. MIT License").assertExists()
        composeTestRule.onNodeWithContentDescription("Silero VAD. MIT License").assertExists()
    }

    @Test
    @Requirement("AC-167")
    fun `AC_167 tapping Gemma opens its real notice text, the Gemma Terms of Use sentence`() {
        composeTestRule.setContent { OrtTheme { SettingsLicensesScreen(context = context, onBack = {}) } }

        composeTestRule.onNodeWithContentDescription("Gemma 3 1B. Gemma Terms of Use").performClick()

        composeTestRule.onNodeWithText(
            "Gemma is provided under and subject to the Gemma Terms of Use found at",
            substring = true,
        ).assertExists()
    }

    @Test
    @Requirement("NFR-6d")
    fun `D55 tapping Gemma also renders the full Terms of Use and the Prohibited Use Policy, not just a link`() {
        composeTestRule.setContent { OrtTheme { SettingsLicensesScreen(context = context, onBack = {}) } }

        composeTestRule.onNodeWithContentDescription("Gemma 3 1B. Gemma Terms of Use").performClick()

        composeTestRule.onNodeWithText("GEMMA TERMS OF USE", substring = true).assertExists()
        composeTestRule.onNodeWithText("GEMMA PROHIBITED USE POLICY", substring = true).assertExists()
        composeTestRule.onNodeWithText("Generate Sexually Explicit Content", substring = true).assertExists()
    }

    @Test
    @Requirement("AC-167")
    fun `AC_167 tapping sherpa-onnx opens the real Apache License 2 dot 0 text`() {
        composeTestRule.setContent { OrtTheme { SettingsLicensesScreen(context = context, onBack = {}) } }

        composeTestRule.onNodeWithContentDescription("sherpa-onnx. Apache License 2.0").performClick()

        composeTestRule.onNodeWithText("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION", substring = true)
            .assertExists()
    }

    @Test
    @Requirement("AC-167")
    fun `AC_167 Back from a notice returns to the list, not past this screen`() {
        var backCount = 0
        composeTestRule.setContent {
            OrtTheme { SettingsLicensesScreen(context = context, onBack = { backCount++ }) }
        }

        composeTestRule.onNodeWithContentDescription("Whisper tiny.en. MIT License").performClick()
        composeTestRule.onNodeWithContentDescription("Back to Licences").performClick()

        // Landed back on the list (its own title is visible again) — `onBack` (this screen's own
        // exit, back to Settings) must not have fired from the notice's own inner Back.
        composeTestRule.onNodeWithText("Third-party licences").assertExists()
        assert(backCount == 0) {
            "expected the notice's own Back to stay on this screen, onBack fired $backCount time(s)"
        }
    }

    @Test
    @Requirement("AC-167")
    fun `AC_167 Back from the list itself fires the real onBack callback`() {
        var backCount = 0
        composeTestRule.setContent {
            OrtTheme { SettingsLicensesScreen(context = context, onBack = { backCount++ }) }
        }

        composeTestRule.onNodeWithContentDescription("Back to Settings").performClick()

        assert(backCount == 1) { "expected onBack to fire exactly once, got $backCount" }
    }
}
