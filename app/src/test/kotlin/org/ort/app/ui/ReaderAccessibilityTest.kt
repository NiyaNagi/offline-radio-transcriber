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
import org.ort.app.ui.data.CaptureStateTone
import org.ort.app.ui.data.CaptureStatusViewState
import org.ort.app.ui.data.KeyValueFacts
import org.ort.app.ui.navigation.OrtNavHost
import org.ort.app.ui.navigation.ReaderDestination
import org.ort.app.ui.screens.CaptureStatusScreen
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
    fun `AC_63_FR_A11Y_3_FR_PLT_6 capture status renders every field without clipping at maximum font scale`() {
        // ui-conformance-plan WP4 (R-031/R-033): StatusScreen/StatusViewState were replaced by
        // CaptureStatusScreen/CaptureStatusViewState — this test's own file is outside WP4's row
        // (it lives at `ui/ReaderAccessibilityTest.kt`, not `ui/screens/`), but deleting
        // `StatusScreen.kt` (this package's brief's own instruction) would otherwise leave it
        // referencing a file that no longer exists. Updated to the minimum needed to keep proving
        // the same fact (AC-63/FR-A11Y-3) against the surface that carries it now.
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = maxFontScale)) {
                OrtTheme {
                    CaptureStatusScreen(
                        state = CaptureStatusViewState(
                            stateLabel = "Capturing",
                            stateTone = CaptureStateTone.NOMINAL,
                            sinceElapsedLabel = "Since 23:32 · 00:05:00 · alive, heartbeat 4s ago",
                            haltActionLabel = "Stop",
                            haltConfirmTitle = "Stop capture?",
                            haltConfirmBody = "Audio already captured is kept.",
                            input = KeyValueFacts(value = "Not measured"),
                            level = KeyValueFacts(value = "Not measured"),
                            radio = KeyValueFacts(value = "No rig configured"),
                            overs = KeyValueFacts(value = "3 captured", subLine = "0 rejected · 0 failed · 1 gaps"),
                            backlog = KeyValueFacts(value = "0 overs waiting"),
                            tier = KeyValueFacts(
                                value = "2 of 3",
                                subLine = "no model — see Models · energy VAD (not Silero)",
                            ),
                            thermal = KeyValueFacts(value = "Nominal", subLine = "RTF not yet measured"),
                            storage = KeyValueFacts(value = "0.0 GB", subLine = "not yet measured this session"),
                            battery = KeyValueFacts(
                                value = "Not measured",
                                subLine = "exemption reports off — not trusted",
                            ),
                        ),
                    )
                }
            }
        }

        // Every field must still be laid out and reachable — a clipped node fails assertIsDisplayed
        // (zero size, or entirely outside its parent's bounds) even after scrolling to it. This
        // screen scrolls by design (guide §5's page-margin rule applies to a dense, multi-section
        // surface), so a field further down is reached the same way `AC_63_FR_A11Y_2` below reaches
        // an overflowing drawer row: scroll to it, then assert.
        composeTestRule.onNodeWithText("Capturing").assertIsDisplayed()
        composeTestRule.onNodeWithText("Since 23:32 · 00:05:00 · alive, heartbeat 4s ago").assertIsDisplayed()
        composeTestRule.onNodeWithText("3 captured").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("2 of 3").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Nominal").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("No rig configured").performScrollTo().assertIsDisplayed()
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
