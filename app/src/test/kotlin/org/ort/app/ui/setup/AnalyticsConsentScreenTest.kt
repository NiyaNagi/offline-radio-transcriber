package org.ort.app.ui.setup

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * R-1085 (register; constitution I; D42/D48/FR-ANL-11/AC-180): before this fix,
 * [AnalyticsConsentScreen] had no [destinationConfigured] parameter at all and could only ever
 * imply the two toggles below share data — on the one screen an operator sees before ever choosing
 * a tier — while in this build nothing leaves the device whatever they choose.
 * [org.ort.app.ui.settings.SettingsAnalyticsScreenTest]'s own `D48_an unconfigured destination
 * states plainly that nothing is ever sent` test is this suite's sibling; both screens must state
 * the identical fact so they cannot disagree about what "on" means (`design/canvas/
 * Setup-Analytics-Consent.dc.html`).
 */
@RunWith(RobolectricTestRunner::class)
class AnalyticsConsentScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    @Requirement("R-1085", "D48", "FR-ANL-11", "AC-180")
    fun `R_1085_an unconfigured destination states plainly that nothing is ever sent`() {
        composeTestRule.setContent {
            OrtTheme {
                AnalyticsConsentScreen(
                    tier2Enabled = false,
                    tier3Enabled = false,
                    destinationConfigured = false,
                    onToggleTier2 = {},
                    onToggleTier3 = {},
                    onContinue = {},
                )
            }
        }

        composeTestRule.onNodeWithText("nothing is ever sent", substring = true).assertExists()
    }

    @Test
    @Requirement("R-1085", "D48")
    fun `R_1085_a configured destination states the queued-during-capture caveat instead`() {
        composeTestRule.setContent {
            OrtTheme {
                AnalyticsConsentScreen(
                    tier2Enabled = false,
                    tier3Enabled = false,
                    destinationConfigured = true,
                    onToggleTier2 = {},
                    onToggleTier3 = {},
                    onContinue = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Queued events send only while capture is not running.", substring = true)
            .assertExists()
    }

    @Test
    @Requirement("R-1085")
    fun `R_1085_the two screens never disagree — same unconfigured sentence as Settings`() {
        // Sibling assertion to SettingsAnalyticsScreenTest's own
        // `D48_an unconfigured destination states plainly that nothing is ever sent` — both
        // screens must render the identical base clause, not two independently-worded claims.
        composeTestRule.setContent {
            OrtTheme {
                AnalyticsConsentScreen(
                    tier2Enabled = false,
                    tier3Enabled = false,
                    destinationConfigured = false,
                    onToggleTier2 = {},
                    onToggleTier3 = {},
                    onContinue = {},
                )
            }
        }

        composeTestRule
            .onNodeWithText(
                "No destination is configured in this build — events queue on this phone and " +
                    "nothing is ever sent, whatever you choose below.",
                substring = false,
            )
            .assertExists()
    }

    /**
     * R-1086 (register; constitution I; D42, FR-ANL-2): the tier 1 paragraph used to say "crashes
     * and ANRs", but no ANR-detection mechanism exists anywhere in this codebase — no watchdog
     * runs, and [org.ort.telemetry.AnalyticsTier1Payload.Crash.isAnr] is deliberately nullable
     * ("never measured") for exactly that reason ([org.ort.app.analytics.CrashPayloads]'s own doc
     * comment). This is the setup step where the operator first decides on analytics at all, so
     * asserts the word never appears anywhere on it, not merely that one paragraph's wording moved.
     */
    @Test
    @Requirement("R-1086", "FR-ANL-2")
    fun `R_1086_no text anywhere on this screen promises ANR collection`() {
        composeTestRule.setContent {
            OrtTheme {
                AnalyticsConsentScreen(
                    tier2Enabled = false,
                    tier3Enabled = false,
                    destinationConfigured = false,
                    onToggleTier2 = {},
                    onToggleTier3 = {},
                    onContinue = {},
                )
            }
        }

        composeTestRule.onAllNodesWithText("ANR", substring = true, ignoreCase = true).assertCountEquals(0)
    }
}
