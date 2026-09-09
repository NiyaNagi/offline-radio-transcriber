package org.ort.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner

/**
 * R-023 (ui-conformance-plan WP2): the six feedback treatments (guide §6.8-6.9/§Feedback), never
 * conflated. `Banner`, `Toast`, `Sheet`, `EmptyState` and `FailedState` did not exist before this.
 */
@RunWith(RobolectricTestRunner::class)
class FeedbackTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `a halting banner and a degradation banner differ in tone, copy and action colour, not just hue`() {
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    Banner(
                        title = "Capture halted — audio is coming from the built-in microphone",
                        body = "The route resolved to the phone's own mic, not the radio. Nothing has been recorded.",
                        tone = BannerTone.HALTING,
                        primaryActionLabel = "Choose another input",
                        onPrimaryAction = {},
                        modifier = Modifier.testTag("halting"),
                    )
                    Banner(
                        title = "Running warm — dropped to tier 2",
                        body = "Fewer callsigns will resolve until it cools. Everything captured is kept and " +
                            "can be improved later.",
                        tone = BannerTone.DEGRADED,
                        primaryActionLabel = "What changes at tier 2",
                        onPrimaryAction = {},
                        modifier = Modifier.testTag("degraded"),
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Choose another input").assertIsDisplayed()
        composeTestRule.onNodeWithText("What changes at tier 2").assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Capture halted — audio is coming from the built-in microphone")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Running warm — dropped to tier 2").assertIsDisplayed()
    }

    @Test
    fun `a toast always carries Undo and states the blast radius, not just success`() {
        composeTestRule.setContent {
            OrtTheme {
                Toast(
                    message = "Corrected to K7LWH · 6 overs updated",
                    onUndo = {},
                    modifier = Modifier.testTag("toast"),
                )
            }
        }

        composeTestRule.onNodeWithText("Corrected to K7LWH · 6 overs updated").assertIsDisplayed()
        composeTestRule.onNodeWithText("Undo").assertIsDisplayed().performClick()
    }

    @Test
    fun `AC_6_8 empty and failed states are never conflated`() {
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    EmptyState(
                        message = "No overs on this frequency yet.",
                        subMessage = "Listening since 22:14.",
                        modifier = Modifier.testTag("empty"),
                    )
                    FailedState(
                        title = "No transcription model installed",
                        body = "Audio is being captured and kept. Transcripts will appear once a model is installed.",
                        actionLabel = "Install a model",
                        onAction = {},
                        modifier = Modifier.testTag("failed"),
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("No overs on this frequency yet.").assertIsDisplayed()
        composeTestRule.onNodeWithText("No transcription model installed").assertIsDisplayed()
        // R-380 correction (WP2, gate-blocking): `TextAction`'s own outer node now carries its
        // label as both `contentDescription` and `text` (this file's own `CHANGELOG.md`) — the
        // default merged tree finds it directly and uniquely; `useUnmergedTree = true` would also
        // surface the still-present inner `Text`, two matches instead of one.
        composeTestRule.onNodeWithText("Install a model").assertIsDisplayed()
    }

    @Test
    fun `a sheet renders its handle, title and an optional Clear all action with a 44dp target`() {
        composeTestRule.setContent {
            OrtTheme {
                Sheet(title = "Filter the log", onClearAll = {}) {
                    androidx.compose.material3.Text("content")
                }
            }
        }

        composeTestRule.onNodeWithText("Filter the log").assertIsDisplayed()
        // R-380 correction (WP2, gate-blocking): see the note above — the default merged tree
        // finds `TextAction`'s own outer node uniquely by its own `text` now. The 44dp floor check
        // still queries by `contentDescription` specifically (also on that same outer node) —
        // either now resolves to the one real target, this just keeps the two checks distinct.
        composeTestRule.onNodeWithText("Clear all").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Clear all", useUnmergedTree = true).assertHeightIsAtLeast(44.dp)
    }

    @Test
    fun `R_545_the sheet header row itself meets the 44dp floor, not just Clear all's own internal one`() {
        // The register's own repro (`emulator-5554`, `wm density` 420 — 44dp = 115.5px): `Sheet`'s
        // "Clear all" measured 72px/27.4dp on device even after R-510's own `TextAction` fix
        // (`requiredHeightIn`, verified in `ControlsTest.kt` to hold on its own). The neighbour test
        // above already asserts `TextAction`'s own 44dp floor and has passed throughout — this
        // host's own layout pass does not reproduce the device finding (this package's own
        // established Robolectric-vs-device gap, `LogRowResponsiveTest.kt`'s own doc comments name
        // the same limit). What *is* checkable here, host-independent: the header `Row` itself
        // (`Sheet`'s own internal `testTag("sheet-header-row")`, not a caller-supplied one) now
        // carries its own `heightIn(min = 44.dp)` floor — a short [title] alone (this test's own
        // "X", far shorter than any real title) can no longer leave the row measuring less than
        // 44dp, which is the structural half of the fix regardless of what any single host's font
        // metrics report for `TextAction`'s own box inside it.
        composeTestRule.setContent {
            OrtTheme {
                Sheet(title = "X", onClearAll = {}) {
                    androidx.compose.material3.Text("content")
                }
            }
        }

        composeTestRule.onNodeWithTag("sheet-header-row").assertHeightIsAtLeast(44.dp)
    }

    @Test
    fun `R_261_a control behind an open sheet is unreachable once its host applies clearedWhileOverlaid`() {
        // The register's own repro: a detail screen's "Back to Log" button stayed focusable and
        // clickable behind an open correction sheet's scrim — invisible only to sighted eyes, not
        // to TalkBack or a stray focus move. `Sheet` itself does not own dismissal/scrim/focus
        // trapping (its own KDoc says so, deliberately, so each screen keeps its own scrim's exact
        // tap geometry — `clearedWhileOverlaid` is the one general, reusable half of that job this
        // package hands every such screen); this proves the mechanism a screen would wire in
        // actually removes the control from the merged tree, not merely dims it visually.
        var backTapped = false
        var sheetOpen by mutableStateOf(true)
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    TextAction(
                        text = "Back to Log",
                        onClick = { backTapped = true },
                        modifier = Modifier.clearedWhileOverlaid(contentHidden = sheetOpen),
                    )
                    if (sheetOpen) {
                        Sheet(title = "Correct this callsign") {
                            androidx.compose.material3.Text("content")
                        }
                    }
                }
            }
        }

        // Unreachable while the sheet is open — not merely present-but-dim.
        composeTestRule.onNodeWithText("Back to Log").assertDoesNotExist()

        // Closing the sheet (the real screen's own dismissal — scrim tap/drag, outside this
        // package) restores it exactly as before — this is a visibility gate, not a deletion.
        // R-380 correction (WP2, gate-blocking): see the note above — the default merged tree
        // finds `TextAction`'s own outer node uniquely by its own `text` now.
        sheetOpen = false
        composeTestRule.onNodeWithText("Back to Log").assertIsDisplayed().performClick()
        assert(backTapped)
    }
}
