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
        composeTestRule.setContent { OrtTheme { StationsListScreen(stations = emptyList(), onOpen = {}) } }

        composeTestRule.onNodeWithContentDescription("No stations heard yet").assertExists()
    }

    @Test
    fun `R_070 the four chips are all present and the selected one is reachable by content description`() {
        composeTestRule.setContent {
            OrtTheme {
                StationsListScreen(
                    stations = listOf(fixtureRow()),
                    onOpen = {},
                    selectedFilter = StationsFilter.TONIGHT,
                )
            }
        }

        composeTestRule.onNodeWithText("Tonight").assertExists()
        composeTestRule.onNodeWithText("All time").assertExists()
        composeTestRule.onNodeWithText("Named").assertExists()
        composeTestRule.onNodeWithText("Unidentified").assertExists()
    }

    @Test
    fun `R_070 tapping a filter chip reports which one was tapped`() {
        var selected: StationsFilter? = null
        composeTestRule.setContent {
            OrtTheme {
                StationsListScreen(
                    stations = listOf(fixtureRow()),
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
                    stations = listOf(
                        fixtureRow().copy(
                            givenName = "Dave",
                            badge = StationListBadge.NEW,
                            countContext = "6 overs",
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
                    stations = listOf(fixtureRow()),
                    onOpen = {},
                    unidentified = UnidentifiedVoicesSummary(voiceCount = 4, overCount = 36),
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
                    stations = listOf(fixtureRow()),
                    onOpen = {},
                    unidentified = UnidentifiedVoicesSummary(voiceCount = null, overCount = 12),
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("unidentified voices · 12 overs").assertExists()
    }

    @Test
    fun `tapping a station row opens that station`() {
        var opened: String? = null
        composeTestRule.setContent {
            OrtTheme { StationsListScreen(stations = listOf(fixtureRow()), onOpen = { opened = it }) }
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
        composeTestRule.onNodeWithText("By day").performClick()

        assert(openedPattern)
    }
}
