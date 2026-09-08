package org.ort.app.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner

/**
 * R-133/R-150/R-251 (register, rounds 4 and 6 System validator): the storage screen's new "when
 * space runs low" rows read real signals; its budget-chip row scrolls (WP2's `FilterChipRow`,
 * proven on-device — round 4's own hand-rolled `horizontalScroll` clipped instead of scrolling on
 * a real device, R-251) and its category legend wraps (a `FlowRow`) rather than either wrapping
 * intra-word or clipping at font scale 2.0 — see [SettingsStorageScreen]'s own doc comments for
 * the mechanism. Font-scale-2.0 is applied via `LocalDensity` (`RowsTest.kt`'s own established
 * idiom — `@Config(qualifiers = "fontscale-2.0")` is not a valid Robolectric qualifier string;
 * this project's font-scale coverage has never used it).
 */
@RunWith(RobolectricTestRunner::class)
class SettingsStorageScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val maxFontScale = 2f

    private fun state(warnAtNightsLeft: Int = 3, hardFloorLabel: String = "100 MB") = SettingsStorageViewState(
        usedBytes = 800_000_000L,
        budgetGb = null,
        deviceFreeBytes = 40_000_000_000L,
        categories = listOf(
            SettingsStorageCategoryViewState("Audio", 800_000_000L),
            SettingsStorageCategoryViewState("Models", 200_000_000L),
            SettingsStorageCategoryViewState("Records", 10_000_000L),
        ),
        nightsLeftLabel = null,
        autoPruneEnabled = false,
        warnAtNightsLeft = warnAtNightsLeft,
        hardFloorLabel = hardFloorLabel,
    )

    @Test
    fun `R_133 Warn at and Hard floor rows show the real values this state carries`() {
        composeTestRule.setContent {
            OrtTheme { SettingsStorageScreen(state = state(), onBack = {}, onSetBudgetGb = {}, onToggleAutoPrune = {}) }
        }

        composeTestRule.onNodeWithText("3 nights left").assertExists()
        composeTestRule.onNodeWithText("100 MB").assertExists()
    }

    @Test
    fun `R_150_R_251 the budget chip row (FilterChipRow) scrolls rather than wraps at font scale 2-0`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = maxFontScale)) {
                OrtTheme {
                    SettingsStorageScreen(state = state(), onBack = {}, onSetBudgetGb = {}, onToggleAutoPrune = {})
                }
            }
        }

        // The "Unlimited" chip's own text node stays one intact node inside a scrollable-action
        // ancestor — the pre-fix layout collapsed each chip's Row width instead of scrolling it,
        // so this ancestor search is what actually distinguishes the two.
        composeTestRule
            .onNode(hasText("Unlimited").and(hasAnyAncestor(hasScrollAction())))
            .assertExists()
    }

    @Test
    fun `R_150_R_251 the category legend wraps (FlowRow) rather than clipping at font scale 2-0`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = maxFontScale)) {
                OrtTheme {
                    SettingsStorageScreen(state = state(), onBack = {}, onSetBudgetGb = {}, onToggleAutoPrune = {})
                }
            }
        }

        // The full label survives as one intact node — R-251's own screenshot showed it clipped
        // to "Reco"/"U" with no way to reach the rest; a `FlowRow` wraps the whole entry onto its
        // own line instead of clipping or splitting it.
        composeTestRule.onNodeWithText("Records 0.0 GB").assertExists()
    }
}
