package org.ort.app.ui.settings

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.ort.core.capture.CaptureMode
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * CF11 (`Settings-Mode.dc.html`, FR-CAP-8, FR-CAP-9, FR-CAP-12, FR-CAP-13, AC-131,
 * `spec/e2e-capture-modes-plan.md` E2-F02): the three modes as radio rows, the current one marked,
 * the live-session banner, and the "what the mode set" rows each with `Change`.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsModeScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun rows(current: CaptureMode, pending: CaptureMode? = null) = CaptureMode.entries.map { mode ->
        SettingsModeRowViewState(
            mode = mode,
            descriptionLabel = "description",
            current = mode == current,
            pending = mode == pending,
        )
    }

    private fun state(
        current: CaptureMode = CaptureMode.LOCAL_MICROPHONE,
        sessionLive: Boolean = false,
        pending: CaptureMode? = null,
    ) = SettingsModeViewState(
        rows = rows(current, pending),
        sessionLive = sessionLive,
        audioRoute = SettingsModeSetRowViewState("Audio route", "USB Audio Device · verified"),
        rigLink = SettingsModeSetRowViewState("Rig link", "TH-D75A · connected"),
    )

    @Test
    @Requirement("AC-131")
    fun `E2_F02 the current mode is marked, the other two are not`() {
        composeTestRule.setContent {
            OrtTheme {
                SettingsModeScreen(
                    state = state(current = CaptureMode.USB_RADIO),
                    onBack = {},
                    onSelectMode = {},
                    onChangeAudioRoute = {},
                    onChangeRigLink = {},
                )
            }
        }

        composeTestRule.onNodeWithText("USB-connected radio · current").assertExists()
        composeTestRule.onNodeWithText("Local microphone").assertExists()
        composeTestRule.onNodeWithText("Bluetooth-connected radio").assertExists()
    }

    @Test
    @Requirement("AC-131")
    fun `E2_F02 the amber banner shows only while a session is live`() {
        composeTestRule.setContent {
            OrtTheme {
                SettingsModeScreen(
                    state = state(sessionLive = true),
                    onBack = {},
                    onSelectMode = {},
                    onChangeAudioRoute = {},
                    onChangeRigLink = {},
                )
            }
        }

        composeTestRule.onNodeWithTag(MODE_LIVE_BANNER_TEST_TAG).assertExists()
        composeTestRule.onNodeWithText("A session is running — changes apply when it ends").assertExists()
    }

    @Test
    @Requirement("AC-131")
    fun `E2_F02 no banner while idle`() {
        composeTestRule.setContent {
            OrtTheme {
                SettingsModeScreen(
                    state = state(sessionLive = false),
                    onBack = {},
                    onSelectMode = {},
                    onChangeAudioRoute = {},
                    onChangeRigLink = {},
                )
            }
        }

        composeTestRule.onNodeWithTag(MODE_LIVE_BANNER_TEST_TAG).assertDoesNotExist()
    }

    @Test
    @Requirement("AC-131")
    fun `E2_F02 a pending mode reads pending, distinct from current`() {
        composeTestRule.setContent {
            OrtTheme {
                SettingsModeScreen(
                    state = state(
                        current = CaptureMode.LOCAL_MICROPHONE,
                        sessionLive = true,
                        pending = CaptureMode.BLUETOOTH_RADIO,
                    ),
                    onBack = {},
                    onSelectMode = {},
                    onChangeAudioRoute = {},
                    onChangeRigLink = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Local microphone · current").assertExists()
        composeTestRule.onNodeWithText("Bluetooth-connected radio · pending, applies next session").assertExists()
    }

    @Test
    @Requirement("FR-CAP-12")
    fun `R_821 no row is marked current when nothing has been chosen — never a fabricated LOCAL_MICROPHONE default`() {
        composeTestRule.setContent {
            OrtTheme {
                SettingsModeScreen(
                    state = SettingsModeViewState(
                        rows = CaptureMode.entries.map { mode ->
                            SettingsModeRowViewState(mode = mode, descriptionLabel = "description", current = false)
                        },
                        sessionLive = false,
                        audioRoute = SettingsModeSetRowViewState("Audio route", "not yet selected"),
                        rigLink = SettingsModeSetRowViewState("Rig link", "no radio configured"),
                    ),
                    onBack = {},
                    onSelectMode = {},
                    onChangeAudioRoute = {},
                    onChangeRigLink = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Local microphone · current").assertDoesNotExist()
        composeTestRule.onNodeWithText("USB-connected radio · current").assertDoesNotExist()
        composeTestRule.onNodeWithText("Bluetooth-connected radio · current").assertDoesNotExist()
        // Every row still renders its own plain (unmarked) label — the picker itself is never empty,
        // only honestly un-committed.
        composeTestRule.onNodeWithText("Local microphone").assertExists()
    }

    @Test
    @Requirement("FR-CAP-9")
    fun `E2_F02 picking a mode calls back with that mode`() {
        var picked: CaptureMode? = null
        composeTestRule.setContent {
            OrtTheme {
                SettingsModeScreen(
                    state = state(current = CaptureMode.LOCAL_MICROPHONE),
                    onBack = {},
                    onSelectMode = { picked = it },
                    onChangeAudioRoute = {},
                    onChangeRigLink = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("${MODE_ROW_TEST_TAG_PREFIX}${CaptureMode.USB_RADIO.name}").performClick()

        assert(picked == CaptureMode.USB_RADIO) { "expected a pick callback for USB_RADIO, got $picked" }
    }

    @Test
    @Requirement("FR-RIG-13")
    fun `E2_F02 the audio-route row's own Change fires onChangeAudioRoute, not the rig-link one`() {
        var audioChanged = false
        var rigChanged = false
        composeTestRule.setContent {
            OrtTheme {
                SettingsModeScreen(
                    state = state(),
                    onBack = {},
                    onSelectMode = {},
                    onChangeAudioRoute = { audioChanged = true },
                    onChangeRigLink = { rigChanged = true },
                )
            }
        }

        composeTestRule.onNodeWithText("USB Audio Device · verified", substring = true).assertExists()
        // Both "what the mode set" rows carry a `Change` action with the identical label — scoped
        // by this row's own testTag ancestor (`TextAction`'s own `clearAndSetSemantics` clears any
        // testTag applied to itself directly, so the tag lives on the row instead — the same
        // technique `SettingsCaptureScreenTest`'s own Capture-mode-row test already established).
        composeTestRule
            .onNode(hasText("Change") and hasAnyAncestor(hasTestTag(MODE_AUDIO_ROUTE_ROW_TEST_TAG)))
            .performClick()

        assert(audioChanged) { "expected the audio-route row's own Change to fire" }
        assert(!rigChanged) { "did not expect the rig-link row's own Change to fire" }
    }

    @Test
    @Requirement("FR-RIG-13")
    fun `E2_F02 the rig-link row's own Change fires onChangeRigLink, not the audio-route one`() {
        var audioChanged = false
        var rigChanged = false
        composeTestRule.setContent {
            OrtTheme {
                SettingsModeScreen(
                    state = state(),
                    onBack = {},
                    onSelectMode = {},
                    onChangeAudioRoute = { audioChanged = true },
                    onChangeRigLink = { rigChanged = true },
                )
            }
        }

        composeTestRule.onNodeWithText("TH-D75A · connected", substring = true).assertExists()
        composeTestRule
            .onNode(hasText("Change") and hasAnyAncestor(hasTestTag(MODE_RIG_LINK_ROW_TEST_TAG)))
            .performScrollTo()
            .performClick()

        assert(rigChanged) { "expected the rig-link row's own Change to fire" }
        assert(!audioChanged) { "did not expect the audio-route row's own Change to fire" }
    }
}
