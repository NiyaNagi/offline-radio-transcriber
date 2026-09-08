package org.ort.app.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.ActivityPatternMapper
import org.ort.app.ui.data.FrequencyDetailViewState
import org.ort.app.ui.data.FrequencyListEntryViewState
import org.ort.app.ui.data.TransmissionListEntryViewState
import org.ort.app.ui.theme.OrtTheme
import org.ort.core.Attribution
import org.robolectric.RobolectricTestRunner

/** FR-UI-10 (build-plan P17): everything heard on one frequency, across every session. */
@RunWith(RobolectricTestRunner::class)
class FrequencyScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `an empty frequency list shows an honest empty state, not a fabricated placeholder`() {
        composeTestRule.setContent { OrtTheme { FrequenciesListScreen(frequencies = emptyList(), onOpen = {}) } }

        composeTestRule.onNodeWithContentDescription("No frequencies recorded yet").assertExists()
    }

    @Test
    fun `tapping a frequency row opens that frequency`() {
        var opened: Long? = null
        composeTestRule.setContent {
            OrtTheme {
                FrequenciesListScreen(
                    frequencies = listOf(
                        FrequencyListEntryViewState(146_960_000L, "146.960 MHz", transmissionCount = 5),
                    ),
                    onOpen = { opened = it },
                )
            }
        }

        composeTestRule.onNodeWithText("146.960 MHz").performClick()

        assert(opened == 146_960_000L)
    }

    @Test
    fun `frequency detail shows the activity pattern and every transmission heard`() {
        val pattern = ActivityPatternMapper.buildPattern(emptyList(), emptyList(), 0L)
        val state = FrequencyDetailViewState(
            frequencyHz = 146_960_000L,
            label = "146.960 MHz",
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

        composeTestRule.setContent { OrtTheme { FrequencyDetailScreen(state = state, onBack = {}) } }

        composeTestRule.onNodeWithText("146.960 MHz").assertExists()
        composeTestRule.onNodeWithText("monitoring").assertExists()
        composeTestRule.onNodeWithContentDescription("Activity by hour of day", substring = true).assertExists()
    }

    @Test
    fun `frequency detail with no transmissions shows an honest empty state`() {
        val state = FrequencyDetailViewState(
            frequencyHz = 146_960_000L,
            label = "146.960 MHz",
            transmissionCount = 0,
            activityPattern = ActivityPatternMapper.buildPattern(emptyList(), emptyList(), 0L),
            transmissions = emptyList(),
        )

        composeTestRule.setContent { OrtTheme { FrequencyDetailScreen(state = state, onBack = {}) } }

        composeTestRule.onNodeWithText("No transmissions recorded on this frequency").assertExists()
    }
}
