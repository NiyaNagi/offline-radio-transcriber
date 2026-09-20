package org.ort.app.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.PrimaryButton
import org.ort.app.ui.components.ToggleRow
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtType

/**
 * P28 (D42, FR-ANL-10, AC-180) — shown once, after `MODELS`/before `READY`
 * ([SetupStateMachine]'s own gate, [SetupSnapshot.analyticsConsentSeen]); explains tier 1 in the
 * same terms [org.ort.app.ui.settings.SettingsAnalyticsScreen] uses (FR-ANL-9) and offers tiers 2
 * and 3 as an explicit, unchecked choice — declining both (leaving them off and tapping
 * `Continue`) leaves every other function fully working, the same guarantee `JurisdictionNoticeScreen`'s
 * own sibling gate ([SetupSnapshot.jurisdictionNoticeSeen]) already makes for its own step.
 *
 * **R-1085 (register; constitution I; D42/D48/FR-ANL-11/AC-180):** [destinationConfigured] is the
 * same D48 fact [org.ort.app.ui.settings.SettingsAnalyticsScreen] already renders one screen away —
 * before this fix, this screen took no such parameter and could only ever imply the two toggles
 * below share data, on the one surface most likely to set the operator's expectation, while in
 * this build nothing leaves the device whatever they choose. `design/canvas/Setup-Analytics-Consent
 * .dc.html` now exists and draws the corrected copy — the disclosure box below reuses that
 * artboard's own sentence verbatim, so the two screens never disagree about what "on" means.
 *
 * **R-1086 (register; constitution I; D42, FR-ANL-2):** the tier 1 paragraph used to say "crashes
 * and ANRs" — but no ANR-detection mechanism exists anywhere in this codebase: no watchdog runs,
 * and [org.ort.telemetry.AnalyticsTier1Payload.Crash.isAnr] is deliberately nullable ("never
 * measured") for exactly that reason ([org.ort.app.analytics.CrashPayloads]'s own doc comment). A
 * setup screen promising a category the app never collects is the same defect class as a
 * confident wrong callsign, arriving as copy. "and ANRs" is dropped here, from
 * `Setup-Analytics-Consent.dc.html`, from [org.ort.app.ui.settings.SettingsAnalyticsScreen]'s
 * equivalent row, and from `docs/privacy-policy.md`'s tier 1 description — all four said the same
 * false thing and now say the same true one.
 */
@Composable
public fun AnalyticsConsentScreen(
    tier2Enabled: Boolean,
    tier3Enabled: Boolean,
    destinationConfigured: Boolean,
    onToggleTier2: (Boolean) -> Unit,
    onToggleTier3: (Boolean) -> Unit,
    onContinue: () -> Unit,
) {
    SetupScaffold(
        step = SetupStep.ANALYTICS_CONSENT,
        title = "Help improve this app",
        subtitle = "On by default, closed to what it can ever contain",
        onBack = null,
        bottomActions = {
            PrimaryButton(
                text = "Continue",
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth().testTag("setup-analytics-continue"),
            )
        },
    ) {
        Text(
            text = "Usage and quality analytics are on: crashes, which screens and " +
                "actions are used, per-pass speed, capture uptime, the setup funnel, and aggregate " +
                "quality rates. Never a transcript, callsign, name, station knowledge or location. " +
                "Turn it off any time in Settings.",
            style = OrtType.bodyProse,
            color = OrtColors.textSecondary,
        )
        Text(
            text = if (destinationConfigured) {
                "Queued events send only while capture is not running."
            } else {
                "No destination is configured in this build — events queue on this phone and " +
                    "nothing is ever sent, whatever you choose below."
            },
            style = OrtType.cardBody,
            color = OrtColors.textFaint,
            modifier = Modifier
                .fillMaxWidth()
                .background(OrtColors.bgCard, RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 11.dp)
                .testTag("setup-analytics-destination-disclosure"),
        )
        ToggleRow(
            label = "Also share transcripts and callsigns",
            checked = tier2Enabled,
            onCheckedChange = onToggleTier2,
            subLine = "Off unless you turn it on — includes what the recognizer heard paired with " +
                "your correction.",
        )
        ToggleRow(
            label = "Also share audio",
            checked = tier3Enabled,
            onCheckedChange = onToggleTier3,
            subLine = "Off unless you turn it on — retained over audio with its corrected transcript.",
        )
    }
}
