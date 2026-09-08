package org.ort.app.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.ActivityPatternMapper
import org.ort.app.ui.data.HourActivityState
import org.ort.app.ui.data.HourByDayActivityCell
import org.ort.app.ui.data.StationPatternViewState
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner
import java.time.DayOfWeek

/** R-072/R-075 (ui-conformance-plan WP8): `Station-Pattern.dc.html`'s toggle and "What this says". */
@RunWith(RobolectricTestRunner::class)
class StationPatternScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun fixtureState() = StationPatternViewState(
        subjectId = "W7NPC",
        label = "W7NPC",
        hourPattern = ActivityPatternMapper.buildPattern(emptyList(), emptyList(), 0L),
        hourByDay = DayOfWeek.entries.flatMap { day ->
            (0 until 24).map { hour -> HourByDayActivityCell(day, hour, HourActivityState.NOT_LISTENING, 0) }
        },
        weekOverWeekSummary = listOf("Tue: up (2 vs 1 last week)"),
        whatThisSays = listOf("Peaks 21:00–22:00.", "Sat is unknown — this phone has never listened then."),
    )

    @Test
    fun `R_072 the three toggle modes are all present`() {
        composeTestRule.setContent { OrtTheme { StationPatternScreen(state = fixtureState(), onBack = {}) } }

        composeTestRule.onNodeWithText("By hour").assertExists()
        composeTestRule.onNodeWithText("Hour × day").assertExists()
        composeTestRule.onNodeWithText("Change over time").assertExists()
    }

    @Test
    fun `R_072 switching to Hour x day renders the grid`() {
        composeTestRule.setContent { OrtTheme { StationPatternScreen(state = fixtureState(), onBack = {}) } }

        composeTestRule.onNodeWithTag("pattern-mode-HOUR_BY_DAY").performClick()

        composeTestRule.onNodeWithContentDescription("Activity by hour and day of week", substring = true)
            .assertExists()
    }

    @Test
    fun `R_072 switching to Change over time renders the week-over-week lines`() {
        composeTestRule.setContent { OrtTheme { StationPatternScreen(state = fixtureState(), onBack = {}) } }

        composeTestRule.onNodeWithTag("pattern-mode-CHANGE_OVER_TIME").performClick()

        composeTestRule.onNodeWithText("Tue: up (2 vs 1 last week)").assertExists()
    }

    @Test
    fun `R_072 What this says names the unknown day explicitly`() {
        composeTestRule.setContent { OrtTheme { StationPatternScreen(state = fixtureState(), onBack = {}) } }

        composeTestRule.onNodeWithText("Sat is unknown — this phone has never listened then.", substring = true)
            .assertExists()
    }

    @Test
    fun `R_075 the screen title carries no UTC qualifier`() {
        composeTestRule.setContent { OrtTheme { StationPatternScreen(state = fixtureState(), onBack = {}) } }

        composeTestRule.onNodeWithText("(UTC)", substring = true).assertDoesNotExist()
    }
}
