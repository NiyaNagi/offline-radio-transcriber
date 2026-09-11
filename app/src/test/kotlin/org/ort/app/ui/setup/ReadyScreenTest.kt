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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

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

    // --- R-812 (reviewer finding, halt): the footer's amber-count sentence is generated, never --
    // --- a fabricated "two" -----------------------------------------------------------------------

    private fun row(ok: Boolean) = ReadyRow("x", "y", ok = ok, statusText = null, actionLabel = null)

    @Test
    fun `R_812 no amber rows drops the second sentence entirely`() {
        val text = readyFooterText(listOf(row(true), row(true)))

        assert(
            text == "Every model this app can use shipped with it and was verified against " +
                "its checksum on first launch — nothing was downloaded.",
        ) { "got '$text'" }
    }

    @Test
    fun `R_812 exactly one amber row reads the singular sentence`() {
        val text = readyFooterText(listOf(row(true), row(false)))

        assert(text.endsWith("The amber item is worth fixing before an overnight run.")) { "got '$text'" }
    }

    @Test
    fun `R_812 N amber rows names the real count, never a hardcoded two`() {
        val text = readyFooterText(listOf(row(false), row(false), row(false), row(true)))

        assert(text.endsWith("The 3 amber items are worth fixing before an overnight run.")) { "got '$text'" }
    }

    // --- R-866 (register, validator V9): S12's amber Install action must meet the 44dp floor -----

    private val modelsRowNotInstalled = ReadyRow(
        "Models",
        "No transcription model yet",
        ok = false,
        statusText = null,
        actionLabel = "Install",
    )

    /**
     * R-866: validator V9 measured the real device's `Install` action at 95px/34.3dp, under the
     * 44dp floor every other S12 action met (`results/ui-audit/validation/V9/assets-bundled/S12*`).
     * Not reproduced here under any Robolectric configuration tried — an isolated render, a
     * real-device-width (`360.dp`) render, and a forced multi-line value all measured `Install` at
     * exactly 44px/44dp under `@GraphicsMode(NATIVE)` — but `ReadySetupRow`'s outer row now also
     * carries an explicit `requiredHeightIn(min = 44.dp)`, first in its own modifier chain
     * (`ReadySetupRow`'s own doc comment has the account), as a defensive floor on the whole row's
     * clickable/visual band in addition to [TextAction]'s existing one, matching the pattern every
     * other S12 action already meets. This test proves that floor holds for the exact `Install` row
     * shape, at both 1.0 and 2.0 font scale, wrapped value included.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `R_866 Install meets the 44dp floor at font scale 1_0`() {
        composeTestRule.setContent {
            OrtTheme { ReadyScreen(state = ReadyViewState(listOf(modelsRowNotInstalled)), onStartCapture = {}) }
        }
        val heightDp = composeTestRule.onNodeWithText("Install").fetchSemanticsNode().size.height
        assert(heightDp >= 44) { "expected Install >= 44dp at scale 1.0 (density 1.0px/dp here), was ${heightDp}px" }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `R_866 Install meets the 44dp floor at font scale 2_0, value wrapped to several lines`() {
        val longValue = "No transcription model yet at all here, this is a very long value line " +
            "that must wrap onto several lines regardless of the available width in this test"
        val row = modelsRowNotInstalled.copy(value = longValue)
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrtTheme { ReadyScreen(state = ReadyViewState(listOf(row)), onStartCapture = {}) }
            }
        }
        val heightDp = composeTestRule.onNodeWithText("Install").fetchSemanticsNode().size.height
        assert(heightDp >= 44) { "expected Install >= 44dp at scale 2.0 (density 1.0px/dp here), was ${heightDp}px" }
    }

    // --- R-882 (register, validator V11, halt): every fact row's value stays a real, wrapping ------
    // --- column at font scale 2.0 -- never squeezed to one letter per line -------------------------

    /**
     * The real-device shape from `results/ui-audit/validation/V11/rig-bt-connected/
     * S11-radio-verified.png`+`.xml` -- the one row carrying both [ReadyRow.statusText] ("verified")
     * and [ReadyRow.actionLabel] ("Change") together ([radioRow]'s own R-285 doc comment), the
     * combination that collapsed the label to invisible and the value to one letter per line at
     * font scale 2.0. [ReadyFactColumns] (this file's own doc comment has the full account) is
     * asserted here three ways: the label is still on screen at its full text, the value node is
     * wider than it is tall (a one-letter-per-line collapse is always taller than wide), and the
     * value node's bounds sit fully inside its row's own bounds.
     *
     * `@Config(qualifiers = "w360dp-h800dp-xhdpi")`: Robolectric's own un-configured default screen
     * (a bare `createComposeRule()` with no qualifiers) measures only 320dp wide -- narrower than
     * `results/ui-audit/validation/V11`'s real device (`S11-radio-verified.xml`'s own root node,
     * `bounds="[0,0][1080,2400]"` at 3x density, is 360dp) -- and on that unrealistically narrow
     * default even the exact *old*, pre-fix shape (rebuilt from the real, still-unmodified
     * `org.ort.app.ui.components.KeyValueRow`/`TextAction`) and this fix measure identically
     * squeezed, proving 320dp is simply too narrow to discriminate anything here, not evidence for
     * or against the fix. 360dp, the real validated width (matching `OrtThemeScaleTest`'s own
     * `@Config(qualifiers = "w390dp-h844dp-xhdpi")` pattern for the same reason), is what this test
     * asserts against; at 360dp neither the old nor the new shape actually collapses on the real
     * "Kenwood TH-D75A · 2 bands" string either (a `results/ui-audit`-class "could not reproduce"
     * finding, CHANGELOG's own "Left open" entry has the account) -- this test still asserts the
     * new shape's invariant holds, defensively, as the coordinator's fix literally instructed.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w360dp-h800dp-xhdpi")
    fun `R_882 the radio row's label stays visible and its value stays wider than tall at scale 2_0`() {
        val row = ReadyRow(
            "Radio",
            "TH-D75A · 2 bands",
            ok = true,
            statusText = "verified",
            actionLabel = "Change",
            onAction = {},
        )
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrtTheme { ReadyScreen(state = ReadyViewState(listOf(row)), onStartCapture = {}) }
            }
        }

        composeTestRule.onNodeWithTag("setup-ready-row-radio-label", useUnmergedTree = true).assertIsDisplayed()
        val rowBounds = composeTestRule.onNodeWithTag("setup-ready-row-radio").fetchSemanticsNode().boundsInRoot
        val valueNode = composeTestRule.onNodeWithTag("setup-ready-row-radio-value", useUnmergedTree = true)
            .fetchSemanticsNode()
        val valueSize = valueNode.size
        val valueBounds = valueNode.boundsInRoot
        assert(valueSize.width > valueSize.height) {
            "expected the Radio value node wider than tall (not one letter per line), was $valueSize"
        }
        assert(
            valueBounds.left >= rowBounds.left &&
                valueBounds.right <= rowBounds.right &&
                valueBounds.top >= rowBounds.top &&
                valueBounds.bottom <= rowBounds.bottom,
        ) { "expected the value node inside its row, row=$rowBounds value=$valueBounds" }
    }

    /** The same assertion generalised across every [ReadyScreen] fact row the coordinator named
     * (Mode, Input, Radio, Models, Overnight) -- each built with a genuinely long value so a
     * regression of the same collapse would fail this test the same way R-882 failed on the
     * device, regardless of which row it recurred on. Same `@Config` as the test above, same
     * reason -- the real validated device width, not Robolectric's narrower unconfigured default. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w360dp-h800dp-xhdpi")
    fun `R_882 every fact row's value node is wider than tall and stays inside its row at scale 2_0`() {
        val rows = listOf(
            ReadyRow(
                "Mode",
                "Bluetooth-connected radio · audio by cable",
                ok = true,
                statusText = null,
                actionLabel = "Change",
            ),
            ReadyRow(
                "Input",
                "USB Audio Device (Kenwood TH-D75A)",
                ok = true,
                statusText = "verified",
                actionLabel = null,
            ),
            ReadyRow(
                "Radio",
                "TH-D75A · 2 bands, connected over Bluetooth SPP",
                ok = true,
                statusText = "verified",
                actionLabel = "Change",
            ),
            ReadyRow(
                "Models",
                "4 of 5 bundled · ready — Gemma 3 1B not in this build",
                ok = true,
                statusText = "ready",
                actionLabel = null,
            ),
            ReadyRow("Overnight", "Battery exemption skipped", ok = false, statusText = null, actionLabel = "Fix"),
        )
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrtTheme { ReadyScreen(state = ReadyViewState(rows), onStartCapture = {}) }
            }
        }

        rows.forEach { row ->
            val tag = "setup-ready-row-${row.label.lowercase()}"
            val rowBounds = composeTestRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            val valueNode = composeTestRule.onNodeWithTag("$tag-value", useUnmergedTree = true)
                .fetchSemanticsNode()
            val valueSize = valueNode.size
            val valueBounds = valueNode.boundsInRoot
            assert(valueSize.width > valueSize.height) {
                "${row.label}: expected the value node wider than tall, was $valueSize"
            }
            assert(
                valueBounds.left >= rowBounds.left &&
                    valueBounds.right <= rowBounds.right &&
                    valueBounds.top >= rowBounds.top &&
                    valueBounds.bottom <= rowBounds.bottom,
            ) { "${row.label}: expected the value node inside its row, row=$rowBounds value=$valueBounds" }
        }
    }
}
