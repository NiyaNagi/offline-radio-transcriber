package org.ort.app.ui.setup

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner

/** R-080..R-084 (ui-conformance-plan WP9) — `Setup-Done.dc.html` (S12). */
@RunWith(RobolectricTestRunner::class)
class ReadyScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `R_080 Start capture invokes its callback`() {
        var started = false
        val rows = listOf(ReadyRow("Input", "USB Audio Device", ok = true, statusText = "verified", actionLabel = null))
        composeTestRule.setContent {
            OrtTheme { ReadyScreen(state = ReadyViewState(rows), onStartCapture = { started = true }) }
        }

        composeTestRule.onNodeWithTag("setup-ready-start-capture").performClick()
        assert(started)
    }

    @Test
    fun `R_080 an amber row's Fix action invokes onAction, not onStartCapture`() {
        var fixed = false
        val rows = listOf(
            ReadyRow(
                "Overnight",
                "Battery exemption skipped",
                ok = false,
                statusText = null,
                actionLabel = "Fix",
                onAction = { fixed = true },
            ),
        )
        composeTestRule.setContent {
            OrtTheme { ReadyScreen(state = ReadyViewState(rows), onStartCapture = {}) }
        }

        composeTestRule.onNodeWithTag("setup-ready-row-overnight").assertIsDisplayed()
        composeTestRule.onNodeWithText("Fix").performClick()
        assert(fixed)
    }

    /**
     * R-226 (validator pass 2): at font scale 2.0 a fixed 82dp label column truncated "Overnight"
     * to "Overni" and collided with the leading marker dot (`setup/S12-ready-pass2@2x.png`). Now
     * built on WP2's `KeyValueRow`, whose label column has no fixed ceiling (only a 96dp floor —
     * that component's own R-152 fix), so the full label always renders and the value column
     * never starts to its left.
     *
     * R-265 (validator finding, halt) merged the whole row into one accessibility node after this
     * test was first written, so `onNodeWithText(...)`'s default *merged* tree now returns the same
     * row node for both queries below (their bounds would trivially be equal, proving nothing) —
     * `useUnmergedTree = true` reaches the individual, pre-merge `Text` nodes this test actually
     * needs to compare, exactly as `R-265`'s own new test above deliberately does *not* (accessible
     * TalkBack behaviour is best proven against the merged tree; layout geometry needs the raw one).
     */
    @Test
    fun `R_226 the full label renders and never collides with the value at font scale 2_0`() {
        val rows = listOf(
            ReadyRow("Overnight", "Battery exemption skipped", ok = false, statusText = null, actionLabel = "Fix"),
        )
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrtTheme { ReadyScreen(state = ReadyViewState(rows), onStartCapture = {}) }
            }
        }

        // The full label, not "Overni" -- onNodeWithText requires an exact match by default, so
        // this alone would fail to even find a node if the label were ever truncated in the tree.
        composeTestRule.onNodeWithText("Overnight", useUnmergedTree = true).assertIsDisplayed()
        val labelBounds = composeTestRule.onNodeWithText("Overnight", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val valueBounds = composeTestRule
            .onNodeWithText("Battery exemption skipped", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assert(valueBounds.left >= labelBounds.right) {
            "expected the value column to start at or after the label's right edge, got label=$labelBounds " +
                "value=$valueBounds"
        }
    }

    // --- R-265 (validator finding, halt): each row is one merged, focusable semantics node -----

    /**
     * WP2 made [org.ort.app.ui.components.KeyValueRow] itself the merge boundary/focus stop
     * (`Rows.kt`'s own `R_265_key_value_row_is_a_traversal_stop`, which reproduces "Input, USB
     * Audio Device, verified" by passing the status through `subLine`) — this row's own wrapper
     * (`Modifier.focusable()`/`Modifier.semantics(mergeDescendants = true)`) is dropped now that it
     * would only nest a redundant second merge boundary inside `KeyValueRow`'s own (confirmed by
     * running it: the two overlapping `contentDescription` entries double-announced the row).
     *
     * `Setup-Done.dc.html` places "verified"/`Fix` as a *trailing* marker beside the value, not a
     * `subLine` beneath it (checked against the board before writing this), so this screen keeps
     * using [KeyValueRow]'s `trailingMarker` slot rather than misplace the status into `subLine`
     * just to reach [KeyValueRow]'s own explicit `contentDescription` (`Rows.kt`, built from
     * `key`/`value`/`subLine` only — `trailingMarker` never reaches it on its own). Instead, each
     * `trailingMarker` composable ([ReadyScreen]'s own code) carries a lone, non-merging
     * `Modifier.semantics { contentDescription = ... }` of its own — not a second merge boundary,
     * so it is swallowed into [KeyValueRow]'s existing merge as one further list entry rather than
     * colliding with it, giving exactly [readyRowDescription]'s string with nothing restated. An
     * [ReadyRow.actionLabel] row (below) does *not* get this treatment — see that test's own doc.
     */
    @Test
    fun `R_265 a verified row announces label, value and status as one merged node`() {
        val row = ReadyRow("Input", "USB Audio Device", ok = true, statusText = "verified", actionLabel = null)
        composeTestRule.setContent {
            OrtTheme { ReadyScreen(state = ReadyViewState(listOf(row)), onStartCapture = {}) }
        }

        val node = composeTestRule.onNodeWithContentDescription("Input", substring = true)
        node.assertIsDisplayed()
        // KeyValueRow's own explicit contentDescription ("Input, USB Audio Device") and this row's
        // own lone contentDescription on the trailingMarker ("verified") are two separate merged
        // list entries, not one joined string -- assertContentDescriptionEquals compares the list
        // element-by-element, so both parts are named here (their concatenation, joined by ", ", is
        // exactly readyRowDescription(row) -- what TalkBack actually reads aloud).
        node.assertContentDescriptionEquals("${row.label}, ${row.value}", row.statusText!!)
    }

    /**
     * Unlike [statusText][ReadyRow.statusText] above, [ReadyRow.actionLabel] ("Fix"/"Install", via
     * [org.ort.app.ui.components.TextAction]) does *not* fold into the row's own announcement —
     * confirmed by running it: `TextAction`'s own `Modifier.clickable` +
     * `Modifier.semantics(mergeDescendants = true) {}` (`Rows.kt`) makes it a genuine merge boundary
     * of its own, which stays a distinct child semantics node inside [KeyValueRow]'s merge rather
     * than being absorbed by it. That is the *correct* shape (this screen's class doc explains why:
     * a row's own click-less announcement should not silently also carry a button's click action) —
     * the row announces facts only, and "Fix" is separately reachable and actionable in its own
     * right, proven by the second assertion below rather than assumed.
     */
    @Test
    fun `R_265 an amber row announces label and value, Fix is its own real button`() {
        val row = ReadyRow("Overnight", "Battery exemption skipped", ok = false, statusText = null, actionLabel = "Fix")
        composeTestRule.setContent {
            OrtTheme { ReadyScreen(state = ReadyViewState(listOf(row)), onStartCapture = {}) }
        }

        composeTestRule.onNodeWithContentDescription("Overnight", substring = true)
            .assertContentDescriptionEquals("${row.label}, ${row.value}")
        composeTestRule.onNodeWithText("Fix").assertHasClickAction()
    }

    @Test
    fun `R_265 every row is a real TalkBack traversal stop, not merely merged text`() {
        val rows = listOf(ReadyRow("Input", "USB Audio Device", ok = true, statusText = "verified", actionLabel = null))
        composeTestRule.setContent {
            OrtTheme { ReadyScreen(state = ReadyViewState(rows), onStartCapture = {}) }
        }

        // WP2 made KeyValueRow itself the merge boundary/focus stop (its own R_265 test,
        // RowsTest.kt) -- the real, native node this row now relies on is KeyValueRow's own, found
        // here by content description, not this screen's own outer `Row`/testTag (which carries no
        // semantics of its own any more).
        val config = composeTestRule.onNodeWithContentDescription("Input", substring = true)
            .fetchSemanticsNode().config
        assertTrue(
            "a row with no focus action is not a real accessibility traversal stop",
            config.getOrNull(SemanticsActions.RequestFocus) != null,
        )
    }

    @Test
    fun `R_265 readyRowDescription omits whichever of statusText or actionLabel is absent`() {
        val neither = ReadyRow(
            "Radio",
            "No radio · frequency by hand",
            ok = true,
            statusText = null,
            actionLabel = null,
        )
        assert(readyRowDescription(neither) == "Radio, No radio · frequency by hand")
    }

    // --- R-285 (validator pass 3): the Radio row is the one place status and action coexist ------

    @Test
    fun `R_285 a verified radio row shows both verified and Change, and Change invokes onAction`() {
        var changed = false
        val row = ReadyRow(
            "Radio",
            "TH-D75A · 2 bands",
            ok = true,
            statusText = "verified",
            actionLabel = "Change",
            onAction = { changed = true },
        )
        composeTestRule.setContent {
            OrtTheme { ReadyScreen(state = ReadyViewState(listOf(row)), onStartCapture = {}) }
        }

        composeTestRule.onNodeWithContentDescription("Radio", substring = true)
            .assertContentDescriptionEquals("${row.label}, ${row.value}", row.statusText!!)
        composeTestRule.onNodeWithText("Change").assertHasClickAction().performClick()
        assert(changed)
    }
}
