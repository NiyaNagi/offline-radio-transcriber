package org.ort.app.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.FrequencyChangeCause
import org.ort.app.ui.data.FrequencyChangeViewState
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner

/** R-074 (ui-conformance-plan WP8, FR-UI-11, FR-DIG): `Frequency-Change.dc.html`'s departure state. */
@RunWith(RobolectricTestRunner::class)
class FrequencyChangeScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun fixtureState() = FrequencyChangeViewState(
        frequencyHz = 146_960_000L,
        label = "146.960",
        subtitleLabel = "Tonight · 61 overs where the usual is 4",
        tonightHourly = List(24) { if (it == 2) 61 else 1 },
        usualHourly = List(24) { 1.0 },
        causes = listOf(
            FrequencyChangeCause("WA7HJR · 6 over(s) · first time heard"),
            FrequencyChangeCause("4 unidentified voice(s), 4 over(s)", isUnidentified = true),
        ),
        overCount = 61,
        explanationSentence = "A departure is a finding, not an alarm.",
    )

    @Test
    fun `R_074 the subtitle and the causes are real, honestly derived text`() {
        composeTestRule.setContent { OrtTheme { FrequencyChangeScreen(state = fixtureState(), onBack = {}) } }

        composeTestRule.onNodeWithText("Tonight · 61 overs where the usual is 4").assertExists()
        composeTestRule.onNodeWithText("WA7HJR · 6 over(s) · first time heard").assertExists()
        composeTestRule.onNodeWithText("4 unidentified voice(s), 4 over(s)").assertExists()
    }

    @Test
    fun `R_074 the departure chart is described for accessibility, never colour alone`() {
        composeTestRule.setContent { OrtTheme { FrequencyChangeScreen(state = fixtureState(), onBack = {}) } }

        composeTestRule.onNodeWithContentDescription("Tonight's hourly overs plotted over the usual average")
            .assertExists()
    }

    @Test
    fun `R_074 the N overs action is reachable`() {
        var viewedOvers = false
        composeTestRule.setContent {
            OrtTheme {
                FrequencyChangeScreen(state = fixtureState(), onBack = {}, onViewOvers = { viewedOvers = true })
            }
        }

        composeTestRule.onNodeWithText("The 61 overs").performClick()

        assert(viewedOvers)
    }

    @Test
    fun `R_074 the closing sentence frames a departure as a finding, not an alarm`() {
        composeTestRule.setContent { OrtTheme { FrequencyChangeScreen(state = fixtureState(), onBack = {}) } }

        composeTestRule.onNodeWithText("A departure is a finding, not an alarm.").assertExists()
    }
}
