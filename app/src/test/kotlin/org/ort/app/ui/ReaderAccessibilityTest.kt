package org.ort.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.status.StatusViewState
import org.ort.app.ui.navigation.OrtNavHost
import org.ort.app.ui.navigation.ReaderDestination
import org.ort.app.ui.screens.StatusScreen
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner

/**
 * AC-63 / FR-A11Y-2, FR-A11Y-3: the reader survives maximum system font scale without clipping,
 * and every interactive element carries a content description.
 */
@RunWith(RobolectricTestRunner::class)
class ReaderAccessibilityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /**
     * 2x (200%) is the largest scale Android's own accessibility "Font size" setting offers on
     * the reference API range (26-34) — the "largest supported scale" FR-A11Y-3 refers to without
     * naming a number.
     */
    private val maxFontScale = 2f

    @Test
    fun `AC_63 the status screen renders every field without clipping at maximum font scale`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = maxFontScale)) {
                OrtTheme {
                    StatusScreen(
                        state = StatusViewState(
                            stateLabel = "Capturing",
                            elapsedLabel = "00:05:00",
                            transmissionCount = 3,
                            gapCount = 1,
                            shedLevel = 2,
                            shedLevelLabel = "Level 2 — speaker identity paused",
                            livenessLabel = "Alive (heartbeat current)",
                            uncleanEndBanner = "The previous session ended unexpectedly.",
                        ),
                    )
                }
            }
        }

        // Every field must still be laid out and visible — a clipped or overlapping node fails
        // assertIsDisplayed (zero size, or entirely outside its parent's bounds).
        composeTestRule.onNodeWithText("Capturing").assertIsDisplayed()
        composeTestRule.onNodeWithText("The previous session ended unexpectedly.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Elapsed: 00:05:00").assertIsDisplayed()
        composeTestRule.onNodeWithText("Transmissions: 3").assertIsDisplayed()
        composeTestRule.onNodeWithText("Gaps: 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Shed level: Level 2 — speaker identity paused").assertIsDisplayed()
        composeTestRule.onNodeWithText("Liveness: Alive (heartbeat current)").assertIsDisplayed()
    }

    @Test
    fun `AC_63 every interactive element in the nav host carries a content description`() {
        composeTestRule.setContent {
            OrtTheme {
                OrtNavHost(sessionId = null)
            }
        }

        composeTestRule.onNodeWithContentDescription("Open navigation drawer").assertIsDisplayed().performClick()

        ReaderDestination.entries.forEach { destination ->
            composeTestRule.onNodeWithContentDescription("Open ${destination.label}").assertIsDisplayed()
        }
    }
}
