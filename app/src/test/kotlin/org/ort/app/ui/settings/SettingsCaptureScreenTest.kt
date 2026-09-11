package org.ort.app.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
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
    @Requirement("FR-CAP-12")
    fun `R_821 the Capture-mode row reads Not set when nothing has been chosen, never a fabricated default`() {
        composeTestRule.setContent {
            OrtTheme {
                SettingsCaptureScreen(
                    state = SettingsCaptureViewState(
                        inputLabel = "No input selected",
                        inputSubLine = "select an input to capture",
                        levelLabel = "Not measured",
                        levelSubLine = "no level signal published yet this session",
                        levelWarnEnabled = true,
                        noiseReductionEnabled = true,
                        bandPassEnabled = false,
                        manualFrequencyMhz = null,
                        mode = null,
                        modeLabel = "Not set",
                        modeSubLine = "pick a mode to start capturing",
                    ),
                    onBack = {},
                    toggles = SettingsCaptureToggleActions({}, {}, {}),
                )
            }
        }

        composeTestRule.onNodeWithText("Not set").assertExists()
        composeTestRule.onNodeWithText("Local microphone").assertDoesNotExist()
    }

    // Register R-957 (root cause, WPI's host comparison on 5558/5556): the real "no dead band"
    // regression test now lives in `OrtNavHostDestinationDispatchTest.kt`
    // (`R_957 CF02s scroll viewport ends exactly at the live bars top...`), driving the real
    // `OrtNavHost`/`NavHostBody` this screen is actually reached through — that is the one place
    // the real bug (a redundant `padding(bottom = liveBarHeight)` on `NavHostBody`'s own content
    // box, on top of the natural `Column` sibling spacing it already gets) could ever be caught.
    // An isolated wrapper here that hand-adds its own `padding(bottom = ...)` to simulate that bug
    // no longer reflects `NavHostBody`'s own real shape once the redundant padding was removed
    // from it, so it is not repeated here — see the two `R_957 the closing paragraph...` tests
    // below for what *this* screen's own file still owns: that removing its own now-redundant
    // static clearance never made the paragraph unreachable.

    @Test
    @Requirement("FR-CAP-12")
    fun `R_957 the closing paragraph is still reachable when the host also reserves the live bars real height`() {
        // Register R-957 (addendum to R-933, reviewer B4): the closing paragraph reads cut after
        // its first line with a *blank gap* above the live bar, live sessions only. R-826's own
        // test above never modelled `NavHostBody`'s own real shape faithfully: that host's
        // `NavHostBody` wraps every destination's content in
        // `Box(Modifier.weight(1f).padding(top = clearance, bottom = liveBarHeight))` — the real,
        // measured live-bar height is *already* reserved as real padding on the ancestor box, not
        // merely occupied by a sibling below it. This screen's own R-826 fix then adds a *second*,
        // static 44dp bottom padding *inside* its own scroll — reserving the live bar's height
        // twice. Reproduced here by adding the identical real padding to the weighted box the R-826
        // test above only ever gave a plain sibling.
        composeTestRule.setContent {
            OrtTheme {
                Column(modifier = Modifier.width(390.dp).height(500.dp)) {
                    Box(modifier = Modifier.weight(1f).padding(bottom = 44.dp)) {
                        SettingsCaptureScreen(
                            state = state(CaptureMode.LOCAL_MICROPHONE),
                            onBack = {},
                            toggles = SettingsCaptureToggleActions({}, {}, {}),
                        )
                    }
                    Box(modifier = Modifier.height(44.dp).testTag("fake-live-bar"))
                }
            }
        }

        val scrollable = composeTestRule.onNode(hasScrollAction())
        scrollable.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, Float.MAX_VALUE) }
        composeTestRule.waitForIdle()

        composeTestRule
            .onNodeWithText(
                "it never adjusts gain on the way in, so what is retained is what the radio put out.",
                substring = true,
            )
            .assertExists()
    }

    @Test
    @Requirement("FR-CAP-12")
    fun `R_957 the closing paragraph stays reachable when the live bars real height arrives after the scroll`() {
        // Register R-957: `NavHostBody`'s own `liveBarHeight` starts at `0.dp` (`remember {
        // mutableStateOf(0.dp) }`) and only becomes the real measured value once `LiveBar` itself
        // reports its size via `onGloballyPositioned`, on a *later* frame/recomposition — exactly
        // the timing trap that file's own R-262 doc comment already names. If a caller (the tour's
        // own capture step) scrolls to the end *before* that real height lands, then the ancestor
        // box's own bottom padding grows afterwards with no further scroll performed, this proves
        // whether the scrollable's own max-scroll value reactively grows to keep the tail
        // reachable, or whether it stays stuck at whatever it computed against the taller,
        // not-yet-shrunk viewport.
        var liveBarHeight by mutableStateOf(0.dp)
        composeTestRule.setContent {
            OrtTheme {
                Column(modifier = Modifier.width(390.dp).height(500.dp)) {
                    Box(modifier = Modifier.weight(1f).padding(bottom = liveBarHeight)) {
                        SettingsCaptureScreen(
                            state = state(CaptureMode.LOCAL_MICROPHONE),
                            onBack = {},
                            toggles = SettingsCaptureToggleActions({}, {}, {}),
                        )
                    }
                    Box(modifier = Modifier.height(44.dp).testTag("fake-live-bar"))
                }
            }
        }

        val scrollable = composeTestRule.onNode(hasScrollAction())
        scrollable.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, Float.MAX_VALUE) }
        composeTestRule.waitForIdle()

        // The live bar's own real height "arrives" only now — after the first (and, on a real
        // device screenshot, only) scroll-to-end.
        liveBarHeight = 44.dp
        composeTestRule.waitForIdle()

        composeTestRule
            .onNodeWithText(
                "it never adjusts gain on the way in, so what is retained is what the radio put out.",
                substring = true,
            )
            .assertExists()
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
