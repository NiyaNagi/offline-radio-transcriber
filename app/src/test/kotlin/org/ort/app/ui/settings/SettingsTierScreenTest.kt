package org.ort.app.ui.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * CF05 (`Settings-Tier.dc.html`, amended 2026-09-10, FR-DIG-3b, D5, `spec/e2e-capture-modes-plan.md`
 * E2-F06): tier 3 names the prose-digest capability; the phrase never appears attached to a lower
 * tier, since only tier 3 ever loads the bundled language model.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsTierScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun state() = SettingsTierViewState(
        currentTierLabel = "3",
        maxTierLabel = "3",
        overrideLabel = "Let the phone choose",
        isOverridden = false,
    )

    @Test
    @Requirement("FR-DIG-3b")
    fun `E2_F06 tier 3 names the prose-digest capability, never for callsigns`() {
        composeTestRule.setContent {
            OrtTheme { SettingsTierScreen(state = state(), onBack = {}, onSelectOverride = {}) }
        }

        composeTestRule
            .onNodeWithText(
                "The only tier that loads the bundled language model — for prose summaries, never for callsigns.",
                substring = true,
            )
            .assertExists()
    }

    @Test
    @Requirement("FR-DIG-3b")
    fun `E2_F06 the language-model sentence is not duplicated onto tier 1 or tier 2's own detail`() {
        composeTestRule.setContent {
            OrtTheme { SettingsTierScreen(state = state(), onBack = {}, onSelectOverride = {}) }
        }

        // Exactly one node carries the phrase (tier 3's own) — a lower tier repeating it would be
        // a false claim (constitution I: a weaker device knows less, it is never more wrong).
        composeTestRule
            .onNodeWithText("field-phone tier", substring = true)
            .assertExists()
        composeTestRule
            .onNodeWithText("Where this phone lands when warm", substring = true)
            .assertExists()
    }

    @Test
    @Requirement("FR-DIG-3b")
    fun `R_932 the override rows read prose tier names, never the T0-T2 abbreviations`() {
        composeTestRule.setContent {
            OrtTheme { SettingsTierScreen(state = state(), onBack = {}, onSelectOverride = {}) }
        }

        composeTestRule.onNodeWithText("Hold at tier 0").assertExists()
        composeTestRule.onNodeWithText("Hold at tier 1").assertExists()
        composeTestRule.onNodeWithText("Hold at tier 2").assertExists()
        composeTestRule.onNodeWithText("Hold at T0").assertDoesNotExist()
        composeTestRule.onNodeWithText("Hold at T1").assertDoesNotExist()
        composeTestRule.onNodeWithText("Hold at T2").assertDoesNotExist()
    }

    @Test
    @Requirement("FR-DIG-3b")
    fun `R_932 picking a prose tier row still calls back with the real T-id the store expects`() {
        var picked: String? = null
        composeTestRule.setContent {
            OrtTheme { SettingsTierScreen(state = state(), onBack = {}, onSelectOverride = { picked = it }) }
        }

        composeTestRule.onNodeWithText("Hold at tier 2").performScrollTo().performClick()

        assert(picked == "T2") { "expected the real override id T2, got $picked" }
    }
}
