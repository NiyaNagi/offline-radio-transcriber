package org.ort.app.ui.settings

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.ort.core.capture.CaptureMode
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * CF02 (`Settings-Capture.dc.html`, amended 2026-09-10, FR-CAP-12, `spec/e2e-capture-modes-plan.md`
 * E2-F01): the leading Capture-mode row — label, sub-line, and `Change` → CF11.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsCaptureScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun state(mode: CaptureMode, modeSubLine: String = "sub-line") = SettingsCaptureViewState(
        inputLabel = "USB Audio Device",
        inputSubLine = "verified · 48000 Hz native · linear · radio audio",
        levelLabel = "Not measured",
        levelSubLine = "no level signal published yet this session",
        levelWarnEnabled = true,
        noiseReductionEnabled = true,
        bandPassEnabled = false,
        manualFrequencyMhz = null,
        mode = mode,
        modeLabel = mode.operatorLabel,
        modeSubLine = modeSubLine,
    )

    @Test
    @Requirement("FR-CAP-12")
    fun `E2_F01 the Capture-mode row shows the real mode label and sub-line`() {
        composeTestRule.setContent {
            OrtTheme {
                SettingsCaptureScreen(
                    state = state(
                        CaptureMode.BLUETOOTH_RADIO,
                        "audio by cable · rig link over Bluetooth · a change applies at the next session",
                    ),
                    onBack = {},
                    toggles = SettingsCaptureToggleActions({}, {}, {}),
                )
            }
        }

        composeTestRule.onNodeWithTag(CAPTURE_MODE_ROW_TEST_TAG).assertExists()
        composeTestRule.onNodeWithText("Bluetooth-connected radio").assertExists()
        composeTestRule
            .onNodeWithText(
                "audio by cable · rig link over Bluetooth · a change applies at the next session",
                substring = true,
            )
            .assertExists()
    }

    @Test
    @Requirement("FR-CAP-12")
    fun `E2_F01 Change on the Capture-mode row opens CF11`() {
        var opened = false
        composeTestRule.setContent {
            OrtTheme {
                SettingsCaptureScreen(
                    state = state(CaptureMode.USB_RADIO),
                    onBack = {},
                    toggles = SettingsCaptureToggleActions({}, {}, {}),
                    onOpenModeSettings = { opened = true },
                )
            }
        }

        // Disambiguated from the Input row's own "Change" (`Device`'s trailing action) by scoping
        // to the Capture-mode row's own tagged subtree — `onNodeWithText("Change")` alone would be
        // ambiguous (two such actions render on this screen).
        composeTestRule
            .onNode(hasText("Change") and hasAnyAncestor(hasTestTag(CAPTURE_MODE_ROW_TEST_TAG)))
            .performClick()

        assert(opened) { "expected Change on the Capture-mode row to open CF11" }
    }

    @Test
    @Requirement("FR-CAP-3a")
    fun `E2_F01 the Input row names radio audio for a non-mic device`() {
        composeTestRule.setContent {
            OrtTheme {
                SettingsCaptureScreen(
                    state = state(CaptureMode.USB_RADIO),
                    onBack = {},
                    toggles = SettingsCaptureToggleActions({}, {}, {}),
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("radio audio", substring = true).assertExists()
    }
}
