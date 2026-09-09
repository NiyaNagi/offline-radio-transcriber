package org.ort.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.FrequencyMeanwhileEntry
import org.ort.app.ui.data.ThreadCardViewState
import org.ort.app.ui.data.ThreadListViewState
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner

/**
 * The "Threads" destination (R-044, ui-conformance WP5; `design/canvas/Threads.dc.html`,
 * `Threads-Ungrouped.dc.html`, FR-UI-2). [org.ort.app.ui.data.ThreadViewDataTest] covers the
 * grouping/reasoning/card logic; this proves the screen renders each of the three honest states.
 */
@RunWith(RobolectricTestRunner::class)
class ThreadScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun card(
        threadId: String = "T1",
        title: String = "W7NPC and K7LWH",
        isNew: Boolean = false,
        ambiguous: Boolean = false,
    ) = ThreadCardViewState(
        threadId = threadId,
        timeLabel = "02:14",
        kindLabel = null,
        frequencyLabel = "145.230",
        overCount = 4,
        titleText = title,
        metaText = "3 confirmed · 1 inferred · 02:14 – 02:16",
        ambiguous = ambiguous,
        isNew = isNew,
    )

    @Test
    fun `no transmissions at all shows an honest empty state`() {
        composeTestRule.setContent {
            OrtTheme { ThreadScreen(state = ThreadListViewState.Empty, onOpenThread = {}) }
        }

        composeTestRule.onNodeWithText("No overs yet.").assertExists()
    }

    @Test
    fun `FR_UI_2 the ungrouped state names why, never a fabricated conversation, and lists by frequency`() {
        composeTestRule.setContent {
            OrtTheme {
                ThreadScreen(
                    state = ThreadListViewState.Ungrouped(
                        totalOvers = 412,
                        byFrequency = listOf(FrequencyMeanwhileEntry(145_230_000L, "145.230", 318, 12)),
                        currentTier = 1,
                    ),
                    onOpenThread = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Conversations are not built on this phone yet").assertExists()
        composeTestRule.onNodeWithText("145.230").assertExists()
        composeTestRule.onNodeWithText("318 overs · 12 stations heard").assertExists()
    }

    @Test
    fun `R_163 the ungrouped paragraph and link name the same real tier, and the section label is upper case`() {
        composeTestRule.setContent {
            OrtTheme {
                ThreadScreen(
                    state = ThreadListViewState.Ungrouped(
                        totalOvers = 1,
                        byFrequency = emptyList(),
                        currentTier = 2,
                    ),
                    onOpenThread = {},
                )
            }
        }

        composeTestRule.onNodeWithText(
            "Grouping overs into QSOs needs speaker identity, which runs at tier 2 and above. " +
                "This phone is at tier 2, so every over is here individually and " +
                "nothing has been guessed about who was talking to whom.",
        ).assertExists()
        // R-380 correction (WP2, gate-blocking): `TextAction`'s own outer node now carries its
        // label as both `contentDescription` and `text` (`ui/components/CHANGELOG.md`), so the
        // default merged tree finds it directly and uniquely.
        composeTestRule.onNodeWithText("What tier 2 can and cannot do").assertExists()
        composeTestRule.onNodeWithText("BY FREQUENCY, MEANWHILE").assertExists()
    }

    @Test
    fun `R_163 the by-frequency row names the plural correctly for one over and one station`() {
        composeTestRule.setContent {
            OrtTheme {
                ThreadScreen(
                    state = ThreadListViewState.Ungrouped(
                        totalOvers = 1,
                        byFrequency = listOf(FrequencyMeanwhileEntry(145_230_000L, "145.230", 1, 1)),
                        currentTier = 3,
                    ),
                    onOpenThread = {},
                )
            }
        }

        composeTestRule.onNodeWithText("1 over · 1 station heard").assertExists()
    }

    @Test
    fun `R_044 a grouped card shows its title, meta and the NEW badge when first heard`() {
        composeTestRule.setContent {
            OrtTheme {
                ThreadScreen(
                    state = ThreadListViewState.Grouped(
                        summary = "1 conversation · 4 overs · newest first",
                        cards = listOf(card(isNew = true)),
                        ungroupedOvers = 0,
                    ),
                    onOpenThread = {},
                )
            }
        }

        composeTestRule.onNodeWithText("W7NPC and K7LWH").assertExists()
        composeTestRule.onNodeWithText("3 confirmed · 1 inferred · 02:14 – 02:16").assertExists()
        composeTestRule.onNodeWithText("NEW").assertExists()
    }

    @Test
    fun `R_511 a grouped card renders the thread's first-over time in its own leading column`() {
        composeTestRule.setContent {
            OrtTheme {
                ThreadScreen(
                    state = ThreadListViewState.Grouped(
                        summary = "1 conversation",
                        cards = listOf(card()),
                        ungroupedOvers = 0,
                    ),
                    onOpenThread = {},
                )
            }
        }

        composeTestRule.onNodeWithText("02:14").assertExists()
    }

    @Test
    fun `R_511 a row too narrow for the title floor stacks the title block above time and chevron, at 2_0`() {
        // Mirrors `LogRowResponsiveTest`'s own already-established approach for the identical rule
        // (R-373/R-420): two copies of the same row, one wide enough for one line, one forced
        // narrower than time + the title block's own callsign floor + the chevron slot — the
        // narrower one must measure taller, the only way its title block could have moved to its
        // own full-width line above time/chevron rather than sharing their line.
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrtTheme {
                    Column {
                        Box(modifier = Modifier.width(400.dp)) {
                            ThreadCard(card = card(), onClick = {}, modifier = Modifier.testTag("wide"))
                        }
                        Box(modifier = Modifier.width(90.dp)) {
                            ThreadCard(card = card(), onClick = {}, modifier = Modifier.testTag("narrow"))
                        }
                    }
                }
            }
        }

        // Both still show every fact — nothing dropped, only reflowed.
        composeTestRule.onAllNodesWithText("02:14").assertCountEquals(2)
        composeTestRule.onAllNodesWithText("W7NPC and K7LWH").assertCountEquals(2)

        val wideHeight = composeTestRule.onNodeWithTag("wide").fetchSemanticsNode().size.height
        val narrowHeight = composeTestRule.onNodeWithTag("narrow").fetchSemanticsNode().size.height
        assert(narrowHeight > wideHeight) {
            "expected the narrow row (too narrow for time + the title floor + the chevron) to stack " +
                "the title block above time/chevron, measuring taller than the wide row; got " +
                "wide=${wideHeight}px, narrow=${narrowHeight}px"
        }
    }

    @Test
    fun `R_044 tapping a card opens its thread`() {
        var opened: String? = null
        composeTestRule.setContent {
            OrtTheme {
                ThreadScreen(
                    state = ThreadListViewState.Grouped(
                        summary = "1 conversation",
                        cards = listOf(card(threadId = "T1")),
                        ungroupedOvers = 0,
                    ),
                    onOpenThread = { opened = it },
                )
            }
        }

        composeTestRule.onNodeWithText("W7NPC and K7LWH").performClick()

        assert(opened == "T1") { "expected T1 to be opened but was $opened" }
    }
}
