package org.ort.app.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.diagnostics.localsave.LocalSaveCategoryId
import org.ort.app.ui.theme.OrtTheme
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * WPDUMP. A checklist of ~11 rows, each with a caption and a real size, is exactly the shape that
 * has already failed on this project (R-874, R-980, R-1023) — a checkbox, a two-line caption at
 * font scale 2.0, and a trailing size label sharing one `Row`. Follows [org.ort.app.ui.screens.ModelsScreenTest]'s
 * own established `R_874`-shaped idiom exactly: `@GraphicsMode(GraphicsMode.Mode.NATIVE)` (the
 * default graphics mode does not reliably reproduce real glyph-wrap measurement), a real-width
 * `Box`, `LocalDensity` for font scale, `getUnclippedBoundsInRoot()` asserting the trailing size
 * label's own right edge never exceeds the screen's own right edge.
 *
 * The `VOICEPRINT_EMBEDDINGS` row is used throughout: its caption is the longest of the twelve
 * (base clause plus the "no voiceprints have been resolved yet" reason, since it is rendered
 * unavailable here) — the row most likely to reveal a clipping/overflow defect if one exists.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsLocalSaveScreenLayoutTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val longestRowId = LocalSaveCategoryId.VOICEPRINT_EMBEDDINGS
    private val longestRowTag = LOCAL_SAVE_ROW_TEST_TAG_PREFIX + longestRowId.name

    private fun stateWithLongestRow() = SettingsDiagnosticsViewState(
        aliveLabel = "alive",
        realTimeFactorLabel = "0.31",
        failedPassCount = 0,
        files = emptyList(),
        totalSizeLabel = "0 KB",
        localSave = LocalSaveSectionViewState(
            rows = listOf(
                LocalSaveCategoryRowViewState(
                    id = longestRowId,
                    label = "voiceprints.json",
                    caption = "the numeric voice signatures used for speaker matching, one per enrolled voice, " +
                        "derived from a real operator's voice. Tied to an identifiable person's speech, not " +
                        "audio itself, but still theirs. — no voiceprints have been resolved yet.",
                    sizeLabel = "0 KB",
                    checked = false,
                    available = false,
                ),
            ),
            totalSizeLabel = "0 KB",
        ),
    )

    private fun assertSizeLabelWithinBounds(boxWidthDp: Int, fontScale: Float) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = fontScale)) {
                OrtTheme {
                    Box(modifier = Modifier.width(boxWidthDp.dp)) {
                        SettingsDiagnosticsScreen(
                            state = stateWithLongestRow(),
                            onBack = {},
                            localSaveActions = LocalSaveActions(),
                        )
                    }
                }
            }
        }

        if (composeTestRule.onAllNodesWithTag(longestRowTag).fetchSemanticsNodes().isEmpty()) {
            composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag(longestRowTag))
        }
        // `useUnmergedTree = true`: the row's own `toggleable` merges every descendant's semantics
        // into one node, so the trailing size label's own individual bounds — the element a
        // misapplied `fillMaxWidth()` on the leading column (this project's own R-413/R-137 defect
        // shape) would push past the row's right edge — are only reachable unmerged, the identical
        // reason `ModelsScreenTest`'s own `R_970` case queries `useUnmergedTree = true` for a badge
        // sharing a row with a wrapped title.
        val sizeLabelBounds = composeTestRule
            .onNodeWithTag(longestRowTag + LOCAL_SAVE_ROW_SIZE_TAG_SUFFIX, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertTrue(
            "expected the ${longestRowId.name} row's own trailing size label right edge " +
                "(${sizeLabelBounds.right}) to stay within the real ${boxWidthDp}dp screen at font scale " +
                "$fontScale, never pushed past it",
            sizeLabelBounds.right <= boxWidthDp.dp,
        )
    }

    @Test
    @Requirement("FR-OBS-3")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `WPDUMP the longest checklist row stays within a real 390dp screen at font scale 1_0`() {
        assertSizeLabelWithinBounds(boxWidthDp = 390, fontScale = 1f)
    }

    @Test
    @Requirement("FR-OBS-3")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `WPDUMP the longest checklist row stays within a real 390dp screen at font scale 2_0`() {
        assertSizeLabelWithinBounds(boxWidthDp = 390, fontScale = 2f)
    }

    @Test
    @Requirement("FR-OBS-3")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `WPDUMP the longest checklist row stays within a real 480dp screen at font scale 1_0`() {
        assertSizeLabelWithinBounds(boxWidthDp = 480, fontScale = 1f)
    }

    @Test
    @Requirement("FR-OBS-3")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `WPDUMP the longest checklist row stays within a real 480dp screen at font scale 2_0`() {
        assertSizeLabelWithinBounds(boxWidthDp = 480, fontScale = 2f)
    }
}
