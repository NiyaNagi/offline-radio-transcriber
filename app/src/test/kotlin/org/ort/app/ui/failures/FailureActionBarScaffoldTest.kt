package org.ort.app.ui.failures

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner

/**
 * Register R-252 (V6 pass 2, `migration-failed/F20-pass2@2x.png`): [FailureActionBarScaffold]'s
 * bar-height reservation must be correct on the very first frame, not just once a later
 * recomposition has caught up — a `mutableStateOf` written from `onGloballyPositioned` only
 * updates the padding on a *following* frame, which is exactly the gap V6 caught (the bottom
 * bullet, and "Save a diagnostic bundle first", both flush against/under the bar until a manual
 * scroll). `mainClock.autoAdvance = false` before `setContent`, with no `advanceTimeByFrame()`
 * call before asserting, isolates exactly that first frame.
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
}
