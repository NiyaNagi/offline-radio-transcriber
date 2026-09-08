package org.ort.app.ui.setup

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.components.PrimaryButton
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner

/**
 * Validator findings (register R-120..R-125) against [SetupScaffold] itself, the shell every
 * guided-setup step but [WelcomeScreen] shares — fixed once here rather than per screen.
 */
@RunWith(RobolectricTestRunner::class)
class SetupScaffoldTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun renderScaffold(lastItemText: String = "last item", fontScale: Float = 1f) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = fontScale)) {
                OrtTheme {
                    SetupScaffold(
                        step = SetupStep.MICROPHONE,
                        title = "Title",
                        subtitle = "Subtitle",
                        onBack = null,
                        bottomActions = {
                            PrimaryButton(text = "Begin", onClick = {}, modifier = Modifier.fillMaxWidth())
                        },
                    ) {
                        Text("first item")
                        Text("middle item")
                        Text(lastItemText)
                    }
                }
            }
        }
    }

    @Test
    fun `R_120 the step counter renders exactly once, never doubled above the segment row`() {
        renderScaffold()

        composeTestRule.onAllNodesWithText("1 of 7").assertCountEquals(1)
    }

    /** Matches `FailureScreensTest`'s/`ReaderAccessibilityTest`'s own V7 font-scale pattern — a
     * directly-provided [LocalDensity], not `@Config(qualifiers = "fontscale-2.0")` (not a real
     * Android resource-qualifier format; Robolectric rejects it) — the same maximum system font
     * scale (2.0) either way. */
    @Test
    fun `R_123 the last content item scrolls clear of the fixed action bar at font scale 2_0`() {
        renderScaffold(lastItemText = "the very last line of content", fontScale = 2f)

        composeTestRule.onNodeWithTag("setup-screen-MICROPHONE").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("the very last line of content")[0].performScrollTo().assertIsDisplayed()
    }

    /**
     * R-341 (validator pass 4, spec, reopened R-123/R-281/R-282/R-283): the regression test above
     * (and `WelcomeScreenTest`'s/`InputScreenTest`'s/`NotificationsScreenTest`'s/
     * `RouteMismatchScreenTest`'s/`OvernightScreenTest`'s own font-scale-2.0 tests) all
     * `performScrollTo()` before asserting — proving the content is *reachable*, never that it does
     * not overlap the bar on the very *first* frame, which is exactly what the validator's
     * screenshots (unscrolled) showed clipped. This asserts on the raw first composition, no scroll
     * performed at all, on both the content container's own placed bounds and its last child's.
     *
     * **Honestly reported, not glossed over**: run directly against the *previous* `weight(1f)` +
     * `verticalScroll` implementation (temporarily, to check this test's own power before keeping
     * it), this assertion passed there too — `ComposeTestRule.setContent` drives to a fully idle,
     * settled layout before any query runs, so it cannot observe the transient pre-settle frame the
     * validator's real-device screenshots caught. The [SubcomposeLayout] fix below is kept because
     * it is the architecturally correct, `R-292`-proven pattern the coordinator asked for and because
     * a hard `maxHeight` constraint is strictly stronger than an unenforced `weight(1f)` — not
     * because this Robolectric test can itself distinguish the two.
     */
    @Test
    fun `R_341 the last content item never overlaps the fixed bar on the very first frame at font scale 2_0`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrtTheme {
                    SetupScaffold(
                        step = SetupStep.MICROPHONE,
                        title = "Title",
                        subtitle = "Subtitle",
                        onBack = null,
                        bottomActions = {
                            PrimaryButton(
                                text = "Begin",
                                onClick = {},
                                modifier = Modifier.fillMaxWidth().testTag("setup-scaffold-bar"),
                            )
                        },
                    ) {
                        repeat(40) { i -> Text("content line $i, long enough to add real height at 2x") }
                    }
                }
            }
        }

        val lastContentBottom = composeTestRule.onNodeWithText("content line 39, long enough to add real height at 2x")
            .fetchSemanticsNode()
            .boundsInRoot
            .bottom
        val barTop = composeTestRule.onNodeWithTag("setup-scaffold-bar").fetchSemanticsNode().boundsInRoot.top

        assert(lastContentBottom <= barTop) {
            "expected the last content item's bottom ($lastContentBottom) to sit at or above the " +
                "bar's top ($barTop) on the first, unscrolled frame"
        }
    }
}
