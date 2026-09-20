package org.ort.app.ui.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * AC-179/FR-ANL-9: Settings shows three toggles, each stating what its tier sends in terms
 * matching FR-ANL-2..4. The gating and immediate-purge-on-disable behaviour itself is proven at
 * the `:telemetry` layer (`AnalyticsControllerTest`) — this screen-level suite only proves the
 * wording each toggle renders, and the honest D48 "not configured" state. AC-180's setup-step
 * equivalent lives in `SetupStateMachineTest`.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsAnalyticsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun state(
        tier1: Boolean = true,
        tier2: Boolean = false,
        tier3: Boolean = false,
        destinationConfigured: Boolean = false,
    ) = SettingsAnalyticsViewState(
        tier1Enabled = tier1,
        tier2Enabled = tier2,
        tier3Enabled = tier3,
        destinationConfigured = destinationConfigured,
        installIdLabel = "id ...abcd1234",
    )

    @Test
    @Requirement("AC-179", "FR-ANL-2")
    fun `AC_179_tier 1's row names crashes, usage and quality, never a transcript or callsign`() {
        composeTestRule.setContent {
            OrtTheme {
                SettingsAnalyticsScreen(state = state(), onBack = {
                }, onToggleTier1 = {}, onToggleTier2 = {}, onToggleTier3 = {}, onResetInstallId = {})
            }
        }

        composeTestRule.onNodeWithText("Crashes and ANRs", substring = true).assertExists()
        composeTestRule.onNodeWithText("transcript, callsign, name, station knowledge or location", substring = true)
            .assertExists()
    }

    @Test
    @Requirement("AC-179", "FR-ANL-3")
    fun `AC_179_tier 2's row names transcript text and callsigns`() {
        composeTestRule.setContent {
            OrtTheme {
                SettingsAnalyticsScreen(state = state(), onBack = {
                }, onToggleTier1 = {}, onToggleTier2 = {}, onToggleTier3 = {}, onResetInstallId = {})
            }
        }

        composeTestRule.onNodeWithText("Transcript text and callsigns", substring = true).assertExists()
    }

    @Test
    @Requirement("AC-179", "FR-ANL-4")
    fun `AC_179_tier 3's row names retained audio with its corrected transcript`() {
        composeTestRule.setContent {
            OrtTheme {
                SettingsAnalyticsScreen(state = state(), onBack = {
                }, onToggleTier1 = {}, onToggleTier2 = {}, onToggleTier3 = {}, onResetInstallId = {})
            }
        }

        composeTestRule.onNodeWithText("Retained over audio", substring = true).assertExists()
    }

    @Test
    fun `D48_an unconfigured destination states plainly that nothing is ever sent`() {
        composeTestRule.setContent {
            OrtTheme {
                SettingsAnalyticsScreen(
                    state = state(destinationConfigured = false),
                    onBack = {},
                    onToggleTier1 = {},
                    onToggleTier2 = {},
                    onToggleTier3 = {},
                    onResetInstallId = {},
                )
            }
        }

        composeTestRule.onNodeWithText("nothing is ever sent", substring = true).assertExists()
    }

    @Test
    @Requirement("AC-175", "FR-ANL-5")
    fun `AC_175_states that no tier combination ever carries a name, station knowledge or location`() {
        composeTestRule.setContent {
            OrtTheme {
                SettingsAnalyticsScreen(state = state(), onBack = {
                }, onToggleTier1 = {}, onToggleTier2 = {}, onToggleTier3 = {}, onResetInstallId = {})
            }
        }

        composeTestRule.onNodeWithText("never leaves the device through this channel", substring = true).assertExists()
    }
}
