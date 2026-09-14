package org.ort.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * R-1078 (register, WPPOLISH): [DestructiveButton] (`Controls.kt`) padded horizontally only
 * (`padding(horizontal = 18.dp)`) — the same defect class R-1065 already fixed on
 * [PrimaryButton]/[SecondaryButton] (`ControlsTest`'s own `R_1065` cases: `requiredHeightIn(min =
 * 44.dp)` only ever raises a floor; it adds no padding once real wrapped content already exceeds
 * it), left unfixed on this one remaining button style, so a wrapped label's own last line touched
 * the button's border. The fix mirrors R-1065 exactly: a real vertical inset (`vertical =
 * OrtSpacing.sm`) alongside the existing horizontal one, so a wrapped label keeps real clearance
 * above and below regardless of how many lines it takes, while a single-line label — already well
 * within the 44dp floor — is unaffected.
 *
 * Kept in its own file rather than folded into [ControlsTest] — that file was already at
 * detekt's `LargeClass` threshold (746 lines) before this register row; adding these cases there
 * would have tipped it over for no reason connected to `DestructiveButton` itself.
 */
@RunWith(RobolectricTestRunner::class)
class DestructiveButtonPaddingTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `R_1078 a wrapped DestructiveButton label keeps padding above and below at font scale 2_0`() {
        val label = "Delete this session's audio permanently"
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrtTheme {
                    Box(modifier = Modifier.requiredWidth(90.dp)) {
                        DestructiveButton(
                            text = label,
                            onClick = {},
                            modifier = Modifier.fillMaxWidth().testTag("destructive-wrapped"),
                        )
                    }
                }
            }
        }

        val buttonBounds = composeTestRule.onNodeWithTag("destructive-wrapped").getUnclippedBoundsInRoot()
        // R-380's own `clearAndSetSemantics` puts the same text on two nodes — the outer clickable
        // node (`text`/`contentDescription`) and the real inner `Text` composable's own node
        // underneath it (`ControlsTest`'s own `assertWrappedLabelKeepsSymmetricPadding` doc comment
        // has the fuller finding) — this selects the inner one specifically, since only it carries
        // `GetTextLayoutResult`.
        val isRealTextNode = SemanticsMatcher("is the real Text node, not the outer clickable node") { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains(label) } == true &&
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult) != null
        }
        val textBounds = composeTestRule.onNode(isRealTextNode, useUnmergedTree = true).getUnclippedBoundsInRoot()

        // Sanity: this must actually force real multi-line wrap, or the test does not discriminate
        // the defect at all — a single-line label already clears the 44dp floor by itself.
        val textHeight = (textBounds.bottom - textBounds.top).value
        assert(textHeight > 44f) {
            "test setup failed to force real multi-line wrap at fontScale=2.0 for " +
                "'destructive-wrapped' — text height only ${textHeight}dp against a 44dp floor " +
                "(button=$buttonBounds text=$textBounds)"
        }

        val topInset = (textBounds.top - buttonBounds.top).value
        val bottomInset = (buttonBounds.bottom - textBounds.bottom).value
        assert(topInset >= 4f) {
            "expected real padding above a wrapped label on 'destructive-wrapped', got " +
                "${topInset}dp (button=$buttonBounds text=$textBounds)"
        }
        assert(bottomInset >= 4f) {
            "expected real padding below a wrapped label on 'destructive-wrapped', got " +
                "${bottomInset}dp (button=$buttonBounds text=$textBounds)"
        }
    }

    @Test
    fun `R_1078 a single-line DestructiveButton still meets its 44dp floor`() {
        // The added vertical padding must not push a single-line, unwrapped label's button past
        // its own guide-specified 44dp floor.
        composeTestRule.setContent {
            OrtTheme {
                DestructiveButton(text = "Delete audio", onClick = {}, modifier = Modifier.testTag("destructive-1l"))
            }
        }
        composeTestRule.onNodeWithTag("destructive-1l").assertHeightIsAtLeast(44.dp)
    }
}
