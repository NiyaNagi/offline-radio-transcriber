package org.ort.app.ui.setup

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
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
}
