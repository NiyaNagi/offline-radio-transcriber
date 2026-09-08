package org.ort.app.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.ThreadEntryViewState
import org.ort.app.ui.data.ThreadGroupViewState
import org.ort.app.ui.data.TransmissionListEntryViewState
import org.ort.app.ui.theme.OrtTheme
import org.ort.core.Attribution
import org.robolectric.RobolectricTestRunner

/**
 * FR-UI-2 — a thread view groups transmissions into conversations and shows why each was
 * attributed. [org.ort.app.ui.data.ThreadGroupingMapperTest] covers the grouping/reasoning logic;
 * this proves the screen renders it, including the honest "not yet grouped" state build-plan P15
 * requires (threading is M6; nothing populates `threadId` today).
 */
@RunWith(RobolectricTestRunner::class)
class ThreadScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun entry(id: String, reasoning: String, attribution: Attribution = Attribution.unknown()) =
        ThreadEntryViewState(
            listEntry = TransmissionListEntryViewState(
                id = id,
                timeLabel = "02:14:07",
                frequencyLabel = "145.230",
                transcriptText = "roger that",
                attribution = attribution,
                revisionNote = null,
                signalLabel = null,
            ),
            reasoning = reasoning,
        )

    @Test
    fun `no transmissions at all shows an honest empty state`() {
        composeTestRule.setContent {
            OrtTheme { ThreadScreen(groups = emptyList(), onOpen = {}) }
        }

        composeTestRule.onNodeWithText("No transmissions yet").assertExists()
    }

    @Test
    fun `FR_UI_2 the ungrouped bucket is labelled honestly, not as a fabricated conversation`() {
        composeTestRule.setContent {
            OrtTheme {
                ThreadScreen(
                    groups = listOf(
                        ThreadGroupViewState(
                            threadId = null,
                            label = "Not yet grouped into threads",
                            entries = listOf(entry("TX1", "no callsign resolved")),
                        ),
                    ),
                    onOpen = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Not yet grouped into threads").assertExists()
    }

    @Test
    fun `FR_UI_2 each entry shows the reasoning behind its attribution`() {
        composeTestRule.setContent {
            OrtTheme {
                ThreadScreen(
                    groups = listOf(
                        ThreadGroupViewState(
                            threadId = "THREAD-A",
                            label = "Thread · 1 over(s)",
                            entries = listOf(
                                entry(
                                    "TX1",
                                    "callsign confirmed in this transmission",
                                    Attribution.confirmed("W7NPC", 0.95),
                                ),
                            ),
                        ),
                    ),
                    onOpen = {},
                )
            }
        }

        composeTestRule.onNodeWithText("callsign confirmed in this transmission").assertExists()
    }

    @Test
    fun `tapping an entry opens that transmission`() {
        var opened: String? = null
        composeTestRule.setContent {
            OrtTheme {
                ThreadScreen(
                    groups = listOf(
                        ThreadGroupViewState(
                            threadId = null,
                            label = "Not yet grouped into threads",
                            entries = listOf(entry("TX1", "no callsign resolved")),
                        ),
                    ),
                    onOpen = { opened = it },
                )
            }
        }

        composeTestRule.onNodeWithText("roger that").performClick()

        assert(opened == "TX1") { "expected TX1 to be opened but was $opened" }
    }
}
