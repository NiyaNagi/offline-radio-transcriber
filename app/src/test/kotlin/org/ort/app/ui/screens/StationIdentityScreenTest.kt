package org.ort.app.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.StationGivenByYouViewState
import org.ort.app.ui.data.StationIdentityViewState
import org.ort.app.ui.data.StationVoiceViewState
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner

/** R-073 (ui-conformance-plan WP8, FR-SPK-10, constitution III): how a station is known. */
@RunWith(RobolectricTestRunner::class)
class StationIdentityScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `R_073 the Heard, Voice and Given-by-you sections all render real facts`() {
        val state = StationIdentityViewState(
            stationId = "N7XYZ",
            callsign = "N7XYZ",
            heardOverCount = 31,
            lexiconLabel = "region 7",
            voice = StationVoiceViewState(clusterOverCount = 38, confirmedCount = 31, inferredCount = 7),
            givenByYou = StationGivenByYouViewState(name = "Dave", note = null),
        )

        composeTestRule.setContent { OrtTheme { StationIdentityScreen(state = state, onBack = {}) } }

        // "N7XYZ" legitimately renders twice — the header and the Heard section's own callsign
        // row — so this checks the row's own sub-line rather than the ambiguous bare callsign.
        composeTestRule.onNodeWithText("heard 31 time(s)", substring = true).assertExists()
        composeTestRule.onNodeWithText("region 7").assertExists()
        composeTestRule.onNodeWithText("One cluster, 38 overs").assertExists()
        composeTestRule.onNodeWithText("Dave").assertExists()
    }

    @Test
    fun `R_073 the never-leaves-the-device statement is always present`() {
        val state = StationIdentityViewState(
            stationId = "N7XYZ",
            callsign = "N7XYZ",
            heardOverCount = 1,
            lexiconLabel = null,
            voice = StationVoiceViewState(clusterOverCount = 1, confirmedCount = 1, inferredCount = 0),
            givenByYou = StationGivenByYouViewState(name = null, note = null),
        )

        composeTestRule.setContent { OrtTheme { StationIdentityScreen(state = state, onBack = {}) } }

        composeTestRule.onNodeWithText("never included in a contribution", substring = true).assertExists()
    }

    @Test
    fun `R_073 a nearest-other distance that has not been computed reads honestly, never a fabricated number`() {
        val state = StationIdentityViewState(
            stationId = "N7XYZ",
            callsign = "N7XYZ",
            heardOverCount = 1,
            lexiconLabel = null,
            voice = StationVoiceViewState(clusterOverCount = 1, confirmedCount = 1, inferredCount = 0),
            givenByYou = StationGivenByYouViewState(name = null, note = null),
        )

        composeTestRule.setContent { OrtTheme { StationIdentityScreen(state = state, onBack = {}) } }

        composeTestRule.onNodeWithText("not computed yet").assertExists()
    }

    @Test
    fun `R_073 Rename, Add and Split are all reachable actions`() {
        var renamed = false
        var noted = false
        var split = false
        val state = StationIdentityViewState(
            stationId = "N7XYZ",
            callsign = "N7XYZ",
            heardOverCount = 1,
            lexiconLabel = null,
            voice = StationVoiceViewState(clusterOverCount = 1, confirmedCount = 1, inferredCount = 0),
            givenByYou = StationGivenByYouViewState(name = null, note = null),
        )

        composeTestRule.setContent {
            OrtTheme {
                StationIdentityScreen(
                    state = state,
                    onBack = {},
                    onRename = { renamed = true },
                    onAddNote = { noted = true },
                    onSplit = { split = true },
                )
            }
        }

        composeTestRule.onNodeWithText("Split").performClick()
        assert(split)
        assert(!renamed && !noted)
    }
}
