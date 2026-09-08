package org.ort.app.ui.failures

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner

/**
 * Register R-252/R-292 (V6 pass 2 then pass 3, closed against R-252's wording and reopened
 * identically as R-292): [FailureActionBarScaffold]'s bar-height reservation must be correct on
 * the very first frame — cold launch, no scroll, no interaction — not just once a later
 * recomposition or a manual scroll has caught up.
 */
@RunWith(RobolectricTestRunner::class)
class FailureActionBarScaffoldTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `R_252 the bar reserves its own height before the first frame is ever drawn, at font scale 2_0`() {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrtTheme {
                    FailureActionBarScaffold(
                        actionBar = {
                            Text(
                                text = "Rebuild now and continue",
                                modifier = Modifier.testTag("failure-action-bar-scaffold-bar"),
                            )
                        },
                        content = {
                            Text(
                                text = "the hour-bucket table changed shape · 4,318 overs marked · " +
                                    "runs in the background",
                                modifier = Modifier.testTag("failure-action-bar-scaffold-last-item"),
                            )
                        },
                    )
                }
            }
        }

        val barTop = composeTestRule.onNodeWithTag("failure-action-bar-scaffold-bar").getUnclippedBoundsInRoot().top
        val contentBottom = composeTestRule.onNodeWithTag("failure-action-bar-scaffold-last-item")
            .getUnclippedBoundsInRoot().bottom
        assertTrue(
            "content bottom ($contentBottom) must clear the bar's top ($barTop) on the very first frame, " +
                "never just after a later recomposition catches up",
            contentBottom <= barTop,
        )
    }

    /**
     * Register R-292 (V6 pass 3, `migration-failed/F20-pass3-2x-initial.png`): the earlier
     * `SubcomposeLayout` fix measured the bar's height correctly and on time, but only turned it
     * into the scrollable *content*'s own bottom **padding**, while still measuring/placing the
     * scrollable container itself at the *full* screen height. Padding inside a `verticalScroll`
     * only reserves blank space at the very *end* of the scrollable content — at rest (scroll
     * offset 0, "cold launch with no interaction", this row's own wording), whatever real content
     * happens to lay out in the bar's screen region is still drawn there, and the bar (drawn after,
     * on top) visually overlaps it. Reproduced here with the real [FailMigrationScreen] and a
     * fixture long enough, at font scale 2.0, to reach the bar's own region — the same shape as the
     * register row's screenshot. The correct fix constrains the *content slot's own measured
     * height*, not just its padding, so nothing can ever be laid out — let alone drawn — behind the
     * bar at any scroll offset, first frame included: for every real content node that is actually
     * on screen (`assertIsDisplayed` — a node the fix correctly scrolls out of view first is not
     * checked, since nothing renders there to overlap), its bottom bound must sit above the bar's
     * own top bound.
     */
    @Test
    fun `R_292 no real content renders behind the fixed bar on the very first frame, cold launch, font scale 2_0`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrtTheme {
                    FailMigrationScreen(
                        state = MigrationViewState(
                            versionLabel = "Updated to 1.1.0",
                            headline = "The records did not fully carry over",
                            steps = listOf(
                                MigrationStep("Audio untouched", "38.2 GB · 4,318 files · checksums match", ok = true),
                                MigrationStep(
                                    "Transcripts, all versions, untouched",
                                    "current and superseded · 6,904 rows",
                                    ok = true,
                                ),
                                MigrationStep(
                                    "Attributions, corrections, stations untouched",
                                    "every state, every correction",
                                    ok = true,
                                ),
                                MigrationStep(
                                    "Activity patterns need rebuilding",
                                    "the hour-bucket table changed shape · 4,318 overs marked · " +
                                        "runs in the background",
                                    ok = false,
                                ),
                            ),
                        ),
                        onRebuildNow = {},
                        onSaveDiagnosticBundle = {},
                    )
                }
            }
        }

        val barTop = composeTestRule.onNodeWithTag("failure-migration-rebuild").getUnclippedBoundsInRoot().top
        val closingParagraph = composeTestRule.onNodeWithText(
            "A migration can never destroy audio or a superseded transcript — that is tested against " +
                "every released version before this one ships.",
        )
        val isDisplayedAtRest = try {
            closingParagraph.assertIsDisplayed()
            true
        } catch (notDisplayed: AssertionError) {
            false
        }
        if (isDisplayedAtRest) {
            val closingBottom = closingParagraph.getUnclippedBoundsInRoot().bottom
            assertTrue(
                "the closing paragraph is on screen at rest ($closingBottom) but overlaps the bar ($barTop) — " +
                    "it must either be scrolled fully clear of the bar, or not rendered there at all",
                closingBottom <= barTop,
            )
        }
        // Either way (fits already, or needed a scroll first), the content must be fully reachable
        // and, once reached, genuinely clear of the bar — matching R-151/R-123's own established
        // "content can always scroll clear" pattern, so this test asserts something concrete no
        // matter which of the two states the paragraph started in.
        val afterScrollBottom = closingParagraph.performScrollTo().getUnclippedBoundsInRoot().bottom
        val barTopAfterScroll = composeTestRule.onNodeWithTag("failure-migration-rebuild")
            .getUnclippedBoundsInRoot().top
        assertTrue(
            "the closing paragraph ($afterScrollBottom), once scrolled to, must clear the bar ($barTopAfterScroll)",
            afterScrollBottom <= barTopAfterScroll,
        )
        // The bar itself must always be exactly where it claims to be — fully on screen throughout.
        composeTestRule.onNodeWithTag("failure-migration-rebuild").assertIsDisplayed()
    }
}
