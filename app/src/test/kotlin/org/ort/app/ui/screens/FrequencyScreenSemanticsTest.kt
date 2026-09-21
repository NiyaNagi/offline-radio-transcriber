package org.ort.app.ui.screens

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.ActivityPatternMapper
import org.ort.app.ui.data.FrequencyDetailViewState
import org.ort.app.ui.data.FrequencyListEntryViewState
import org.ort.app.ui.data.FrequencyRegularViewState
import org.ort.app.ui.theme.OrtTheme
import org.ort.core.Attribution
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R-1108 (register; R-1090's own sweep left `FrequencyScreen.kt` out of its list): `FrequencyRow`
 * and `RegularRow` carried the identical `clickable` + trailing
 * `semantics(mergeDescendants = true)` shape a real device dump proved exports an *empty*
 * `content-desc` on the clickable node, stranding the real label on a non-clickable child
 * (`CheckboxRow`/`ToggleRow`'s own R-1090 finding, `Controls.kt`'s doc comment). The fix —
 * `clearAndSetSemantics` with an explicit, multi-entry `SemanticsProperties.Text` list — is the
 * same shape already confirmed for `CheckboxRow`/`ToggleRow` and `SearchScreen.kt`'s `RecentRow`.
 *
 * At `w390dp-h844dp-420dpi` per the working agreement's own layout-test requirement for a change
 * under `app/src/main/kotlin/org/ort/app/ui` (recursively).
 *
 * What this test proves and does not — see `ImproveGroupRowSemanticsTest`'s own doc comment for
 * the full account: a real, non-empty `ContentDescription` on Robolectric's tree, and that the
 * visible label/summary text stays independently findable there, never what a real device's
 * TalkBack announces. Only a device dump (the lead's own batch tour) settles that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-420dpi")
class FrequencyScreenSemanticsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    @Requirement("R-1108")
    fun `R_1108 a frequency row carries a real ContentDescription on its own clickable node`() {
        val entry = FrequencyListEntryViewState(
            frequencyHz = 146_960_000L,
            label = "146.960 MHz",
            transmissionCount = 94,
            whatItIs = "Repeater · 2 m · FM",
            tonightCount = 94,
            tonightStationCount = 7,
        )
        var opened: Long? = null
        composeTestRule.setContent {
            OrtTheme { FrequenciesListScreen(frequencies = listOf(entry), onOpen = { opened = it }) }
        }

        val rowTag = "frequency-row-${entry.frequencyHz}"
        val description = composeTestRule.onNodeWithTag(rowTag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.ContentDescription)
            ?.joinToString()

        assert(
            description?.contains("146.960 MHz") == true &&
                description?.contains("Repeater · 2 m · FM") == true,
        ) {
            "expected '$rowTag' to carry a ContentDescription naming the frequency and what it is, " +
                "got $description"
        }

        // Still findable by its own visible label on the default merged tree, and still tappable —
        // `clearAndSetSemantics` erases descendant `Text`s unless explicitly restated (this row's
        // own R-1108 comment); this is what proves that restatement actually happened.
        composeTestRule.onNodeWithText("146.960").performClick()
        assert(opened == entry.frequencyHz) { "expected tapping the row to open ${entry.frequencyHz}, got $opened" }
    }

    @Test
    @Requirement("R-1108")
    fun `R_1108 a regular row carries a real ContentDescription on its own clickable node`() {
        val regular = FrequencyRegularViewState(
            stationId = "W7NPC",
            label = "W7NPC",
            attribution = Attribution.confirmed("W7NPC", 0.9),
            countContext = "612 over(s) · 14 session(s)",
            lastHeardLabel = "02:14",
        )
        val state = FrequencyDetailViewState(
            frequencyHz = 145_230_000L,
            label = "145.230 MHz",
            transmissionCount = 4112,
            activityPattern = ActivityPatternMapper.buildPattern(emptyList(), emptyList(), 0L),
            regulars = listOf(regular),
            transmissions = emptyList(),
        )
        composeTestRule.setContent { OrtTheme { FrequencyDetailScreen(state = state, onBack = {}) } }
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("regular-W7NPC"))

        val description = composeTestRule.onNodeWithTag("regular-W7NPC", useUnmergedTree = true)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.ContentDescription)
            ?.joinToString()

        assert(description?.contains("W7NPC") == true && description?.contains("612 over(s)") == true) {
            "expected 'regular-W7NPC' to carry a ContentDescription naming the station and its " +
                "count context, got $description"
        }

        // Still findable and tappable by its own visible text (R-074's existing test already
        // covers the tap; this only re-confirms the text stays on the default merged tree).
        composeTestRule.onNodeWithText("612 over(s) · 14 session(s)").assertExists()
    }
}
