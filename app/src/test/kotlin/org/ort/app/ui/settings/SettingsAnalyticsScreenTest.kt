package org.ort.app.ui.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
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

        composeTestRule.onNodeWithText("Crashes, screens and actions used", substring = true).assertExists()
        composeTestRule.onNodeWithText("transcript, callsign, name, station knowledge or location", substring = true)
            .assertExists()
    }

    /**
     * R-1086 (register; constitution I; D42, FR-ANL-2): tier 1's row used to say "Crashes and
     * ANRs", but no ANR-detection mechanism exists anywhere in this codebase — no watchdog runs,
     * and [org.ort.telemetry.AnalyticsTier1Payload.Crash.isAnr] is deliberately nullable
     * ("never measured") for exactly that reason ([org.ort.app.analytics.CrashPayloads]'s own doc
     * comment). A setup or settings screen promising a category the app never collects is the same
     * defect class as a confident wrong callsign, arriving as copy — so this asserts the word
     * never appears anywhere on this screen, not merely that one row's wording changed.
     */
    @Test
    @Requirement("R-1086", "FR-ANL-2")
    fun `R_1086_no row anywhere on this screen promises ANR collection`() {
        composeTestRule.setContent {
            OrtTheme {
                SettingsAnalyticsScreen(state = state(), onBack = {
                }, onToggleTier1 = {}, onToggleTier2 = {}, onToggleTier3 = {}, onResetInstallId = {})
            }
        }

        composeTestRule.onAllNodesWithText("ANR", substring = true, ignoreCase = true).assertCountEquals(0)
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

    /**
     * Register R-1193/R-1196 (constitution I; D42, FR-ANL-4). The tier-3 row states FR-ANL-4's field
     * list, as FR-ANL-9 requires — but **no tier-3 producer exists anywhere in this build**, so the
     * row on its own asks the operator to consent to sharing third parties' recorded audio and
     * describes a consequence no code can deliver. This asserts the correction: the screen says
     * plainly that no audio is collected here, the same shape `SettingsContributeScreen` already
     * uses for the contribution channel's missing upload client.
     *
     * The companion assertion — that the producer really is absent, which is what makes this copy
     * true rather than merely present — is `AnalyticsProducerReachabilityTest` in `:telemetry`.
     * That pair is this screen's answer to R-1196: a dead implementation satisfies neither.
     *
     * Asserted through the block's own stable tag rather than a bare `onNodeWithText` sweep of the
     * whole tree (R-1160, R-1070: this repo has twice shipped a Compose assertion that passed by
     * matching something other than the screen under test).
     */
    @Test
    @Requirement("AC-174", "FR-ANL-4", "R-1193")
    fun `AC_174_the opt-in block states that no audio is collected in this build`() {
        composeTestRule.setContent {
            OrtTheme {
                SettingsAnalyticsScreen(state = state(), onBack = {
                }, onToggleTier1 = {}, onToggleTier2 = {}, onToggleTier3 = {}, onResetInstallId = {})
            }
        }

        composeTestRule.onNodeWithTag(ANALYTICS_OPT_IN_NOT_COLLECTED_TAG)
            .assertTextContains("No audio is ever recorded for analytics here", substring = true)
    }

    /**
     * Register R-1193, the tier-2 half: `AnalyticsTier2Payload.Transcript` has no producer either,
     * and the one live tier-2 path (`CorrectionAnalytics`) sends the callsign pair alone. The row
     * above still states FR-ANL-3's field list because FR-ANL-9 requires it; this block is what
     * keeps that from being a promise of transcript collection the build never makes.
     */
    @Test
    @Requirement("AC-173", "FR-ANL-3", "R-1193")
    fun `AC_173_the opt-in block states that tier 2 collects only the callsign pair`() {
        composeTestRule.setContent {
            OrtTheme {
                SettingsAnalyticsScreen(state = state(), onBack = {
                }, onToggleTier1 = {}, onToggleTier2 = {}, onToggleTier3 = {}, onResetInstallId = {})
            }
        }

        composeTestRule.onNodeWithTag(ANALYTICS_OPT_IN_NOT_COLLECTED_TAG)
            .assertTextContains("no transcript text is collected", substring = true)
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
