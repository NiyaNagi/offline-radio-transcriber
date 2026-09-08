package org.ort.app.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import java.time.DayOfWeek
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.HourActivityBucket
import org.ort.app.ui.data.HourActivityState
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner

/**
 * FR-UI-11/FR-UI-12 (build-plan P17; R-021, ui-conformance-plan WP2): the activity-by-hour
 * component must be able to represent every hour that was never listened to, and must say so in
 * terms a screen reader can distinguish from "quiet" — not merely colour it differently
 * (constitution VII / FR-A11Y-1's floor, applied here the same way `AttributionMarkerTest` proves
 * it for the four attribution states). R-021 additionally requires the legend to be a shape (a
 * hatch swatch), never the `▨` font glyph (guide §7) — this file's assertions were updated from
 * "the glyph exists" to "the glyph never exists, the swatch legend does" when that fix landed.
 */
@RunWith(RobolectricTestRunner::class)
class ActivityPatternChartTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun bucket(hour: Int, state: HourActivityState, heardCount: Int = 0) =
        HourActivityBucket(hourOfDayUtc = hour, state = state, heardCount = heardCount)

    @Test
    fun `FR_UI_12 a pattern with a not-listening hour is announced as such, not silently as quiet`() {
        val pattern = (0..23).map { hour ->
            when (hour) {
                3 -> bucket(hour, HourActivityState.NOT_LISTENING)
                5 -> bucket(hour, HourActivityState.HEARD, heardCount = 2)
                else -> bucket(hour, HourActivityState.SILENT_WHILE_LISTENING)
            }
        }

        composeTestRule.setContent { OrtTheme { ActivityPatternChart(pattern = pattern) } }

        composeTestRule
            .onNode(hasContentDescription("not listening", substring = true, ignoreCase = true))
            .assertIsDisplayed()
    }

    @Test
    fun `R_021 the not-listening legend is a hatch swatch, never the font glyph`() {
        val pattern = (0..23).map { hour ->
            if (hour == 3) {
                bucket(hour, HourActivityState.NOT_LISTENING)
            } else {
                bucket(hour, HourActivityState.SILENT_WHILE_LISTENING)
            }
        }

        composeTestRule.setContent { OrtTheme { ActivityPatternChart(pattern = pattern, notListeningLabel = "38 s") } }

        composeTestRule.onNodeWithText("▨ not listening").assertDoesNotExist()
        composeTestRule.onNodeWithText("not listening · 38 s").assertIsDisplayed()
    }

    @Test
    fun `a pattern with no not-listening hours at all shows no not-listening legend`() {
        val pattern = (0..23).map { hour -> bucket(hour, HourActivityState.SILENT_WHILE_LISTENING) }

        composeTestRule.setContent { OrtTheme { ActivityPatternChart(pattern = pattern) } }

        composeTestRule.onNodeWithText("not listening").assertDoesNotExist()
    }

    @Test
    fun `the summary content description reports honest counts for every state`() {
        val pattern = listOf(
            bucket(0, HourActivityState.HEARD, heardCount = 1),
            bucket(1, HourActivityState.SILENT_WHILE_LISTENING),
            bucket(2, HourActivityState.NOT_LISTENING),
        )

        composeTestRule.setContent { OrtTheme { ActivityPatternChart(pattern = pattern) } }

        composeTestRule.onNode(
            hasContentDescription(
                "Activity by hour of day: 1 hours heard, 1 hours quiet while listening, 1 hours not listening",
            ),
        ).assertIsDisplayed()
    }

    @Test
    fun `R_021 axis labels render at both ends when supplied`() {
        val pattern = (0..23).map { hour -> bucket(hour, HourActivityState.SILENT_WHILE_LISTENING) }

        composeTestRule.setContent {
            OrtTheme { ActivityPatternChart(pattern = pattern, axisStart = "22:00", axisEnd = "06:00") }
        }

        composeTestRule.onNodeWithText("22:00").assertIsDisplayed()
        composeTestRule.onNodeWithText("06:00").assertIsDisplayed()
    }

    @Test
    fun `AC_62 a day-of-week grid distinguishes heard, quiet and not-listening cells without colour alone`() {
        val cells = DayOfWeek.entries.flatMap { day ->
            (0 until 24).map { hour ->
                val state = when {
                    day == DayOfWeek.SATURDAY -> HourActivityState.NOT_LISTENING
                    day == DayOfWeek.TUESDAY && hour == 19 -> HourActivityState.HEARD
                    else -> HourActivityState.SILENT_WHILE_LISTENING
                }
                DayHourCell(dayOfWeek = day, hourOfDayUtc = hour, state = state)
            }
        }

        composeTestRule.setContent { OrtTheme { DayOfWeekGrid(cells = cells) } }

        composeTestRule
            .onNode(hasContentDescription("not listening", substring = true, ignoreCase = true))
            .assertIsDisplayed()
    }

    @Test
    fun `a sparkline reports how many of its nights were not-listening rather than quiet`() {
        val nights = listOf(
            HourActivityState.HEARD,
            HourActivityState.SILENT_WHILE_LISTENING,
            HourActivityState.NOT_LISTENING,
        )

        composeTestRule.setContent { OrtTheme { Sparkline(nights = nights) } }

        composeTestRule.onNodeWithContentDescription("3 nights: 1 heard, 1 not listening").assertIsDisplayed()
    }
}
