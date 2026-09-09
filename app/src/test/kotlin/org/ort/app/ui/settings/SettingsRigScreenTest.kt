package org.ort.app.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * R-413 (register, halt, Reviewer D): a Band tile's mono frequency value used to wrap one
 * character per line down the whole screen — every `Tile` in the row asked for its own
 * `Modifier.fillMaxWidth()` inside a plain, unweighted `Row`; the first tile claimed the entire
 * row, squeezing the second into ~0px. R-444 (design): `Settings-Rig`'s own disconnected-state
 * copy leaked "FR-RIG's module contract (…)", a spec id, into operator prose.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsRigScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun connectedState() = SettingsRigViewState(
        descriptorLabel = "TH-D75A",
        connected = true,
        staleSinceLabel = null,
        bands = listOf(
            SettingsRigBandViewState("A", "146.960", "FM · squelch open", squelchOpen = true),
            SettingsRigBandViewState("B", "146.960", "FM · closed", squelchOpen = false),
        ),
    )

    /** The real, rendered line count via the same `SemanticsActions.GetTextLayoutResult` action
     * TalkBack itself would use — `LogRowResponsiveTest.kt`'s own established idiom (that file's
     * own doc comment: a pixel-width guess this host's degenerate font metrics cannot be trusted
     * for) — for the [index]-th band tile's frequency `Text` specifically. */
    private fun frequencyLineCount(index: Int): Int {
        val node = composeTestRule
            .onAllNodesWithTag(BAND_TILE_FREQUENCY_TEST_TAG, useUnmergedTree = true)[index]
            .fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.singleOrNull()?.lineCount
            ?: error("no TextLayoutResult for band tile frequency #$index — is it a real Text node?")
    }

    @Test
    @Requirement("R-413")
    fun `R_413 both band tiles' frequency values render as one line at font scale 1-0`() {
        composeTestRule.setContent {
            OrtTheme { SettingsRigScreen(state = connectedState(), onBack = {}) }
        }

        composeTestRule.onAllNodesWithTag(BAND_TILE_TEST_TAG).assertCountEquals(2)
        assert(frequencyLineCount(0) == 1) { "expected band A's frequency as one line" }
        assert(frequencyLineCount(1) == 1) { "expected band B's frequency as one line" }
    }

    @Test
    @Requirement("R-413")
    fun `R_413 both band tiles' frequency values render as one line at font scale 2-0`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrtTheme { SettingsRigScreen(state = connectedState(), onBack = {}) }
            }
        }

        assert(frequencyLineCount(0) == 1) { "expected band A's frequency as one line at 2.0" }
        assert(frequencyLineCount(1) == 1) { "expected band B's frequency as one line at 2.0" }
    }

    @Test
    @Requirement("R-444")
    fun `R_444 the disconnected state never leaks the FR-RIG spec id into operator copy`() {
        val disconnected = SettingsRigViewState(
            descriptorLabel = "TH-D75A",
            connected = false,
            staleSinceLabel = null,
            bands = emptyList(),
        )
        composeTestRule.setContent {
            OrtTheme { SettingsRigScreen(state = disconnected, onBack = {}) }
        }

        composeTestRule.onNodeWithText("No radio support in this build yet", substring = true).assertExists()
        composeTestRule.onNodeWithText("FR-RIG", substring = true).assertDoesNotExist()
    }
}
