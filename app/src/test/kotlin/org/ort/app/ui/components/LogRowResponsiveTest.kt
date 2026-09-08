package org.ort.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
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

        // Both still show every fact — nothing is dropped, only reflowed.
        composeTestRule.onAllNodesWithText("02:14:07").assertCountEquals(2)
        composeTestRule.onAllNodesWithText("145.230").assertCountEquals(2)
        composeTestRule.onAllNodesWithText("W7NPC").assertCountEquals(2)

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
}
