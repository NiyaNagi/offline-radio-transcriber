package org.ort.app.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.StationGivenByYouViewState
import org.ort.app.ui.data.StationIdentityViewState
import org.ort.app.ui.data.StationVoiceSplitViewState
import org.ort.app.ui.data.StationVoiceViewState
import org.ort.app.ui.data.VoiceprintSplitOverViewState
import org.ort.app.ui.theme.OrtTheme
import org.ort.core.Attribution
import org.robolectric.RobolectricTestRunner

/** R-073 (ui-conformance-plan WP8, FR-SPK-10, constitution III): how a station is known. */
@RunWith(RobolectricTestRunner::class)
class StationIdentityScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun fixtureState(name: String? = null, note: String? = null) = StationIdentityViewState(
        stationId = "N7XYZ",
        callsign = "N7XYZ",
        heardOverCount = 31,
        lexiconLabel = "region 7",
        voice = StationVoiceViewState(clusterOverCount = 38, confirmedCount = 31, inferredCount = 7),
        givenByYou = StationGivenByYouViewState(name = name, note = note),
    )

    @Test
    fun `R_073 the Heard, Voice and Given-by-you sections all render real facts`() {
        composeTestRule.setContent {
            OrtTheme { StationIdentityScreen(state = fixtureState(name = "Dave"), onBack = {}) }
        }

        // "N7XYZ" legitimately renders twice — the header and the Heard section's own callsign
        // row — so this checks the row's own sub-line rather than the ambiguous bare callsign.
        // R-212: the shared plural helper, "heard 31 times" — never the literal "(s)" placeholder.
        composeTestRule.onNodeWithText("heard 31 times", substring = true).assertExists()
        composeTestRule.onNodeWithText("region 7").assertExists()
        composeTestRule.onNodeWithText("One cluster, 38 overs").assertExists()
        composeTestRule.onNodeWithTag("station-identity-rename").performScrollTo()
        composeTestRule.onNodeWithText("Dave").assertExists()
    }

    @Test
    fun `R_214 the HEARD section label is present and the Callsign row carries a real state marker`() {
        composeTestRule.setContent { OrtTheme { StationIdentityScreen(state = fixtureState(), onBack = {}) } }

        composeTestRule.onNodeWithText("HEARD").assertExists()
        // The marker is shape-only (no confidence chip); its content description still names the
        // real state, so this proves a marker is actually drawn beside the Heard-section facts,
        // not merely that the rows' own text renders. Both Callsign and Lexicon carry one here.
        composeTestRule.onAllNodesWithContentDescription("filled circle", substring = true)
            .assertCountEquals(2)
    }

    @Test
    fun `R_073 the never-leaves-the-device statement is always present`() {
        composeTestRule.setContent { OrtTheme { StationIdentityScreen(state = fixtureState(), onBack = {}) } }

        composeTestRule.onNodeWithText("never included in a contribution", substring = true).performScrollTo()
        composeTestRule.onNodeWithText("never included in a contribution", substring = true).assertExists()
    }

    @Test
    fun `R_073 a nearest-other distance that has not been computed reads honestly, never a fabricated number`() {
        composeTestRule.setContent { OrtTheme { StationIdentityScreen(state = fixtureState(), onBack = {}) } }

        composeTestRule.onNodeWithText("not computed yet").assertExists()
    }

    @Test
    fun `R_073 Split is a reachable action`() {
        var split = false
        composeTestRule.setContent {
            OrtTheme { StationIdentityScreen(state = fixtureState(), onBack = {}, onSplit = { split = true }) }
        }

        composeTestRule.onNodeWithTag("station-identity-split").performClick()

        assert(split)
    }

    // R-073's own screen scrolls (`Column(...).verticalScroll(...)`), so "Given by you"'s Rename/
    // Add note/Save/Cancel controls sit below the fold in this test host's default window — found,
    // not merely suspected: `assertHasClickAction()` passes on an off-screen node (existence and
    // capability don't require visibility), but `performClick()` on one silently does nothing,
    // because the coordinate it dispatches to is scrolled out of the root's viewport. Every click
    // below "Voice" in this suite scrolls the target into view first for that reason.

    @Test
    fun `R_073 tapping Add opens the name field and Save persists the typed value`() {
        var savedName: String? = "not called"
        composeTestRule.setContent {
            OrtTheme {
                StationIdentityScreen(state = fixtureState(name = null), onBack = {}, onRename = { savedName = it })
            }
        }

        composeTestRule.onNodeWithTag("station-identity-rename").performScrollTo()
        composeTestRule.onNodeWithTag("station-identity-rename").performClick()
        composeTestRule.onNodeWithTag("station-identity-name-field").performTextInput("Dave")
        composeTestRule.onNodeWithTag("station-identity-name-field-save").performScrollTo()
        composeTestRule.onNodeWithTag("station-identity-name-field-save").performClick()

        assert(savedName == "Dave") { "savedName was: $savedName" }
    }

    @Test
    fun `R_073 clearing the name field and saving persists null, never an empty string`() {
        var savedName: String? = "not called"
        composeTestRule.setContent {
            OrtTheme {
                StationIdentityScreen(state = fixtureState(name = "Dave"), onBack = {}, onRename = { savedName = it })
            }
        }

        composeTestRule.onNodeWithTag("station-identity-rename").performScrollTo()
        composeTestRule.onNodeWithTag("station-identity-rename").performClick()
        composeTestRule.onNodeWithTag("station-identity-name-field").performTextClearance()
        composeTestRule.onNodeWithTag("station-identity-name-field-save").performScrollTo()
        composeTestRule.onNodeWithTag("station-identity-name-field-save").performClick()

        assert(savedName == null) { "savedName was: $savedName" }
    }

    @Test
    fun `R_073 tapping Add note opens the note field and Save persists the typed value`() {
        var savedNote: String? = "not called"
        composeTestRule.setContent {
            OrtTheme {
                StationIdentityScreen(state = fixtureState(), onBack = {}, onAddNote = { savedNote = it })
            }
        }

        composeTestRule.onNodeWithTag("station-identity-add-note").performScrollTo()
        composeTestRule.onNodeWithTag("station-identity-add-note").performClick()
        composeTestRule.onNodeWithTag("station-identity-note-field").performTextInput("net control")
        composeTestRule.onNodeWithTag("station-identity-note-field-save").performScrollTo()
        composeTestRule.onNodeWithTag("station-identity-note-field-save").performClick()

        assert(savedNote == "net control") { "savedNote was: $savedNote" }
    }

    @Test
    fun `R_073 Cancel on the name field never calls onRename`() {
        var renamed = false
        composeTestRule.setContent {
            OrtTheme {
                StationIdentityScreen(state = fixtureState(), onBack = {}, onRename = { renamed = true })
            }
        }

        composeTestRule.onNodeWithTag("station-identity-rename").performScrollTo()
        composeTestRule.onNodeWithTag("station-identity-rename").performClick()
        composeTestRule.onNodeWithText("Cancel").performScrollTo()
        composeTestRule.onNodeWithText("Cancel").performClick()

        assert(!renamed)
        composeTestRule.onNodeWithTag("station-identity-rename").assertExists()
    }

    // --- Split (Fail-Cluster.dc.html) ---

    private fun splitFixture() = StationVoiceSplitViewState(
        stationId = "N7XYZ",
        callsign = "N7XYZ",
        fromVoiceprintId = "V1",
        overs = listOf(
            VoiceprintSplitOverViewState(
                transmissionId = "TX1",
                timeLabel = "00:41:15",
                transcriptText = "yeah I'm mobile, heading north on the five",
                attribution = Attribution.inferred("N7XYZ", 0.41),
                isAnchor = false,
                corrected = false,
            ),
            VoiceprintSplitOverViewState(
                transmissionId = "TX2",
                timeLabel = "02:12:03",
                transcriptText = "november seven x-ray yankee zulu, back to net control",
                attribution = Attribution.confirmed("N7XYZ", 0.91),
                isAnchor = true,
                corrected = false,
            ),
        ),
    )

    @Test
    fun `R_073 the split chooser lists every real over in the cluster`() {
        composeTestRule.setContent {
            OrtTheme { StationSplitScreen(state = splitFixture(), onCancel = {}, onSplit = {}) }
        }

        composeTestRule.onNodeWithText("yeah I'm mobile, heading north on the five").assertExists()
        composeTestRule.onNodeWithText("november seven x-ray yankee zulu, back to net control").assertExists()
        composeTestRule.onNodeWithText("One cluster, 2 overs.", substring = true).assertExists()
    }

    @Test
    fun `R_073 ticking a non-anchor over and confirming splits exactly that over`() {
        var splitIds: List<String>? = null
        composeTestRule.setContent {
            OrtTheme { StationSplitScreen(state = splitFixture(), onCancel = {}, onSplit = { splitIds = it }) }
        }

        composeTestRule.onNodeWithTag("split-over-TX1").performClick()
        composeTestRule.onNodeWithText("Split off 1 overs").performClick()

        assert(splitIds == listOf("TX1")) { "splitIds was: $splitIds" }
    }

    @Test
    fun `R_073 an anchor over where the callsign was heard cannot be ticked`() {
        var splitIds: List<String>? = null
        composeTestRule.setContent {
            OrtTheme { StationSplitScreen(state = splitFixture(), onCancel = {}, onSplit = { splitIds = it }) }
        }

        composeTestRule.onNodeWithContentDescription("heard, cannot be moved", substring = true).assertExists()
        composeTestRule.onNodeWithText("Split off 0 overs").performClick()

        assert(splitIds == emptyList<String>())
    }

    @Test
    fun `R_073 Cancel on the split chooser never calls onSplit`() {
        var cancelled = false
        var splitCalled = false
        composeTestRule.setContent {
            OrtTheme {
                StationSplitScreen(
                    state = splitFixture(),
                    onCancel = { cancelled = true },
                    onSplit = { splitCalled = true },
                )
            }
        }

        composeTestRule.onNodeWithText("Cancel").performClick()

        assert(cancelled)
        assert(!splitCalled)
    }
}
