package org.ort.app.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.components.LogRowBadge
import org.ort.app.ui.components.LogRowPartial
import org.ort.app.ui.components.LogRowViewState
import org.ort.app.ui.data.LogEmptyStateViewState
import org.ort.app.ui.data.LogListItem
import org.ort.app.ui.data.LogQuickFilterChipViewState
import org.ort.app.ui.data.LogQuickFilterId
import org.ort.app.ui.data.LogScreenViewState
import org.ort.app.ui.theme.OrtTheme
import org.ort.core.Attribution
import org.robolectric.RobolectricTestRunner

/**
 * The "Log" destination (R-040/R-041/R-042/R-043/R-045, ui-conformance WP5;
 * `design/canvas/Log.dc.html`, `Rows.dc.html`, `Log-Partial.dc.html`, `Log-Empty.dc.html`,
 * `Log-Rejected.dc.html`). Every fact this screen renders comes from `LogScreenViewState`
 * (`ui/data/LogViewData.kt`'s own tests, `LogViewDataTest`, cover the rules that produce it); this
 * proves the screen lays that state out correctly.
 *
 * **FR_UI_4, renamed from `FR_UI_4 the confidence value is shown as visible text…`** (audit
 * F-012): the design's source of truth (`States.dc.html`, guide §6.2) shows the score chip
 * **only** on INFERRED — a CONFIRMED callsign was heard, and a number would imply doubt the data
 * does not have. FR-UI-4's "never omitted" is the closed-set *state* (the marker shape), which
 * every row carries regardless of whether a confidence number sits beside it; that is what this
 * test now establishes, through [org.ort.app.ui.components.AttributionRow] rather than the legacy
 * always-show-confidence [org.ort.app.ui.components.AttributionMarker] shim.
 */
@RunWith(RobolectricTestRunner::class)
class LogScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun rowState(
        id: String,
        transcript: String,
        attribution: Attribution? = Attribution.unknown(),
        alternate: String? = null,
        badge: LogRowBadge? = null,
        partial: LogRowPartial? = null,
    ) = LogRowViewState(
        id = id,
        timeLabel = "02:14:07",
        frequencyLabel = "145.230",
        transcript = transcript,
        partial = partial,
        attribution = if (partial == null) attribution else null,
        alternate = alternate,
        signalLabel = if (partial == null) "S7" else null,
        badge = badge,
    )

    private fun screenState(
        items: List<LogListItem>,
        emptyState: LogEmptyStateViewState? = null,
        rejectedFocus: Boolean = false,
        rejectedExplanation: String? = null,
        quickFilters: List<LogQuickFilterChipViewState> = listOf(
            LogQuickFilterChipViewState(LogQuickFilterId.All, "All", true),
            LogQuickFilterChipViewState(LogQuickFilterId.Rejected, "Rejected", false),
        ),
    ) = LogScreenViewState(items, quickFilters, rejectedFocus, rejectedExplanation, emptyState)

    @Test
    fun `FR_UI_1 a transmission with no transcript yet shows the honest not-yet-transcribed state, not an empty row`() {
        composeTestRule.setContent {
            OrtTheme {
                LogScreen(
                    state = screenState(listOf(LogListItem.Row(rowState("TX1", "(captured, not yet transcribed)")))),
                    onOpen = {},
                    onQuickFilterSelect = {},
                    onFilterClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("(captured, not yet transcribed)").assertExists()
    }

    @Test
    fun `FR_UI_1 a superseded transcript is visibly marked REVISED, never silently replaced`() {
        composeTestRule.setContent {
            OrtTheme {
                LogScreen(
                    state = screenState(
                        listOf(
                            LogListItem.Row(
                                rowState(
                                    "TX1",
                                    "final transcript",
                                    Attribution.confirmed("W7NPC", 0.95),
                                    badge = LogRowBadge.REVISED,
                                ),
                            ),
                        ),
                    ),
                    onOpen = {},
                    onQuickFilterSelect = {},
                    onFilterClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("REVISED").assertExists()
    }

    @Test
    fun `FR_UI_4_state_marker_is_never_omitted_and_the_score_chip_appears_only_on_INFERRED`() {
        composeTestRule.setContent {
            OrtTheme {
                LogScreen(
                    state = screenState(
                        listOf(
                            LogListItem.Row(rowState("TX-confirmed", "a", Attribution.confirmed("W7NPC", 0.95))),
                            LogListItem.Row(rowState("TX-inferred", "b", Attribution.inferred("K7LWH", 0.82))),
                            LogListItem.Row(rowState("TX-ambiguous", "c", Attribution.ambiguous())),
                            LogListItem.Row(rowState("TX-unknown", "d", Attribution.unknown())),
                        ),
                    ),
                    onOpen = {},
                    onQuickFilterSelect = {},
                    onFilterClick = {},
                )
            }
        }

        // The state — the marker shape — is never omitted, on every one of the four closed states.
        fun marker(description: String) = composeTestRule.onNodeWithContentDescription(description, substring = true)
        marker("filled circle, Confirmed, W7NPC").assertExists()
        marker("outlined circle, Inferred, K7LWH").assertExists()
        marker("half-filled circle, Ambiguous").assertExists()
        marker("small dot, Unknown, unknown station").assertExists()

        // The score chip is visible text beside INFERRED only — never a number on CONFIRMED.
        composeTestRule.onNodeWithText("0.82").assertExists()
        composeTestRule.onNodeWithText("0.95").assertDoesNotExist()
    }

    @Test
    fun `tapping a row opens that transmission`() {
        var opened: String? = null
        composeTestRule.setContent {
            OrtTheme {
                LogScreen(
                    state = screenState(listOf(LogListItem.Row(rowState("TX1", "roger that")))),
                    onOpen = { opened = it },
                    onQuickFilterSelect = {},
                    onFilterClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("roger that").performClick()

        assert(opened == "TX1") { "expected TX1 to be opened but was $opened" }
    }

    @Test
    fun `R_045 an empty log keeps the header, chips and columns and says why nothing is here`() {
        composeTestRule.setContent {
            OrtTheme {
                LogScreen(
                    state = screenState(
                        items = emptyList(),
                        emptyState = LogEmptyStateViewState(
                            "No overs yet.",
                            "Listening since 23:32. The first one appears here the moment squelch opens.",
                        ),
                    ),
                    onOpen = {},
                    onQuickFilterSelect = {},
                    onFilterClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Log").assertExists()
        composeTestRule.onNodeWithText("All").assertExists()
        composeTestRule.onNodeWithText("No overs yet.").assertExists()
        composeTestRule
            .onNodeWithText("Listening since 23:32. The first one appears here the moment squelch opens.")
            .assertExists()
    }

    @Test
    fun `R_040 a QSO group header renders above its rows`() {
        composeTestRule.setContent {
            OrtTheme {
                LogScreen(
                    state = screenState(
                        listOf(
                            LogListItem.Group("T1", "QSO · 2 overs · 2 stations"),
                            LogListItem.Row(rowState("TX1", "monitoring", Attribution.confirmed("W7NPC", 0.9))),
                        ),
                    ),
                    onOpen = {},
                    onQuickFilterSelect = {},
                    onFilterClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("QSO · 2 overs · 2 stations").assertExists()
    }

    @Test
    fun `R_017 tapping a QSO group header opens its thread`() {
        var openedThread: String? = null
        composeTestRule.setContent {
            OrtTheme {
                LogScreen(
                    state = screenState(
                        listOf(
                            LogListItem.Group("T1", "QSO · 2 overs · 2 stations"),
                            LogListItem.Row(rowState("TX1", "monitoring", Attribution.confirmed("W7NPC", 0.9))),
                        ),
                    ),
                    onOpen = {},
                    onQuickFilterSelect = {},
                    onFilterClick = {},
                    onOpenThread = { openedThread = it },
                )
            }
        }

        composeTestRule.onNodeWithText("QSO · 2 overs · 2 stations").performClick()

        assert(openedThread == "T1") { "expected T1 to be opened but was $openedThread" }
    }

    @Test
    fun `R_040 a gap row names the reason, never conflated with a quiet band`() {
        composeTestRule.setContent {
            OrtTheme {
                LogScreen(
                    state = screenState(
                        listOf(LogListItem.Gap("G1", "02:15:00", "not listening · 38s · incoming call")),
                    ),
                    onOpen = {},
                    onQuickFilterSelect = {},
                    onFilterClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("not listening · 38s · incoming call").assertExists()
    }

    private val rejectedExplanationText =
        "1 segment rejected tonight. Audio for every one is kept; opening a row plays it."

    @Test
    fun `R_043 a rejected row stays reachable and opens like any other row`() {
        var opened: String? = null
        composeTestRule.setContent {
            OrtTheme {
                LogScreen(
                    state = screenState(
                        listOf(LogListItem.RejectedItem("TX1", "02:16:40", "146.960", "squelch tail")),
                        rejectedFocus = true,
                        rejectedExplanation = rejectedExplanationText,
                    ),
                    onOpen = { opened = it },
                    onQuickFilterSelect = {},
                    onFilterClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText(rejectedExplanationText).assertExists()
        composeTestRule.onNodeWithContentDescription("rejected", substring = true).assertExists().performClick()
        assert(opened == "TX1") { "expected TX1 to be opened but was $opened" }
    }

    @Test
    fun `R_041 a hearing partial has no state marker and shows the streaming text`() {
        composeTestRule.setContent {
            OrtTheme {
                val row = rowState("TX1", "and we're clear on the rep", partial = LogRowPartial.HEARING)
                LogScreen(
                    state = screenState(listOf(LogListItem.Row(row))),
                    onOpen = {},
                    onQuickFilterSelect = {},
                    onFilterClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("hearing…").assertExists()
        composeTestRule.onNodeWithText("and we're clear on the rep").assertExists()
    }

    @Test
    fun `R_041 a resolving partial has no state marker and shows the final text`() {
        composeTestRule.setContent {
            OrtTheme {
                val text = "and we're clear on the repeater, seven three"
                val row = rowState("TX1", text, partial = LogRowPartial.RESOLVING)
                LogScreen(
                    state = screenState(listOf(LogListItem.Row(row))),
                    onOpen = {},
                    onQuickFilterSelect = {},
                    onFilterClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("resolving…").assertExists()
    }

    @Test
    fun `R_042 tapping Filter invokes the callback`() {
        var clicked = false
        composeTestRule.setContent {
            OrtTheme {
                LogScreen(
                    state = screenState(listOf(LogListItem.Row(rowState("TX1", "roger that")))),
                    onOpen = {},
                    onQuickFilterSelect = {},
                    onFilterClick = { clicked = true },
                )
            }
        }

        composeTestRule.onNodeWithText("Filter").performClick()

        assert(clicked) { "expected the Filter action to invoke its callback" }
    }

    @Test
    fun `R_042 selecting a quick filter chip invokes the callback with its id`() {
        var selected: LogQuickFilterId? = null
        composeTestRule.setContent {
            OrtTheme {
                LogScreen(
                    state = screenState(listOf(LogListItem.Row(rowState("TX1", "roger that")))),
                    onOpen = {},
                    onQuickFilterSelect = { selected = it },
                    onFilterClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Rejected").performClick()

        assert(selected == LogQuickFilterId.Rejected) { "expected the Rejected chip to be selected but was $selected" }
    }
}
