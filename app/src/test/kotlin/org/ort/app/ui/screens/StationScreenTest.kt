package org.ort.app.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.ActivityPatternMapper
import org.ort.app.ui.data.StationDetailViewState
import org.ort.app.ui.data.StationListEntryViewState
import org.ort.app.ui.data.TransmissionListEntryViewState
import org.ort.app.ui.data.dayOfWeekShortLabel
import org.ort.app.ui.theme.OrtTheme
import org.ort.core.Attribution
import org.robolectric.RobolectricTestRunner

/** FR-UI-9 (build-plan P17): everything heard from one station, across every session. */
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
    fun `tapping a station row opens that station`() {
        var opened: String? = null
        composeTestRule.setContent {
            OrtTheme {
                StationsListScreen(
                    stations = listOf(
                        StationListEntryViewState(
                            stationId = "W7NPC",
                            label = "W7NPC",
                            transmissionCount = 3,
                            lastHeardLabel = "2026-09-07 02:14 UTC",
                        ),
                    ),
                    onOpen = { opened = it },
                )
            }
        }

        composeTestRule.onNodeWithText("W7NPC").performClick()

        assert(opened == "W7NPC")
    }

    @Test
    fun `station detail shows the activity pattern and every transmission heard`() {
        val pattern = ActivityPatternMapper.buildPattern(emptyList(), emptyList(), 0L)
        val state = StationDetailViewState(
            stationId = "W7NPC",
            label = "W7NPC",
            transmissionCount = 1,
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
        composeTestRule.onNodeWithText("monitoring").assertExists()
        composeTestRule.onNodeWithContentDescription("Activity by hour of day", substring = true).assertExists()
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

        composeTestRule.onNodeWithText("No transmissions recorded from this station").assertExists()
    }

    @Test
    fun `FR_UI_11 station detail renders the day-of-week chart with seven labelled buckets`() {
        val dayOfWeekPattern = ActivityPatternMapper.buildDayOfWeekPattern(emptyList(), emptyList(), 0L)
        val state = StationDetailViewState(
            stationId = "W7NPC",
            label = "W7NPC",
            transmissionCount = 0,
            activityPattern = ActivityPatternMapper.buildPattern(emptyList(), emptyList(), 0L),
            dayOfWeekPattern = dayOfWeekPattern,
            transmissions = emptyList(),
        )

        composeTestRule.setContent { OrtTheme { StationDetailScreen(state = state, onBack = {}) } }

        composeTestRule.onNodeWithContentDescription("Activity by day of week", substring = true).assertExists()
        dayOfWeekPattern.forEach { bucket ->
            composeTestRule.onNodeWithText(dayOfWeekShortLabel(bucket.dayOfWeek)).assertExists()
        }
    }
}
