package org.ort.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.ort.core.Attribution
import org.robolectric.RobolectricTestRunner

/** R-373 (ui-conformance-plan WP2): `LogRow`'s response to a font scale wide enough that time +
 * freq + the station/transcript column's own floor no longer fit one line
 * (`overnight/L01-log-pass3@2x.png`, `search-corpus/Q03-results-2x-clean-pass4.png`). Split out of
 * `RowsTest.kt` (detekt's own `LargeClass` finding, once that file grew past a reasonable size
 * across every row family it covers) rather than suppressed — this id's own two tests are already
 * self-contained, not entangled with `KeyValueRow`/`RejectedRow`/the others `RowsTest.kt` still
 * owns. */
@RunWith(RobolectricTestRunner::class)
class LogRowResponsiveTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val maxFontScale = 2f

    private fun row(
        attribution: Attribution? = Attribution.confirmed("W7NPC", 0.95),
        transcript: String = "this is whiskey seven november papa charlie, monitoring",
    ) = LogRowViewState(
        id = "TX1",
        timeLabel = "02:14:07",
        frequencyLabel = "145.230",
        transcript = transcript,
        attribution = attribution,
        signalLabel = "S7",
    )

    /** Fetches the real, rendered line count for the text node found by [text] via the same
     * `SemanticsActions.GetTextLayoutResult` action TalkBack itself would use — a real signal
     * Compose always populates from the actual layout pass, in preference to a pixel-width guess
     * this package's own `RowsTest.kt` (`assertColumnsDoNotCollide`'s own doc comment) already
     * found this host's font metrics too degenerate to trust (a 23-character string measured
     * 96px, a lone digit 1px, regardless of font scale). */
    private fun lineCountOf(text: String): Int {
        val node = composeTestRule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.singleOrNull()?.lineCount
            ?: error("no TextLayoutResult for '$text' — is it a real Text node?")
    }

    @Test
    fun `R_373_a callsign renders as one line, never split character by character, at font scale 2_0`() {
        // The register's own repro (`search-corpus/Q03-results-2x-clean-pass4.png`): "KE7QRS"
        // rendered as "KE7QR" then "S" on its own line — pins `maxLines = 1, softWrap = false` on
        // `AttributionRow`'s own callsign `Text`, this id's structural half of the fix.
        //
        // A genuine host limit, found and disclosed while writing this: this host's own degenerate
        // glyph metrics (see `lineCountOf`'s own doc comment) extend to its *line-breaking*, not
        // only its width measurement — investigated directly by forcing a real, confirmed-narrower-
        // than-content constraint (a `Box` outside `OrtTheme`'s own `Surface`, whose
        // `propagateMinConstraints` otherwise silently widens a smaller `Modifier.width()` back out,
        // the same finding recorded against R-340's own diagnosis elsewhere in this file's
        // `CHANGELOG.md`): given a real 3px budget for "KE7QRS"'s own ~6px degenerate width, this
        // host still renders one 35px-tall line, not the two 70px-tall lines a real device's own
        // line-breaker would produce — with *or* without `softWrap = false` present, so this host
        // cannot itself distinguish the fixed and unfixed code by a rendered line count or height.
        // What this test pins instead is the real, structural guarantee `softWrap = false` makes
        // regardless of any host's glyph metrics — Compose always reports exactly one line for a
        // `Text` configured this way — so a future edit that drops `softWrap = false` (or the
        // `maxLines = 1` beside it) here regresses a real, checkable fact, even though this host
        // cannot independently confirm the *narrow-container* scenario the register's screenshot
        // shows it fixes.
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = maxFontScale)) {
                OrtTheme {
                    LogRow(
                        state = row(attribution = Attribution.confirmed("KE7QRS", 0.95)),
                        onClick = {},
                        modifier = Modifier.testTag("row"),
                    )
                }
            }
        }

        val lines = lineCountOf("KE7QRS")
        assert(lines == 1) {
            "expected the callsign 'KE7QRS' to render as one line, rendered across $lines lines"
        }
    }

    @Test
    fun `R_373_log_row stacks time freq beneath the marker line when too narrow for the callsign floor, at 2_0`() {
        // R-373's other half: `LogRow`'s own weighted column now carries a real floor
        // (`rememberCallsignColumnWidth`) — the guide's own "columns wrap as whole units, never
        // intra-word" (§5) — so a row too narrow for time + freq + that floor moves time/freq (and
        // signal) to their own line *beneath* the marker line, rather than letting the weighted
        // column get squeezed under its floor. `LOG_TIME_COLUMN`/`LOG_FREQ_COLUMN` are real,
        // non-degenerate dp floors (`maxOf(floor, measuredWidth)` — this host's own degenerate
        // glyph measurement can only ever lose to the floor, never beat it), so the width this test
        // picks to force that decision is reliable regardless of this host's font metrics.
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = maxFontScale)) {
                OrtTheme {
                    Column {
                        Box(modifier = Modifier.width(400.dp)) {
                            LogRow(state = row(), onClick = {}, modifier = Modifier.testTag("wide"))
                        }
                        Box(modifier = Modifier.width(90.dp)) {
                            LogRow(state = row(), onClick = {}, modifier = Modifier.testTag("narrow"))
                        }
                    }
                }
            }
        }

        // Both still show every fact — nothing is dropped, only reflowed. R-380/R-381: `LogRow`'s
        // own outer node now `clearAndSetSemantics` (an earlier entry in this file's own
        // `CHANGELOG.md`), so these column labels are only reachable on the unmerged tree.
        composeTestRule.onAllNodesWithText("02:14:07", useUnmergedTree = true).assertCountEquals(2)
        composeTestRule.onAllNodesWithText("145.230", useUnmergedTree = true).assertCountEquals(2)
        composeTestRule.onAllNodesWithText("W7NPC", useUnmergedTree = true).assertCountEquals(2)

        // The narrow row is measurably taller than the wide one — the only way time/freq/signal
        // could have moved to a line of their own beneath the marker line + transcript, rather
        // than sharing the marker line's own row as the wide layout does.
        val wideHeight = composeTestRule.onNodeWithTag("wide").fetchSemanticsNode().size.height
        val narrowHeight = composeTestRule.onNodeWithTag("narrow").fetchSemanticsNode().size.height
        assert(narrowHeight > wideHeight) {
            "expected the narrow row (too narrow for time + freq + the callsign floor) to stack " +
                "time/freq beneath the marker line, measuring taller than the wide row; got " +
                "wide=${wideHeight}px, narrow=${narrowHeight}px"
        }
    }

    @Test
    fun `R_420_the score chip is one line and no column overflows the row's own right edge, at font scale 2_0`() {
        // The register's own repro (`overnight/L01-log@2x.png`, K7LWH's "0.82" chip): the score
        // chip previously lived welded to the callsign inside `AttributionRow`'s own non-wrapping
        // `Row` — once there was no room for both, the chip collapsed into a one-character-per-line
        // stack that collided with the SIG column instead of wrapping onto its own line the way the
        // NEW/CORRECTED/REVISED badge already did (R-244). Pulling the chip out into its own
        // `FlowRow` item (`AttributionRow(showScore = false)` + a separate `ScoreChip` right
        // after it, in `LogRowMarkerLine`) is what this pins.
        //
        // The same genuine host limit `R_373`'s own callsign test found and disclosed applies here
        // too — confirmed directly, not assumed: this host's Robolectric rendered "0.82" as one
        // line, and every column's own right edge inside the row's, at this exact width both with
        // and without `ScoreChip`'s own `softWrap = false` and with and without the chip pulled out
        // of `AttributionRow` at all. What this test still pins, meaningfully, is the real,
        // structural guarantee both fixes make regardless of any host's glyph metrics — a `Text`
        // configured `maxLines = 1, softWrap = false` always reports one line, and a node placed
        // inside a `widthIn`/`weight`-constrained column always reports a right edge inside its
        // row's — so a future edit that drops either regresses a real, checkable fact, even though
        // this host cannot independently reproduce the *collision* the register's screenshot shows
        // it fixes.
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = maxFontScale)) {
                OrtTheme {
                    Box(modifier = Modifier.width(250.dp)) {
                        LogRow(
                            state = row(attribution = Attribution.inferred("K7LWH", 0.82)),
                            onClick = {},
                            modifier = Modifier.testTag("row"),
                        )
                    }
                }
            }
        }

        val chipLines = lineCountOf("0.82")
        assert(chipLines == 1) {
            "expected the score chip '0.82' to render as one line, rendered across $chipLines lines"
        }

        // No column's own right edge runs past the row's — the SIG column (or anything else)
        // measured off-screen is exactly the second half of the register's own finding.
        val rowRight = composeTestRule.onNodeWithTag("row").fetchSemanticsNode().boundsInRoot.right
        listOf("02:14:07", "145.230", "K7LWH", "0.82", "S7").forEach { text ->
            val nodeRight = composeTestRule.onNodeWithText(text, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
                .right
            assert(nodeRight <= rowRight) {
                "expected '$text' (right edge ${nodeRight}px) to stay within the row's own right " +
                    "edge (${rowRight}px) at font scale 2.0"
            }
        }
    }

    @Test
    fun `R_420_column_header_row stacks STATION above TIME FREQ SIG when too narrow for the callsign floor, at 2_0`() {
        // R-420's own "the column header row stacks the same way" — mirrors
        // `R_373_log_row stacks…`'s own already-discriminative approach (`LOG_TIME_COLUMN`/
        // `LOG_FREQ_COLUMN` are real, non-degenerate `Dp` floors, so the width this test picks to
        // force the stacked layout is reliable regardless of this host's font metrics) — confirmed
        // directly: re-run against a temporarily-forced always-one-line branch, this failed exactly
        // as expected before the real condition was restored.
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = maxFontScale)) {
                OrtTheme {
                    Column {
                        Box(modifier = Modifier.width(400.dp)) {
                            ColumnHeaderRow(modifier = Modifier.testTag("wide"))
                        }
                        Box(modifier = Modifier.width(90.dp)) {
                            ColumnHeaderRow(modifier = Modifier.testTag("narrow"))
                        }
                    }
                }
            }
        }

        // Every header still shows — nothing dropped, only reflowed.
        composeTestRule.onAllNodesWithText("TIME").assertCountEquals(2)
        composeTestRule.onAllNodesWithText("FREQ").assertCountEquals(2)
        composeTestRule.onAllNodesWithText("STATION").assertCountEquals(2)
        composeTestRule.onAllNodesWithText("SIG").assertCountEquals(2)

        val wideHeight = composeTestRule.onNodeWithTag("wide").fetchSemanticsNode().size.height
        val narrowHeight = composeTestRule.onNodeWithTag("narrow").fetchSemanticsNode().size.height
        assert(narrowHeight > wideHeight) {
            "expected the narrow header row to stack TIME/FREQ/SIG beneath STATION, measuring " +
                "taller than the wide row; got wide=${wideHeight}px, narrow=${narrowHeight}px"
        }
    }

    @Test
    fun `R_381_log_row's own unmerged node carries both OnClick and the composed description`() {
        // R-381's own attribution list names "L01/T02 rows" — checked here, not merely asserted:
        // `LogRow` already composes `logRowDescription(state)` explicitly, in the same
        // `semantics(mergeDescendants = true)` block `clickable` lives in, not an empty block
        // depending on merge-from-descendants alone — so this is confirmation, not a fix.
        // `useUnmergedTree = true` matters here specifically — the assertion is about this one
        // physical node's own semantics config, not whatever the merged-tree view would report
        // (which would also see `AttributionRow`'s own separate, nested merge-boundary node).
        composeTestRule.setContent {
            OrtTheme {
                LogRow(
                    state = row(attribution = Attribution.confirmed("W7NPC", 0.95)),
                    onClick = {},
                    modifier = Modifier.testTag("row"),
                )
            }
        }

        val node = composeTestRule.onNodeWithTag("row", useUnmergedTree = true).fetchSemanticsNode()
        assert(node.config.getOrNull(SemanticsActions.OnClick) != null) {
            "expected LogRow's own node to carry OnClick"
        }
        val description = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
        assert(description?.contains("W7NPC") == true && description.contains("02:14:07")) {
            "expected LogRow's own node (carrying OnClick) to also carry a description with both " +
                "the callsign and the time, got $description"
        }
    }
}
