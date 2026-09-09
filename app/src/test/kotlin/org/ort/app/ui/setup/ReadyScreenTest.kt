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

    // --- R-265/R-342 (validator findings, halt): each row is one real, focusable semantics node --

    /**
     * R-342 (validator pass 4, halt): the previous fix here relied on a lone
     * `Modifier.semantics { contentDescription = ... }` on the trailing status `Text` merging up
     * into [org.ort.app.ui.components.KeyValueRow]'s own native R-265 merge boundary — every
     * Robolectric test for it passed, then a real device dump
     * (`setup-verified/S12-done-pass4.png`) showed `verified` as its own separate node anyway.
     * [ReadySetupRow] no longer relies on that merge seeing [ReadyRow.statusText] at all:
     * `Modifier.clearAndSetSemantics` on the row's own outer node replaces its whole subtree's
     * accessibility surface outright with [readyRowFactsDescription] — re-dumped and confirmed on a
     * real device after this change (see [ReadySetupRow]'s own doc comment for the full account).
     */
    @Test
    fun `R_342 a verified row announces label, value and status as one real node`() {
        val row = ReadyRow("Input", "USB Audio Device", ok = true, statusText = "verified", actionLabel = null)
        composeTestRule.setContent {
            OrtTheme { ReadyScreen(state = ReadyViewState(listOf(row)), onStartCapture = {}) }
        }

        val node = composeTestRule.onNodeWithContentDescription("Input", substring = true)
        node.assertIsDisplayed()
        node.assertContentDescriptionEquals(readyRowFactsDescription(row))
    }

    /**
     * R-361 (validator pass 5, halt): this row shape is the one R-342's own first fix left on
     * [org.ort.app.ui.components.KeyValueRow]'s native merge, reasoning it was already correct — a
     * real device dump proved otherwise (the *focusable* node had an **empty** description, the
     * real text sat on a separate non-focusable child at the same bounds, so TalkBack announced
     * nothing landing on the only reachable stop). [ReadySetupRow] now gives this row the identical
     * `clearAndSetSemantics` treatment every row gets, action or not — this assertion, like R-342's
     * own equivalent note, cannot itself prove the *old* shape wrong (Robolectric's own semantics
     * query showed a correct merged description for it too, same as it did before R-342 was even
     * found); the real proof is the on-device dump this package's own report quotes. Unlike
     * [statusText][ReadyRow.statusText] above, [ReadyRow.actionLabel] ("Fix"/"Install", via
     * [org.ort.app.ui.components.TextAction]) is never folded into a row's own announcement — it is
     * always its own separate, real button stop, proven by the second assertion below.
     */
    @Test
    fun `R_361 an amber row's focusable node announces label and value, Fix is its own real button`() {
        val row = ReadyRow("Overnight", "Battery exemption skipped", ok = false, statusText = null, actionLabel = "Fix")
        composeTestRule.setContent {
            OrtTheme { ReadyScreen(state = ReadyViewState(listOf(row)), onStartCapture = {}) }
        }

        composeTestRule.onNodeWithContentDescription("Overnight", substring = true)
            .assertContentDescriptionEquals(readyRowFactsDescription(row))
        composeTestRule.onNodeWithText("Fix").assertHasClickAction()
    }

    @Test
    fun `R_265 every row is a real TalkBack traversal stop, not merely merged text`() {
        val rows = listOf(ReadyRow("Input", "USB Audio Device", ok = true, statusText = "verified", actionLabel = null))
        composeTestRule.setContent {
            OrtTheme { ReadyScreen(state = ReadyViewState(rows), onStartCapture = {}) }
        }

        val config = composeTestRule.onNodeWithContentDescription("Input", substring = true)
            .fetchSemanticsNode().config
        assertTrue(
            "a row with no focus action is not a real accessibility traversal stop",
            config.getOrNull(SemanticsActions.RequestFocus) != null,
        )
    }

    @Test
    fun `R_265 readyRowFactsDescription omits whichever of statusText or actionLabel is absent`() {
        val neither = ReadyRow(
            "Radio",
            "No radio · frequency by hand",
            ok = true,
            statusText = null,
            actionLabel = null,
        )
        assert(readyRowFactsDescription(neither) == "Radio, No radio · frequency by hand")
    }

    // --- R-285/R-342: the Radio row is the one place status and action coexist --------------------

    /**
     * R-342's own dual-shape fix ([ReadySetupRow]'s own doc comment, third bullet): the facts
     * ("Radio, TH-D75A · 2 bands, verified") are wrapped in their own `clearAndSetSemantics`, and
     * `Change` renders as a genuine sibling *outside* that boundary — so `Change` is reachable and
     * clickable in its own right, the same as [TextAction] always is, never folded into the row's
     * own announcement.
     */
    @Test
    fun `R_342 a verified radio row announces facts as one node, Change as its own separate button`() {
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
            .assertContentDescriptionEquals(readyRowFactsDescription(row))
        composeTestRule.onNodeWithText("Change").assertHasClickAction().performClick()
        assert(changed)
    }
}
