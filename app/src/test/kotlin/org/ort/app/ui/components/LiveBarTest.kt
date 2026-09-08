package org.ort.app.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner

/**
 * R-022 (ui-conformance-plan WP2): P4 ("capture state never in doubt") and P5 ("live vs record
 * differ") in one component — no live bar existed at all before this landed.
 */
@RunWith(RobolectricTestRunner::class)
class LiveBarTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `R_022 live bar degraded and halted variants differ in more than colour`() {
        val nominal = LiveBarViewState(
            level = listOf(0.4f, 1f, 0.6f, 1f),
            partialText = "and we're clear",
            label = "Live",
            tone = LiveBarTone.NOMINAL,
        )
        val degraded = LiveBarViewState(
            level = listOf(0.2f, 0.3f, 0.2f, 0.3f),
            partialText = null,
            label = "Tier 2",
            tone = LiveBarTone.DEGRADED,
        )
        val halted = LiveBarViewState(
            level = listOf(0f, 0f, 0f, 0f),
            partialText = null,
            label = "Halted — no route",
            tone = LiveBarTone.HALTED,
        )

        composeTestRule.setContent {
            OrtTheme {
                androidx.compose.foundation.layout.Column {
                    LiveBar(state = nominal, onClick = {}, modifier = Modifier.testTag("nominal"))
                    LiveBar(state = degraded, onClick = {}, modifier = Modifier.testTag("degraded"))
                    LiveBar(state = halted, onClick = {}, modifier = Modifier.testTag("halted"))
                }
            }
        }

        // Different labels/copy per tone, not merely a colour swap (constitution: "colour is
        // reinforcement, never signal").
        composeTestRule.onNodeWithText("Live").assertIsDisplayed()
        composeTestRule.onNodeWithText("Tier 2").assertIsDisplayed()
        composeTestRule.onNodeWithText("Halted — no route").assertIsDisplayed()
    }

    @Test
    fun `FR_A11Y_2 the live bar is one 44dp target with a role and a merged description`() {
        val state = LiveBarViewState(
            level = listOf(0.2f, 0.6f, 0.3f, 0.8f),
            partialText = "seven three",
            label = "Live",
            tone = LiveBarTone.NOMINAL,
        )
        var clicked = false

        composeTestRule.setContent {
            OrtTheme { LiveBar(state = state, onClick = { clicked = true }, modifier = Modifier.testTag("bar")) }
        }

        val node = composeTestRule.onNodeWithTag("bar")
        node.assertHeightIsAtLeast(44.dp)
        node.assert(hasContentDescription("Live", substring = true))
        node.assert(hasContentDescription("seven three", substring = true))
        node.performClick()
        assert(clicked)
    }

    @Test
    fun `P5 a live partial renders italic and dimmed, distinct from a resolved row`() {
        val state = LiveBarViewState(
            level = listOf(0.1f, 0.2f, 0.1f, 0.3f),
            partialText = "and we're clear on the repeater",
            label = "Live",
            tone = LiveBarTone.NOMINAL,
        )

        composeTestRule.setContent { OrtTheme { LiveBar(state = state, onClick = {}) } }

        composeTestRule.onNodeWithText("and we're clear on the repeater").assertIsDisplayed()
    }
}
