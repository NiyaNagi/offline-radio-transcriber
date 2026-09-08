package org.ort.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.ort.app.ui.components.DrillInHeader
import org.ort.app.ui.components.FailedState
import org.ort.app.ui.components.KeyValueRow
import org.ort.app.ui.components.SecondaryButton
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.components.Tile
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/**
 * `Settings-Rig.dc.html` (FR-RIG). FR-RIG-1..12 describe a full rig-module contract with a
 * built-in TH-D75A module and a null module for manual entry — none of it is built yet (register
 * R-084's plan-level note), so [SettingsRigViewState.connected] is `false` on every build today.
 * This renders that honestly as a [FailedState] rather than a fabricated "Reconnect"/"Change radio"
 * pair with nothing behind them.
 */
@Composable
public fun SettingsRigScreen(state: SettingsRigViewState, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DrillInHeader(parentLabel = "Settings", onBack = onBack)
        Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg)) {
            Text(text = state.descriptorLabel, style = OrtType.screenTitle, color = OrtColors.textHigh)
            Text(
                text = if (state.connected) "Connected" else "Not connected",
                style = OrtType.subtitle,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.sm),
            )

            if (state.bands.isNotEmpty()) {
                SectionHeader(label = "Bands", modifier = Modifier.padding(top = OrtSpacing.md))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = OrtSpacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
                ) {
                    state.bands.forEach { band ->
                        Tile(
                            figure = band.frequencyLabel,
                            caption = "${band.label} · ${band.statusLabel}",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            if (!state.connected) {
                FailedState(
                    title = "No rig module is connected",
                    body = "FR-RIG's module contract (a built-in TH-D75A driver and a manual-entry null " +
                        "module) is not built yet. Capture is unaffected — frequencies are logged from " +
                        "Settings › Input and level's manual entry until a rig module exists.",
                    modifier = Modifier.padding(top = OrtSpacing.lg),
                )
            } else {
                state.staleSinceLabel?.let {
                    KeyValueRow(key = "Status", value = "Stale", subLine = "last known reading, $it")
                }
            }

            SecondaryButton(
                text = "Reconnect",
                enabled = false,
                onClick = {},
                modifier = Modifier.fillMaxWidth().padding(top = OrtSpacing.lg, bottom = OrtSpacing.lg),
            )
        }
    }
}
