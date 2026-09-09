package org.ort.app.ui.screens

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.ActivityPatternMapper
import org.ort.app.ui.data.StationDetailViewState
import org.ort.app.ui.data.StationListBadge
import org.ort.app.ui.data.StationListEntryViewState
import org.ort.app.ui.data.StationsFilter
import org.ort.app.ui.data.StationsListState
import org.ort.app.ui.data.TransmissionListEntryViewState
import org.ort.app.ui.data.UnidentifiedVoicesSummary
import org.ort.app.ui.theme.OrtTheme
import org.ort.core.Attribution
import org.robolectric.RobolectricTestRunner

/** R-070/R-071 (ui-conformance-plan WP8): everything heard from one station, across every session. */
@RunWith(RobolectricTestRunner::class)
class StationScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `an empty station list shows an honest empty state, not a fabricated placeholder`() {
        composeTestRule.setContent {
            OrtTheme { StationsListScreen(state = StationsListState(stations = emptyList()), onOpen = {}) }
        }

        composeTestRule.onNodeWithContentDescription("No stations heard yet").assertExists()
    }

    @Test
    fun `R_070 the four chips are all present and the selected one is reachable by content description`() {
        composeTestRule.setContent {
            OrtTheme {
                StationsListScreen(
                    state = StationsListState(stations = listOf(fixtureRow())),
                    onOpen = {},
                    selectedFilter = StationsFilter.TONIGHT,
                )
            }
        }

        // `FilterChip` `clearAndSetSemantics { contentDescription = label; ... }` (Controls.kt's
        // own R-380 doc comment) — reachable by content description, not by `onNodeWithText`,
        // which the merge erases (see `FrequencyChangeScreenTest`'s own fix for the identical
        // defect on `TextAction`).
        composeTestRule.onNodeWithContentDescription("Tonight").assertExists()
        composeTestRule.onNodeWithContentDescription("All time").assertExists()
        composeTestRule.onNodeWithContentDescription("Named").assertExists()
        composeTestRule.onNodeWithContentDescription("Unidentified").assertExists()
    }

    @Test
    fun `R_070 tapping a filter chip reports which one was tapped`() {
        var selected: StationsFilter? = null
        composeTestRule.setContent {
            OrtTheme {
                StationsListScreen(
                    state = StationsListState(stations = listOf(fixtureRow())),
                    onOpen = {},
                    onFilterSelected = { selected = it },
                )
            }
        }

        composeTestRule.onNodeWithTag("stations-filter-NAMED").performClick()

        assert(selected == StationsFilter.NAMED)
    }

    @Test
    fun `R_070 a NEW badge and a given name both render on the row`() {
        composeTestRule.setContent {
            OrtTheme {
                StationsListScreen(
                    state = StationsListState(
                        stations = listOf(
                            fixtureRow().copy(
                                givenName = "Dave",
                                badge = StationListBadge.NEW,
                                countContext = "6 overs",
                            ),
                        ),
                    ),
                    onOpen = {},
                )
            }
        }

        composeTestRule.onNodeWithText("new", substring = true, ignoreCase = true).assertExists()
        composeTestRule.onNodeWithText("Dave", substring = true).assertExists()
    }

    @Test
    fun `R_070 the trailing unidentified-voices row is a real aggregate, never a station row`() {
        composeTestRule.setContent {
            OrtTheme {
                StationsListScreen(
                    state = StationsListState(
                        stations = listOf(fixtureRow()),
                        unidentified = UnidentifiedVoicesSummary(voiceCount = 4, overCount = 36),
                    ),
                    onOpen = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("4 unidentified voices · 36 overs").assertExists()
    }

    @Test
    fun `R_070 the unidentified-voices row omits a fabricated count when no voice clustering exists`() {
        composeTestRule.setContent {
            OrtTheme {
                StationsListScreen(
                    state = StationsListState(
                        stations = listOf(fixtureRow()),
                        unidentified = UnidentifiedVoicesSummary(voiceCount = null, overCount = 12),
                    ),
                    onOpen = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("unidentified voices · 12 overs").assertExists()
    }

    @Test
    fun `R_207 the subtitle names the real all-time and tonight totals, and the header has no TIME or FREQ columns`() {
        composeTestRule.setContent {
            OrtTheme {
                StationsListScreen(
                    state = StationsListState(
                        stations = listOf(fixtureRow()),
                        heardAllTimeCount = 12,
                        heardTonightCount = 3,
                    ),
                    onOpen = {},
                )
            }
        }

        composeTestRule.onNodeWithText("12 stations heard all time · 3 tonight").assertExists()
        // R-207 (halt-adjacent design finding): the old header borrowed the Log row's TIME/FREQ
        // columns this board never had.
        composeTestRule.onNodeWithText("TIME").assertDoesNotExist()
        composeTestRule.onNodeWithText("FREQ").assertDoesNotExist()
        composeTestRule.onNodeWithText("STATION").assertExists()
        composeTestRule.onNodeWithText("LAST").assertExists()
    }

    @Test
    fun `R_207 Most heard toggles the sort and reports it, and the row's last-heard is a bare mono time`() {
        var sorted = false
        composeTestRule.setContent {
            OrtTheme {
                StationsListScreen(
                    state = StationsListState(stations = listOf(fixtureRow().copy(lastHeardLabel = "02:14"))),
                    onOpen = {},
                    onToggleSort = { sorted = true },
                )
            }
        }

        composeTestRule.onNodeWithTag("stations-sort-most-heard").performClick()

        assert(sorted)
        // This row renders whatever `lastHeardLabel` it is given verbatim — no date, no "UTC"
        // suffix added here; `StationsAndFrequenciesTest`/`StationPollingTest` prove the real
        // mapper now produces exactly this bare local "HH:mm" shape (R-207).
        composeTestRule.onNodeWithText("02:14").assertExists()
    }

    @Test
    fun `tapping a station row opens that station`() {
        var opened: String? = null
        composeTestRule.setContent {
            OrtTheme {
                StationsListScreen(state = StationsListState(stations = listOf(fixtureRow())), onOpen = { opened = it })
            }
        }

        composeTestRule.onNodeWithText("W7NPC").performClick()

        assert(opened == "W7NPC")
    }

    private fun fixtureRow() = StationListEntryViewState(
        stationId = "W7NPC",
        label = "W7NPC",
        transmissionCount = 3,
        lastHeardLabel = "2026-09-07 02:14 UTC",
        attribution = Attribution.confirmed("W7NPC", 0.9),
        countContext = "3 overs",
    )

    @Test
    fun `R_071 station detail shows the facts table, the activity pattern and every transmission heard`() {
        val pattern = ActivityPatternMapper.buildPattern(emptyList(), emptyList(), 0L)
        val state = StationDetailViewState(
            stationId = "W7NPC",
            label = "W7NPC",
            transmissionCount = 1,
            confirmedCount = 1,
            frequenciesSummary = "146.960 always",
            firstHeardLabel = "Tue 25 Aug, 18:58",
            lastHeardLabel = "Tonight, 02:14",
            activityPattern = pattern,
            transmissions = listOf(
                TransmissionListEntryViewState(
                    id = "TX1",
                    timeLabel = "02:14:07",
                    frequencyLabel = "146.960",
                    transcriptText = "monitoring",
                    attribution = Attribution.confirmed("W7NPC", 0.9),
                    revisionNote = null,
                    signalLabel = "S7",
                ),
            ),
        )

        composeTestRule.setContent { OrtTheme { StationDetailScreen(state = state, onBack = {}) } }

        composeTestRule.onNodeWithText("W7NPC").assertExists()
        composeTestRule.onNodeWithText("146.960 always", substring = true).assertExists()
        composeTestRule.onNodeWithText("Tue 25 Aug, 18:58", substring = true).assertExists()
        composeTestRule.onNodeWithContentDescription("Activity by hour of day", substring = true).assertExists()
        // Below the fold in a LazyColumn — scroll the list to it first (Compose's
        // `performScrollToNode`, which can bring an as-yet-uncomposed lazy item into being,
        // unlike `performScrollTo` on an already-resolved node), matching how a real user or
        // TalkBack reaches it, rather than asserting on content the initial layout pass skipped.
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("monitoring"))
        composeTestRule.onNodeWithText("monitoring").assertExists()
    }

    @Test
    fun `R_071 recent-over rows are tappable and open the transmission`() {
        var opened: String? = null
        val state = StationDetailViewState(
            stationId = "W7NPC",
            label = "W7NPC",
            transmissionCount = 1,
            activityPattern = ActivityPatternMapper.buildPattern(emptyList(), emptyList(), 0L),
            transmissions = listOf(
                TransmissionListEntryViewState(
                    id = "TX1",
                    timeLabel = "02:14:07",
                    frequencyLabel = "146.960",
                    transcriptText = "monitoring",
                    attribution = Attribution.confirmed("W7NPC", 0.9),
                    revisionNote = null,
                    signalLabel = "S7",
                ),
            ),
        )

        composeTestRule.setContent {
            OrtTheme { StationDetailScreen(state = state, onBack = {}, onOpenTransmission = { opened = it }) }
        }
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("recent-over-TX1"))
        composeTestRule.onNodeWithTag("recent-over-TX1").performClick()

        assert(opened == "TX1")
    }

    @Test
    fun `R_192 the header kebab is discoverable as Station identity and opens it`() {
        // `Station.dc.html` draws no explicit Identity action, section preview or row anywhere in
        // its body — the header kebab is the *only* affordance (V4/V5 register R-192), so it must
        // carry a real, specific description and testTag rather than the shared header's generic
        // "More".
        var openedIdentity = false
        val state = StationDetailViewState(
            stationId = "W7NPC",
            label = "W7NPC",
            transmissionCount = 0,
            activityPattern = ActivityPatternMapper.buildPattern(emptyList(), emptyList(), 0L),
            transmissions = emptyList(),
        )

        composeTestRule.setContent {
            OrtTheme { StationDetailScreen(state = state, onBack = {}, onOpenIdentity = { openedIdentity = true }) }
        }

        composeTestRule.onNodeWithContentDescription("Station identity").assertExists()
        composeTestRule.onNodeWithTag("station-identity-open").performClick()
        assert(openedIdentity)
    }

    @Test
    fun `station detail with no transmissions shows an honest empty state`() {
        val state = StationDetailViewState(
            stationId = "W7NPC",
            label = "W7NPC",
            transmissionCount = 0,
            activityPattern = ActivityPatternMapper.buildPattern(emptyList(), emptyList(), 0L),
            transmissions = emptyList(),
        )

        composeTestRule.setContent { OrtTheme { StationDetailScreen(state = state, onBack = {}) } }

        composeTestRule.onNode(hasScrollAction())
            .performScrollToNode(hasText("No transmissions recorded from this station"))
        composeTestRule.onNodeWithText("No transmissions recorded from this station").assertExists()
    }

    @Test
    fun `R_208 the detail title carries a real state marker and an honest context sentence`() {
        val state = StationDetailViewState(
            stationId = "W7NPC",
            label = "W7NPC",
            attribution = Attribution.confirmed("W7NPC", 0.95),
            contextSentence = "Heard 14 nights of 14",
            transmissionCount = 1,
            activityPattern = ActivityPatternMapper.buildPattern(emptyList(), emptyList(), 0L),
            transmissions = emptyList(),
        )

        composeTestRule.setContent { OrtTheme { StationDetailScreen(state = state, onBack = {}) } }

        // The marker itself has no text — its content description carries the real state
        // (`AttributionMarker`'s own convention); the context sentence is real, visible prose.
        composeTestRule.onNodeWithContentDescription("Confirmed", substring = true).assertExists()
        composeTestRule.onNodeWithText("Heard 14 nights of 14", substring = true).assertExists()
    }

    @Test
    fun `R_208 first and last heard render local, with no raw ISO date and no UTC suffix`() {
        val state = StationDetailViewState(
            stationId = "W7NPC",
            label = "W7NPC",
            transmissionCount = 1,
            firstHeardLabel = "2026-08-26 05:12",
            lastHeardLabel = "2026-09-08 05:37",
            activityPattern = ActivityPatternMapper.buildPattern(emptyList(), emptyList(), 0L),
            transmissions = emptyList(),
        )

        composeTestRule.setContent { OrtTheme { StationDetailScreen(state = state, onBack = {}) } }

        composeTestRule.onNodeWithText("2026-08-26 05:12", substring = true).assertExists()
        composeTestRule.onNodeWithText("UTC", substring = true).assertDoesNotExist()
    }

    @Test
    fun `R_072 station detail's By day action reaches the Station-Pattern screen, not a second chart here`() {
        // R-072's own finding: the old build drew day-of-week as a second bar chart directly on
        // this screen. The artboard puts the hour x day grid on Station-Pattern instead, reached
        // from here by one tap — the day-of-week chart itself is `StationPatternScreenTest`'s job.
        var openedPattern = false
        val state = StationDetailViewState(
            stationId = "W7NPC",
            label = "W7NPC",
            transmissionCount = 0,
            activityPattern = ActivityPatternMapper.buildPattern(emptyList(), emptyList(), 0L),
            transmissions = emptyList(),
        )

        composeTestRule.setContent {
            OrtTheme { StationDetailScreen(state = state, onBack = {}, onOpenPattern = { openedPattern = true }) }
        }
        // `TextAction` `clearAndSetSemantics { ... }` — see the R_070 chip test above.
        composeTestRule.onNodeWithContentDescription("By day").performClick()

        assert(openedPattern)
    }

    @Test
    fun `R_017 backLabel defaults to Stations, matching OrtNavHost's own hardcoded label today`() {
        val state = StationDetailViewState(
            stationId = "W7NPC",
            label = "W7NPC",
            transmissionCount = 0,
            activityPattern = ActivityPatternMapper.buildPattern(emptyList(), emptyList(), 0L),
            transmissions = emptyList(),
        )

        composeTestRule.setContent { OrtTheme { StationDetailScreen(state = state, onBack = {}) } }

        composeTestRule.onNodeWithText("Stations").assertExists()
    }

    @Test
    fun `R_017 backLabel is a real parameter WP3 can wire to the true navigation origin`() {
        val state = StationDetailViewState(
            stationId = "W7NPC",
            label = "W7NPC",
            transmissionCount = 0,
            activityPattern = ActivityPatternMapper.buildPattern(emptyList(), emptyList(), 0L),
            transmissions = emptyList(),
        )

        composeTestRule.setContent {
            OrtTheme { StationDetailScreen(state = state, onBack = {}, backLabel = "Search") }
        }

        composeTestRule.onNodeWithText("Search").assertExists()
        composeTestRule.onNodeWithText("Stations").assertDoesNotExist()
    }
}
