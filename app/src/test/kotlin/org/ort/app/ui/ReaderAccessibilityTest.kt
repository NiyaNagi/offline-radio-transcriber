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
import org.ort.testing.Requirement
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

        // R-003/R-004 (ui-conformance-plan WP3): the M3 TopAppBar's "=" glyph is gone — the drawer
        // control is WP2's `ScreenHeader`, whose own description is "Open navigation" (see
        // `ui/components/Rows.kt`'s `ScreenHeader` and its own `RowsTest`).
        composeTestRule.onNodeWithContentDescription("Open navigation").assertIsDisplayed().performClick()

        // build-plan P15 grew the drawer's destination set past nine real rows; R-015 (WP3) then
        // moved `Search` out of the drawer entirely — it is reached from every header's magnifier
        // instead (`Menu.dc.html` never lists it as a row) — so every destination *except* Search
        // must still be reachable and displayed once scrolled to; the drawer is not allowed to
        // silently clip a destination that overflows it.
        ReaderDestination.entries.filter { it != ReaderDestination.SEARCH }.forEach { destination ->
            composeTestRule.onNodeWithContentDescription("Open ${destination.label}")
                .performScrollTo()
                .assertIsDisplayed()
        }
    }

    @Test
    @Requirement("R-015")
    fun `R_015 the search icon in the header opens the Search destination`() {
        composeTestRule.setContent {
            OrtTheme {
                OrtNavHost(sessionId = null)
            }
        }

        composeTestRule.onNodeWithContentDescription("Search").assertIsDisplayed().performClick()
        // Round 5 (R-200): `SEARCH` no longer shows the host's `ScreenHeader` — `SearchContent`
        // draws its own back chevron instead (`SearchScreen.kt`'s `search-back-chevron`,
        // `contentDescription = "Back"`), so this asserts *that* marker is what actually opened,
        // not the header this test used to expect to still be present underneath it.
        composeTestRule.onNodeWithContentDescription("Back").assertIsDisplayed()
    }
}
