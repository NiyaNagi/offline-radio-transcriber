package org.ort.app.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
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
import org.robolectric.annotation.Config

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
        // R-212/R-572: the shared plural helper, and the audio-level-resolution qualifier the
        // board's own copy carries — never the literal "(s)" placeholder, never just the bare count.
        composeTestRule.onNodeWithText("heard clearly in 31 overs", substring = true).assertExists()
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

    @Test
    fun `R_570 Split sits on the Nearest-other row, not Voiceprint`() {
        composeTestRule.setContent { OrtTheme { StationIdentityScreen(state = fixtureState(), onBack = {}) } }

        composeTestRule.onNodeWithTag("station-identity-split").performScrollTo()
        composeTestRule.onNode(
            hasTestTag("station-identity-split").and(hasAnyAncestor(hasTestTag("station-identity-nearest-other-row"))),
        ).assertExists()
        composeTestRule.onNode(
            hasTestTag("station-identity-split").and(hasAnyAncestor(hasTestTag("station-identity-voiceprint-row"))),
        ).assertDoesNotExist()
    }

    // R-611's own repro is font-scale-driven (1.0 vs 2.0), but `RowsTest.assertColumnsDoNotCollide`'s
    // own doc comment already names why that specific axis is not something a Robolectric test can
    // drive here: "Robolectric's `Paint` returns degenerate glyph metrics for this codebase's
    // `sans`/`mono` `fontFamily`s … regardless of a 2.0 font scale" (verified again directly for
    // this row: `rememberTextMeasurer` reports the identical width at fontScale 1.0 and 2.0 in this
    // host). `MarkedKeyValueRow`'s own doc comment records the same finding. The two tests below are
    // still real, named for R-611 and run at both scales, checking what *is* deterministic
    // regardless of the host's glyph metrics — the full phrase survives as one continuous node and
    // `Split` stays reachable, at both scales. The two after them exercise the stacked/one-line
    // *mechanism* itself deterministically, via a real width constraint `BoxWithConstraints` reads
    // structurally (not font metrics) — proof the code this file added actually switches layouts;
    // the exact wrap point at a real font scale needs a device, the same standing caveat R-373's own
    // report already gives for this identical class of defect.

    @Test
    fun `R_611 at font scale 1_0 the value and Split both render, real facts intact`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                OrtTheme { StationIdentityScreen(state = fixtureState(), onBack = {}) }
            }
        }
        composeTestRule.onNodeWithTag("station-identity-nearest-other-row").performScrollTo()

        composeTestRule.onNodeWithText("not computed yet").assertExists()
        composeTestRule.onNodeWithContentDescription("Split").assertExists()
    }

    @Test
    fun `R_611 at font scale 2_0 the value survives as one phrase, never split mid-word, Split stays reachable`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrtTheme { StationIdentityScreen(state = fixtureState(), onBack = {}) }
            }
        }
        composeTestRule.onNodeWithTag("station-identity-nearest-other-row").performScrollTo()

        // The full phrase survives as one continuous string in the semantics tree — never split
        // into "not" / "compute" / "d yet" fragments the way a forced mid-word hard-break would.
        composeTestRule.onNodeWithText("not computed yet").assertExists()
        composeTestRule.onNodeWithContentDescription("Split").assertExists()
    }

    @Test
    @Config(qualifiers = "w160dp-h800dp-mdpi")
    fun `R_611 the row stacks, Split beneath the value, when the real available width is too narrow`() {
        composeTestRule.setContent {
            OrtTheme {
                StationIdentityScreen(state = fixtureState(), onBack = {})
            }
        }
        composeTestRule.onNodeWithTag("station-identity-nearest-other-row").performScrollTo()

        // `useUnmergedTree = true` — `KeyValueRow`'s own one-line shape (the "not stacked" branch)
        // wraps its whole row in `semantics(mergeDescendants = true)`; on the default merged tree
        // `onNodeWithText`/`onNodeWithContentDescription` would both resolve to that one outer
        // node instead of the individual `Text`/`Split`, reporting the *row's* own bounds for
        // both and making this assertion meaningless — `RowsTest.assertColumnsDoNotCollide`'s own
        // doc comment names the identical fix for the identical reason.
        val valueBounds = composeTestRule.onNodeWithText("not computed yet", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val splitBounds = composeTestRule.onNodeWithContentDescription("Split", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot

        assert(splitBounds.top >= valueBounds.bottom) {
            "expected Split ($splitBounds) to sit below the value ($valueBounds), not beside it"
        }
    }

    @Test
    @Config(qualifiers = "w800dp-h1000dp-mdpi")
    fun `R_611 the row stays one line, Split flush right, when the real available width has room`() {
        composeTestRule.setContent {
            OrtTheme {
                StationIdentityScreen(state = fixtureState(), onBack = {})
            }
        }
        composeTestRule.onNodeWithTag("station-identity-nearest-other-row").performScrollTo()

        val valueBounds = composeTestRule.onNodeWithText("not computed yet", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val splitBounds = composeTestRule.onNodeWithContentDescription("Split", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot

        assert(splitBounds.top < valueBounds.bottom && splitBounds.bottom > valueBounds.top) {
            "expected Split ($splitBounds) to sit on the same line as the value ($valueBounds)"
        }
        assert(splitBounds.left >= valueBounds.right) {
            "expected Split ($splitBounds) to sit to the right of the value ($valueBounds)"
        }
    }

    @Test
    fun `R_571 Name and Note both carry the leading state marker, filled when set and small when None`() {
        composeTestRule.setContent {
            OrtTheme { StationIdentityScreen(state = fixtureState(name = "Dave", note = null), onBack = {}) }
        }
        composeTestRule.onNodeWithTag("station-identity-rename").performScrollTo()

        // Filled: Callsign, Lexicon (known), Name ("Dave"). Small: Note ("None"), Nearest other
        // (never computed). Proves both new rows actually carry a marker (R-571's own gap), not
        // just that the row itself renders.
        composeTestRule.onAllNodesWithContentDescription("filled circle", substring = true).assertCountEquals(3)
        composeTestRule.onAllNodesWithContentDescription("small dot", substring = true).assertCountEquals(2)
    }

    @Test
    fun `R_572 the Callsign sub-line states it was parsed from audio, not just an over count`() {
        composeTestRule.setContent { OrtTheme { StationIdentityScreen(state = fixtureState(), onBack = {}) } }

        composeTestRule.onNodeWithText("heard clearly in 31 overs · parsed from audio every time").assertExists()
    }

    @Test
    fun `R_572 the Voiceprint sub-line states stable-since when a real first-seen date exists`() {
        val state = fixtureState().copy(
            voice = StationVoiceViewState(
                clusterOverCount = 38,
                confirmedCount = 31,
                inferredCount = 7,
                stableSinceLabel = "28 Aug",
            ),
        )
        composeTestRule.setContent { OrtTheme { StationIdentityScreen(state = state, onBack = {}) } }

        composeTestRule.onNodeWithText("31 with the callsign heard · 7 inferred from it · stable since 28 Aug")
            .assertExists()
    }

    @Test
    fun `R_572 the Voiceprint sub-line omits stable-since honestly when no such fact exists`() {
        composeTestRule.setContent { OrtTheme { StationIdentityScreen(state = fixtureState(), onBack = {}) } }

        composeTestRule.onNodeWithText("31 with the callsign heard · 7 inferred from it").assertExists()
        composeTestRule.onNodeWithText("stable since", substring = true).assertDoesNotExist()
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
        // The rename field's own `Cancel`/`Save` are `TextAction`s, which
        // `clearAndSetSemantics { contentDescription = text; ... }` (Controls.kt's own R-380 doc
        // comment) — reachable by content description, not by `onNodeWithText` (the split
        // chooser's own `Cancel` below is `ActionBar`'s plain `clickable` + `Text`, unaffected).
        composeTestRule.onNodeWithContentDescription("Cancel").performScrollTo()
        composeTestRule.onNodeWithContentDescription("Cancel").performClick()

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
