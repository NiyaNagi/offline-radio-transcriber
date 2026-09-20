package org.ort.app.ui.setup

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
 * own sibling gate ([SetupSnapshot.jurisdictionNoticeSeen]) already makes for its own step. No
 * artboard exists for this screen yet (the `design` tree is outside this unit's file ownership) —
 * built to this package's own established [SetupScaffold] shape rather than left undrawn, the same
 * choice that screen's own doc comment already made.
 */
@Composable
public fun AnalyticsConsentScreen(
    tier2Enabled: Boolean,
    tier3Enabled: Boolean,
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
            text = "Usage and quality analytics are on: crashes and ANRs, which screens and " +
                "actions are used, per-pass speed, capture uptime, the setup funnel, and aggregate " +
                "quality rates. Never a transcript, callsign, name, station knowledge or location. " +
                "Turn it off any time in Settings.",
            style = OrtType.bodyProse,
            color = OrtColors.textSecondary,
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
