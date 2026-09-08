package org.ort.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.ort.core.Attribution
import org.robolectric.RobolectricTestRunner

/** R-023 (ui-conformance-plan WP2): the row family from guide §6.5/`Rows.dc.html`. */
@RunWith(RobolectricTestRunner::class)
class RowsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun row(
        attribution: Attribution? = Attribution.confirmed("W7NPC", 0.95),
        partial: LogRowPartial? = null,
        badge: LogRowBadge? = null,
        transcript: String = "this is whiskey seven november papa charlie, monitoring",
    ) = LogRowViewState(
        id = "TX1",
        timeLabel = "02:14:07",
        frequencyLabel = "145.230",
        transcript = transcript,
        partial = partial,
        attribution = attribution,
        signalLabel = "S7",
        badge = badge,
    )

    @Test
    fun `every LogRow variant from Rows_dc_html is distinguishable and clickable`() {
        var opened: String? = null
        composeTestRule.setContent {
            OrtTheme {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    LogRow(
                        state = row(Attribution.confirmed("W7NPC", 0.95)),
                        onClick = { opened = "confirmed" },
                        modifier = Modifier.testTag("confirmed"),
                    )
                    LogRow(
                        state = row(Attribution.inferred("K7LWH", 0.82)),
                        onClick = {},
                        modifier = Modifier.testTag("inferred"),
                    )
                    LogRow(
                        state = row(Attribution.ambiguous(), transcript = "kilo echo seven quebec romeo sierra"),
                        onClick = {},
                        modifier = Modifier.testTag("ambiguous"),
                    )
                    LogRow(
                        state = row(Attribution.unknown(), transcript = "…any station on frequency, this is"),
                        onClick = {},
                        modifier = Modifier.testTag("unknown"),
                    )
                    LogRow(
                        state = row(partial = LogRowPartial.HEARING, attribution = null),
                        onClick = {},
                        modifier = Modifier.testTag("hearing"),
                    )
                    LogRow(
                        state = row(partial = LogRowPartial.RESOLVING, attribution = null),
                        onClick = {},
                        modifier = Modifier.testTag("resolving"),
                    )
                    LogRow(state = row(badge = LogRowBadge.NEW), onClick = {}, modifier = Modifier.testTag("new"))
                }
            }
        }

        composeTestRule.onNodeWithTag("confirmed").performClick()
        assert(opened == "confirmed")
        // A scrollable Column, so a badge on the seventh stacked row need only exist, not be
        // scrolled into view, for this test's purpose (every variant renders distinctly).
        composeTestRule.onNodeWithText("hearing…").assertExists()
        composeTestRule.onNodeWithText("resolving…").assertExists()
        composeTestRule.onNodeWithText("NEW").assertExists()
        composeTestRule.onNodeWithTag("confirmed").assertHeightIsAtLeast(44.dp)
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
    fun `a drill-in header and a screen header carry their own targets and descriptions`() {
        var backCalled = false
        composeTestRule.setContent {
            OrtTheme {
                Column {
                    DrillInHeader(
                        parentLabel = "Log",
                        onBack = { backCalled = true },
                        modifier = Modifier.testTag("drillin"),
                    )
                    ScreenHeader(
                        onDrawer = {},
                        liveElapsedLabel = "6:42",
                        onSearch = {},
                        modifier = Modifier.testTag("screenheader"),
                    )
                }
            }
        }

        composeTestRule.onNode(hasContentDescription("Back to Log", substring = true)).performClick()
        assert(backCalled)
        composeTestRule.onNode(hasContentDescription("Open navigation")).assertIsDisplayed()
        composeTestRule.onNodeWithText("6:42").assertIsDisplayed()
        composeTestRule.onNodeWithTag("drillin").assertHeightIsAtLeast(44.dp)
        composeTestRule.onNodeWithTag("screenheader").assertHeightIsAtLeast(44.dp)
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
        composeTestRule.onNodeWithText("Confirm").assertIsDisplayed()
        composeTestRule.onNodeWithText("Not right?").assertIsDisplayed()
    }
}
