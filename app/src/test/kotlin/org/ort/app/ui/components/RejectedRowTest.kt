package org.ort.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner

/** R-023 (ui-conformance-plan WP2): `RejectedRow`/`GapRow`/`LogGroupHeader`/`ActionBar`, split out
 * of `RowsTest.kt` (detekt's own `LargeClass` finding, once that file grew past a reasonable size
 * across every row family it covers) — this is its own self-contained cluster, not entangled with
 * `LogRow`/`KeyValueRow`/the rest `RowsTest.kt` still owns (its own `row()`/`maxFontScale`/
 * `assertColumnsDoNotCollide` helpers are duplicated here rather than shared, the same choice
 * `LogRowResponsiveTest.kt`/`NavRowTest.kt` already made when they split out). */
@RunWith(RobolectricTestRunner::class)
class RejectedRowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val maxFontScale = 2f

    /** See `RowsTest.kt`'s own identical helper for the full doc — duplicated, not shared. */
    private fun assertColumnsDoNotCollide(leftText: String, rightText: String, minGapDp: Int = 1) {
        val left = composeTestRule.onNodeWithText(leftText, useUnmergedTree = true).fetchSemanticsNode()
        val right = composeTestRule.onNodeWithText(rightText, useUnmergedTree = true).fetchSemanticsNode()
        val leftEdge = left.positionInRoot.x + left.size.width
        val rightEdge = right.positionInRoot.x
        val gap = rightEdge - leftEdge
        assert(gap >= minGapDp) {
            "expected at least ${minGapDp}px between '$leftText' (ending at ${leftEdge}px) and " +
                "'$rightText' (starting at ${rightEdge}px) at font scale $maxFontScale, got ${gap}px"
        }
    }

    @Test
    fun `a gap row and a rejected row stay reachable rather than disappearing`() {
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    GapRow(
                        timeLabel = "02:15:0",
                        label = "not listening · 38 s · incoming call",
                        modifier = Modifier.testTag("gap"),
                    )
                    RejectedRow(
                        timeLabel = "02:16:40",
                        frequencyLabel = "146.960",
                        reason = "squelch tail",
                        modifier = Modifier.testTag("rejected"),
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("not listening · 38 s · incoming call").assertIsDisplayed()
        composeTestRule.onNodeWithText("REJECTED · SQUELCH TAIL").assertIsDisplayed()
        composeTestRule.onNodeWithTag("gap").assertHeightIsAtLeast(44.dp)
    }

    // R-838 (register, design): a Bluetooth-audio-dropped gap draws the interrupted-connector
    // glyph, never the ordinary gapWarn circle every other cause still uses.
    @Test
    fun `R_838 a Bluetooth-audio-dropped gap draws the interrupted-connector icon`() {
        composeTestRule.setContent {
            OrtTheme {
                GapRow(
                    timeLabel = "02:15:0",
                    label = "not listening · 38 s and counting · Bluetooth audio dropped",
                    bluetoothAudioDropped = true,
                )
            }
        }

        composeTestRule.onNodeWithTag("gap-row-icon-interrupted", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("gap-row-icon-default", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `R_838 every other gap cause keeps the ordinary gapWarn icon`() {
        composeTestRule.setContent {
            OrtTheme {
                GapRow(timeLabel = "02:15:0", label = "not listening · 38 s · incoming call")
            }
        }

        composeTestRule.onNodeWithTag("gap-row-icon-default", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("gap-row-icon-interrupted", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `a log group header names the thread and a column header row names every column`() {
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    LogGroupHeader(label = "QSO · 4 overs · 2 stations")
                    ColumnHeaderRow()
                }
            }
        }

        composeTestRule.onNodeWithText("QSO · 4 overs · 2 stations").assertIsDisplayed()
        composeTestRule.onNodeWithText("TIME").assertIsDisplayed()
        composeTestRule.onNodeWithText("FREQ").assertIsDisplayed()
    }

    @Test
    fun `R_380_a clickable log group header's own node carries both OnClick and the label`() {
        // `LogGroupHeader`'s conditional `onClick` branch carried no `semantics` at all before
        // this fix — the `PrimaryButton`-before-fix shape (`Controls.kt`'s own doc comment); the
        // non-clickable branch (the test above) is unaffected and stays a plain label.
        var tapped = false
        composeTestRule.setContent {
            OrtTheme {
                LogGroupHeader(
                    label = "QSO · 4 overs · 2 stations",
                    onClick = { tapped = true },
                    modifier = Modifier.testTag("header"),
                )
            }
        }

        val node = composeTestRule.onNodeWithTag("header", useUnmergedTree = true).fetchSemanticsNode()
        assert(node.config.getOrNull(SemanticsActions.OnClick) != null) {
            "expected the clickable log group header's own node to carry OnClick"
        }
        val description = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
        assert(description?.contains("QSO · 4 overs · 2 stations") == true) {
            "expected the clickable log group header's own node to carry its own label, got $description"
        }
        composeTestRule.onNodeWithTag("header").performClick()
        assert(tapped)
    }

    @Test
    fun `R_381_a clickable rejected row's own node carries both OnClick and the composed description`() {
        // `RejectedRow`'s conditional `onClick` branch previously paired `.clickable(...)` with a
        // *separate* `semantics(mergeDescendants = true) { contentDescription = ... }` — the exact
        // two-stage shape this package's own R-380/R-381 finding proved does not survive to a real
        // device even with an explicit description. The non-clickable branch (every other
        // `RejectedRow` test in this file) is unaffected.
        var tapped = false
        composeTestRule.setContent {
            OrtTheme {
                RejectedRow(
                    timeLabel = "16:28:56",
                    frequencyLabel = "146.960",
                    reason = "squelch tail",
                    onClick = { tapped = true },
                    modifier = Modifier.testTag("rejected"),
                )
            }
        }

        val node = composeTestRule.onNodeWithTag("rejected", useUnmergedTree = true).fetchSemanticsNode()
        assert(node.config.getOrNull(SemanticsActions.OnClick) != null) {
            "expected the clickable rejected row's own node to carry OnClick"
        }
        val description = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
        assert(description?.contains("16:28:56") == true && description.contains("squelch tail")) {
            "expected the clickable rejected row's own node to carry its composed description, got " +
                description
        }
        composeTestRule.onNodeWithTag("rejected").performClick()
        assert(tapped)
    }

    @Test
    fun `R_205_gap_row and column_header_row time and freq columns meet the guide's floor and render in full`() {
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    GapRow(timeLabel = "16:28:56", label = "not listening", modifier = Modifier.testTag("gap"))
                    ColumnHeaderRow()
                }
            }
        }

        composeTestRule.onNodeWithText("16:28:56").assertIsDisplayed()
        val gapTimeWidth = composeTestRule.onNodeWithText("16:28:56").fetchSemanticsNode().size.width
        assert(gapTimeWidth >= 52) { "expected GapRow's time column at least 52px, got ${gapTimeWidth}px" }

        val headerTimeWidth = composeTestRule.onNodeWithText("TIME").fetchSemanticsNode().size.width
        val headerFreqWidth = composeTestRule.onNodeWithText("FREQ").fetchSemanticsNode().size.width
        assert(headerTimeWidth >= 52) {
            "expected ColumnHeaderRow's time column at least 52px, got ${headerTimeWidth}px"
        }
        assert(headerFreqWidth >= 56) {
            "expected ColumnHeaderRow's freq column at least 56px, got ${headerFreqWidth}px"
        }
    }

    @Test
    fun `a key value row and an action bar render at a 44dp target`() {
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    KeyValueRow(key = "Route", value = "USB audio · verified", modifier = Modifier.testTag("kv"))
                    ActionBar(secondaryLabel = "Not right?", onSecondary = {}, primaryLabel = "Confirm", onPrimary = {})
                }
            }
        }

        composeTestRule.onNodeWithTag("kv").assertHeightIsAtLeast(44.dp)
        // R-380 correction (WP2, gate-blocking): `ActionBarButton`'s own outer node now carries
        // its label as both `contentDescription` and `text` (see its own doc comment), so the
        // *default* merged tree finds it directly and uniquely — `useUnmergedTree = true` would
        // also surface the still-present inner `Text`, two matches instead of one.
        composeTestRule.onNodeWithText("Confirm").assertIsDisplayed()
        composeTestRule.onNodeWithText("Not right?").assertIsDisplayed()
    }

    @Test
    fun `R_380_an action bar's own buttons carry both OnClick and a real description on the same node`() {
        // `ActionBarButton` carried no `semantics` at all before this fix — the label lived purely
        // on the child `Text`, the `PrimaryButton`-before-fix shape (`Controls.kt`'s own doc
        // comment). Checked on the unmerged tree specifically, so this is about the one physical
        // node `OnClick` lives on, not a descendant's merged-in text.
        composeTestRule.setContent {
            OrtTheme {
                ActionBar(
                    secondaryLabel = "Not right?",
                    onSecondary = {},
                    primaryLabel = "Confirm",
                    onPrimary = {},
                    modifier = Modifier.testTag("bar"),
                )
            }
        }

        // Queried by the outer node's own `contentDescription` (not `onNodeWithText`, which would
        // resolve to the inner, non-actionable label `Text` instead — the same distinction this
        // package's other `R_380` tests draw).
        val secondary = composeTestRule
            .onNodeWithContentDescription("Not right?", useUnmergedTree = true)
            .fetchSemanticsNode()
        assert(secondary.config.getOrNull(SemanticsActions.OnClick) != null) {
            "expected the secondary action bar button's own node to carry OnClick"
        }

        val primary = composeTestRule
            .onNodeWithContentDescription("Confirm", useUnmergedTree = true)
            .fetchSemanticsNode()
        assert(primary.config.getOrNull(SemanticsActions.OnClick) != null) {
            "expected the primary action bar button's own node to carry OnClick"
        }
    }

    @Test
    fun `R_043_a rejected row's optional why line explains the reason in prose`() {
        composeTestRule.setContent {
            OrtTheme {
                RejectedRow(
                    timeLabel = "02:16:40",
                    frequencyLabel = "146.960",
                    reason = "squelch tail",
                    why = "0.4 s of noise after the carrier dropped. No speech energy.",
                    modifier = Modifier.testTag("rejected-why"),
                )
            }
        }

        composeTestRule
            .onNodeWithText("0.4 s of noise after the carrier dropped. No speech energy.")
            .assertIsDisplayed()
    }

    @Test
    fun `a rejected row with no why still renders exactly as before`() {
        composeTestRule.setContent {
            OrtTheme {
                RejectedRow(
                    timeLabel = "02:16:40",
                    frequencyLabel = "146.960",
                    reason = "squelch tail",
                    modifier = Modifier.testTag("rejected-no-why"),
                )
            }
        }

        composeTestRule.onNodeWithText("REJECTED · SQUELCH TAIL").assertIsDisplayed()
    }

    @Test
    fun `R_242_a rejected row's optional duration renders in the DUR column and reaches the description`() {
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    RejectedRow(
                        timeLabel = "02:16:40",
                        frequencyLabel = "146.960",
                        reason = "squelch tail",
                        durationLabel = "0.4s",
                        modifier = Modifier.testTag("with-duration"),
                    )
                    // Additive — a caller that supplies no duration renders exactly as before,
                    // no empty DUR column.
                    RejectedRow(
                        timeLabel = "01:52:07",
                        frequencyLabel = "145.230",
                        reason = "hallucination",
                        modifier = Modifier.testTag("no-duration"),
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("0.4s").assertIsDisplayed()
        composeTestRule.onNodeWithTag("with-duration").assert(hasContentDescription("0.4s", substring = true))
        composeTestRule.onNodeWithTag("no-duration").assert(
            hasContentDescription("01:52:07, 145.230, rejected, hallucination"),
        )
    }

    @Test
    fun `R_152_a rejected row keeps a real enforced gap between its time and freq columns at font scale 2`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = maxFontScale)) {
                OrtTheme {
                    RejectedRow(timeLabel = "02:14:07", frequencyLabel = "145.230", reason = "squelch tail")
                }
            }
        }

        assertColumnsDoNotCollide("02:14:07", "145.230", minGapDp = 10)
    }
}
