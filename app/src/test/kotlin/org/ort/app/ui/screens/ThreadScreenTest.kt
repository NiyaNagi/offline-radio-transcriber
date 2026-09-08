package org.ort.app.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
