package org.ort.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
 * AC-63 / FR-A11Y-2, FR-A11Y-3, FR-PLT-6: the reader survives maximum system font scale without
 * clipping, and every interactive element carries a content description.
 *
 * FR-PLT-6's font-scale clause is established by the same evidence as FR-A11Y-3 below: nothing in
 * `:app` overrides `LocalDensity`'s `fontScale`, so respecting it is a structural property of not
 * fighting the system setting, and this test is what proves the app does not silently clip when
 * that setting is honoured. FR-PLT-6's other three clauses — per-app language preference, system
 * contrast, and reduced motion — are NOT established here, because no code reads any of them: no
 * per-app language config, no `LocalContrast`/high-contrast handling, no reduced-motion check
 * anywhere in `:app` (confirmed by search). They are not built, not merely untested.
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
    fun `AC_63_FR_A11Y_3_FR_PLT_6 the status screen renders every field without clipping at maximum font scale`() {
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
    fun `AC_63_FR_A11Y_2 every interactive element in the nav host carries a content description`() {
        composeTestRule.setContent {
            OrtTheme {
                OrtNavHost(sessionId = null)
            }
        }

        composeTestRule.onNodeWithContentDescription("Open navigation drawer").assertIsDisplayed().performClick()

        // build-plan P15 grew the drawer to ten destinations (Search, Threads) — more than fit
        // in the drawer's fixed height at once, so it scrolls (see ReaderDrawerContent). Each row
        // must still be reachable and displayed once scrolled to; the drawer is not allowed to
        // silently clip a destination that overflows it.
        ReaderDestination.entries.forEach { destination ->
            composeTestRule.onNodeWithContentDescription("Open ${destination.label}")
                .performScrollTo()
                .assertIsDisplayed()
        }
    }
}
