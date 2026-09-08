package org.ort.app.ui.screens

import androidx.compose.ui.test.hasScrollAction
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
    fun `R_211 the mode chip row scrolls horizontally, so an off-screen chip at large font scale is still reachable`() {
        composeTestRule.setContent { OrtTheme { StationPatternScreen(state = fixtureState(), onBack = {}) } }

        // WP2's `FilterChipRow` (shared component) — this screen must actually use it rather than
        // a bare non-scrolling `Row`, which is what V5 @f8430b8 found clipped "Change over time"
        // at font scale 2.0 with no way to reach it.
        assert(composeTestRule.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty())
        composeTestRule.onNodeWithText("Change over time").assertExists()
    }

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

        composeTestRule.onNodeWithContentDescription("Activity by day and hour", substring = true)
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

    @Test
    fun `R_210 the subtitle names the real night count and range, never Local time`() {
        composeTestRule.setContent {
            OrtTheme {
                StationPatternScreen(
                    state = fixtureState().copy(nightsSubtitle = "14 nights of listening, 25 Aug – 7 Sep"),
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithText("14 nights of listening, 25 Aug – 7 Sep").assertExists()
        composeTestRule.onNodeWithText("Local time").assertDoesNotExist()
    }

    @Test
    fun `R_209 the hour x day grid is oriented days-as-rows with day labels and the board's own legend wording`() {
        composeTestRule.setContent { OrtTheme { StationPatternScreen(state = fixtureState(), onBack = {}) } }

        composeTestRule.onNodeWithTag("pattern-mode-HOUR_BY_DAY").performClick()

        // Days labelled down the left (never on the transposed shared grid's own bottom-row
        // day-initial layout) and the board's own three-swatch legend wording — never
        // `DayOfWeekGrid`'s "heard"/"quiet".
        composeTestRule.onNodeWithText("Mon").assertExists()
        composeTestRule.onNodeWithText("Sun").assertExists()
        composeTestRule.onNodeWithText("listened, not heard").assertExists()
        composeTestRule.onNodeWithText("heard often").assertExists()
        composeTestRule.onNodeWithText("not listening").assertExists()
    }
}
