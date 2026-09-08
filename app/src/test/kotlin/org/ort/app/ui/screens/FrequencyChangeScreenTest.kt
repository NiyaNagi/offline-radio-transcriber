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
import org.ort.app.ui.data.TimeWindow
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
        subtitleLabel = "Tonight, 02:00–04:00 · 61 overs where the usual is 4",
        tonightHourly = List(24) { if (it == 2) 61 else 1 },
        usualHourly = List(24) { 1.0 },
        usualNightsCount = 13,
        causes = listOf(
            FrequencyChangeCause("WA7HJR · 6 overs · first time heard"),
            FrequencyChangeCause("4 unidentified voices, 4 overs", isUnidentified = true),
        ),
        overCount = 61,
        explanationParagraph = "A departure is a finding, not an alarm. This one has an explanation — " +
            "see What made it busy above. It will appear in tonight's digest, and will not change " +
            "what \"usual\" means unless it keeps happening.",
        window = TimeWindow(startMillis = 1_000L, endMillis = 2_000L),
    )

    @Test
    fun `R_074 the subtitle and the causes are real, honestly derived text`() {
        composeTestRule.setContent { OrtTheme { FrequencyChangeScreen(state = fixtureState(), onBack = {}) } }

        composeTestRule.onNodeWithText("Tonight, 02:00–04:00 · 61 overs where the usual is 4").assertExists()
        composeTestRule.onNodeWithText("WA7HJR · 6 overs · first time heard").assertExists()
        composeTestRule.onNodeWithText("4 unidentified voices, 4 overs").assertExists()
    }

    @Test
    fun `R_074 the departure chart is described for accessibility, never colour alone`() {
        composeTestRule.setContent { OrtTheme { FrequencyChangeScreen(state = fixtureState(), onBack = {}) } }

        composeTestRule.onNodeWithContentDescription("Tonight's hourly overs plotted over the usual average")
            .assertExists()
    }

    @Test
    fun `R_274 the chart carries a labelled axis and a real tonight-versus-usual legend`() {
        composeTestRule.setContent { OrtTheme { FrequencyChangeScreen(state = fixtureState(), onBack = {}) } }

        composeTestRule.onNodeWithText("22:00").assertExists()
        composeTestRule.onNodeWithText("06:00").assertExists()
        composeTestRule.onNodeWithText("tonight").assertExists()
        composeTestRule.onNodeWithText("usual, 13 nights").assertExists()
    }

    @Test
    fun `R_276 the N overs action opens the frequency and its real window, not a no-op`() {
        var openedFrequency: Long? = null
        var openedWindow: TimeWindow? = null
        composeTestRule.setContent {
            OrtTheme {
                FrequencyChangeScreen(
                    state = fixtureState(),
                    onBack = {},
                    onOpenOvers = { frequencyHz, window ->
                        openedFrequency = frequencyHz
                        openedWindow = window
                    },
                )
            }
        }

        composeTestRule.onNodeWithText("The 61 overs").performClick()

        assert(openedFrequency == 146_960_000L) { "openedFrequency was: $openedFrequency" }
        assert(openedWindow == TimeWindow(startMillis = 1_000L, endMillis = 2_000L))
    }

    @Test
    fun `R_275 the full closing paragraph renders, not just its first sentence`() {
        composeTestRule.setContent { OrtTheme { FrequencyChangeScreen(state = fixtureState(), onBack = {}) } }

        composeTestRule.onNodeWithText(
            "A departure is a finding, not an alarm. This one has an explanation — see What made it " +
                "busy above. It will appear in tonight's digest, and will not change what \"usual\" " +
                "means unless it keeps happening.",
        ).assertExists()
    }
}
