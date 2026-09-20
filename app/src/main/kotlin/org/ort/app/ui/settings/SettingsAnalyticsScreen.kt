package org.ort.app.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.ort.app.analytics.AnalyticsAppWiring
import org.ort.app.ui.components.DrillInHeader
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.components.ToggleRow
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType
import org.ort.telemetry.AnalyticsTier

/**
 * P28 (D42, FR-ANL-1..14): three toggles, one per tier, each stating in plain language what that
 * tier sends when on (FR-ANL-9) — matching FR-ANL-2..4's own field lists verbatim, the same
 * "prose that becomes an FR-tested claim" discipline `SettingsContributeScreen.kt` already applies
 * to its own category list. Tier 1's toggle can turn tier 1 off entirely (FR-ANL-1); tiers 2 and 3
 * are opt-in and start unchecked. [SettingsAnalyticsViewState.destinationConfigured] renders the
 * honest D48 state: `false` (the default today, no endpoint deployed) means every event queues
 * on-device and nothing is ever sent, whatever the three toggles say.
 */
@Composable
public fun SettingsAnalyticsScreen(
    state: SettingsAnalyticsViewState,
    onBack: () -> Unit,
    onToggleTier1: (Boolean) -> Unit,
    onToggleTier2: (Boolean) -> Unit,
    onToggleTier3: (Boolean) -> Unit,
    onResetInstallId: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DrillInHeader(parentLabel = "Settings", onBack = onBack)
        Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg)) {
            Text(text = "Analytics", style = OrtType.screenTitle, color = OrtColors.textHigh)
            Text(
                text = if (state.destinationConfigured) {
                    "Queued events send only while capture is not running."
                } else {
                    "No destination is configured in this build — events queue on this phone and " +
                        "nothing is ever sent."
                },
                style = OrtType.subtitle,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.sm),
            )

            SectionHeader(label = "On by default", modifier = Modifier.padding(top = OrtSpacing.md))
            ToggleRow(
                label = "Usage and quality (tier 1)",
                checked = state.tier1Enabled,
                onCheckedChange = onToggleTier1,
                subLine = "Crashes and ANRs, screens and actions used, per-pass speed, capture " +
                    "uptime, the setup funnel, and aggregate quality rates — never a transcript, " +
                    "callsign, name, station knowledge or location.",
            )

            SectionHeader(label = "Opt-in", modifier = Modifier.padding(top = OrtSpacing.md))
            ToggleRow(
                label = "Transcripts and callsigns (tier 2)",
                checked = state.tier2Enabled,
                onCheckedChange = onToggleTier2,
                subLine = "Transcript text and callsigns, including what the recognizer heard " +
                    "paired with your correction.",
            )
            ToggleRow(
                label = "Audio (tier 3)",
                checked = state.tier3Enabled,
                onCheckedChange = onToggleTier3,
                subLine = "Retained over audio together with its corrected transcript.",
            )

            SectionHeader(label = "Never in any tier", modifier = Modifier.padding(top = OrtSpacing.md))
            Text(
                text = "A user-supplied name, station knowledge, or location finer than a grid " +
                    "square never leaves the device through this channel, in any combination of " +
                    "tiers above.",
                style = OrtType.cardBody,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.md),
            )

            SectionHeader(label = "This install", modifier = Modifier.padding(top = OrtSpacing.md))
            Text(
                text = state.installIdLabel,
                style = OrtType.cardBody,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs),
            )
            org.ort.app.ui.components.NavRow(
                rowTitle = "Reset install id",
                subLine = "Starts a new, unlinked id and asks the destination to erase every row " +
                    "tied to the old one.",
                onClick = onResetInstallId,
                icon = org.ort.app.ui.components.OrtIcons.lock,
            )
        }
    }
}

/** [SettingsAnalyticsScreen]'s own state-builder — deliberately not added to `SettingsPolling.kt`
 * (outside this unit's narrow, named integration point; see `SettingsRootScreen.kt`'s own doc
 * comment on the identical choice P27 made for `SettingsLicensesScreen`). Calls
 * [AnalyticsAppWiring.configureOnce] defensively so this screen renders real state even if
 * `OrtApplication.onCreate` has not run yet in whatever hosts it (a Robolectric test's own
 * `Application`, `OrtApplication`, never calls it at all — see that class's own Robolectric guard). */
public object SettingsAnalyticsPolling {
    public fun current(context: Context): SettingsAnalyticsViewState {
        AnalyticsAppWiring.configureOnce(context)
        val installId = AnalyticsAppWiring.installIdStore.currentId()
        return SettingsAnalyticsViewState(
            tier1Enabled = AnalyticsAppWiring.tierPreferences.isEnabled(AnalyticsTier.TIER_1),
            tier2Enabled = AnalyticsAppWiring.tierPreferences.isEnabled(AnalyticsTier.TIER_2),
            tier3Enabled = AnalyticsAppWiring.tierPreferences.isEnabled(AnalyticsTier.TIER_3),
            destinationConfigured = AnalyticsAppWiring.isDestinationConfigured(),
            installIdLabel = "id ...${installId.takeLast(SHORT_ID_CHARS)}",
        )
    }

    private const val SHORT_ID_CHARS = 8
}
