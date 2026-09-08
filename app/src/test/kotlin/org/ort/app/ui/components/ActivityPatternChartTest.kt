package org.ort.app.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.HourActivityBucket
import org.ort.app.ui.data.HourActivityState
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner

/**
 * FR-UI-11/FR-UI-12 (build-plan P17): the activity-by-hour component must be able to represent
 * every hour that was never listened to, and must say so in terms a screen reader can distinguish
 * from "quiet" — not merely colour it differently (constitution VII / FR-A11Y-1's floor, applied
 * here the same way `AttributionMarkerTest` proves it for the four attribution states).
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
        composeTestRule.onNodeWithText("▨ not listening").assertIsDisplayed()
    }

    @Test
    fun `a pattern with no not-listening hours at all shows no not-listening legend`() {
        val pattern = (0..23).map { hour -> bucket(hour, HourActivityState.SILENT_WHILE_LISTENING) }

        composeTestRule.setContent { OrtTheme { ActivityPatternChart(pattern = pattern) } }

        composeTestRule.onNodeWithText("▨ not listening").assertDoesNotExist()
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
}
