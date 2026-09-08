package org.ort.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.DrillInHeader
import org.ort.app.ui.components.RadioRow
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/**
 * `Settings-Tier.dc.html` (P11, FR-TIER-1..7): states what this device therefore does *not* know
 * at a lower tier, plainly, per P11 — a weaker device knows less; it never shows a shakier number.
 * [SettingsTierViewState.currentTierLabel] comes from the same shed-level placeholder
 * `RealCaptureService.tierFromShedLevel()` already uses (no real tier detector exists — FR-TIER-1's
 * measured-throughput detector is unbuilt; this is named honestly rather than presented as one).
 */
@Composable
public fun SettingsTierScreen(
    state: SettingsTierViewState,
    onBack: () -> Unit,
    onSelectOverride: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DrillInHeader(parentLabel = "Settings", onBack = onBack)
        Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg)) {
            Text(text = "Tier and capability", style = OrtType.screenTitle, color = OrtColors.textHigh)
            Text(
                text = "A weaker device knows less. It is never more wrong.",
                style = OrtType.subtitle,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.sm),
            )

            Column(
                modifier = Modifier.fillMaxWidth().background(OrtColors.bgCard, SETTINGS_CARD_SHAPE).padding(14.dp),
            ) {
                Text(
                    text = "${state.currentTierLabel} of ${state.maxTierLabel}",
                    style = OrtType.figure,
                    color = OrtColors.textHigh,
                )
                Text(
                    text = "No measured tier detector exists yet (FR-TIER-1) — this number is derived from " +
                        "the shed-shedding level, the same placeholder the capture notification uses.",
                    style = OrtType.cardBody,
                    color = OrtColors.textDim,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            SectionHeader(label = "Override", modifier = Modifier.padding(top = OrtSpacing.lg))
            RadioRow(
                label = "Let the phone choose",
                selected = !state.isOverridden,
                onClick = { onSelectOverride(null) },
            )
            listOf("T0", "T1", "T2").forEach { tier ->
                RadioRow(
                    label = "Hold at $tier",
                    selected = state.isOverridden && state.overrideLabel == "Held at $tier",
                    onClick = { onSelectOverride(tier) },
                )
            }
            Column(modifier = Modifier.padding(bottom = OrtSpacing.lg)) {}
        }
    }
}
