package org.ort.app.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.PrimaryButton
import org.ort.app.ui.components.RadioRow
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtType
import org.ort.rig.RigTransportKind

/** S09b's whole view-state (`Setup-Rig-Transport.dc.html`, D33, FR-RIG-13/14/17). [options] is
 * only ever the transports [org.ort.rig.catalogue.RigCatalogueEntry.transportCapabilities]
 * actually declares for the chosen rig, in board order (Bluetooth SPP, then USB serial) — never a
 * fixed pair assumed for every rig. [presetKind] (D33/FR-CAP-9) is `null` once the operator has
 * overridden the axis; the preset label is shown per-card in [RigTransportOption.subLabel], not as
 * a separate chip, matching the board. */
public data class RigTransportViewState(
    val rigDisplayName: String,
    val options: List<RigTransportOption>,
    val selected: RigTransportKind?,
)

public data class RigTransportOption(
    val kind: RigTransportKind,
    val label: String,
    val subLabel: String,
    val capabilities: List<String>,
    val costLine: String,
    val isPreset: Boolean,
)

/**
 * S09b (`Setup-Rig-Transport.dc.html`, D33) — "how is the rig linked?" for the rig chosen at S09.
 * Both declared transports, generated capabilities, the preset marked, the cost of each stated.
 * `Connect over …` names the currently-selected transport ([RigTransportViewState.selected]) and
 * moves to S10 (USB) or S10b (Bluetooth); `Back` returns to S09.
 */
@Composable
public fun RigTransportScreen(
    state: RigTransportViewState,
    onSelect: (RigTransportKind) -> Unit,
    onConnect: () -> Unit,
    onBack: () -> Unit,
) {
    SetupScaffold(
        step = SetupStep.RIG_TRANSPORT,
        title = "How is the ${state.rigDisplayName} linked?",
        subtitle = "The same command set on either. Pick the one you have wired.",
        onBack = onBack,
        bottomActions = {
            PrimaryButton(
                text = "Connect over ${state.selected?.let(RigPickerCatalogue::transportLabel) ?: "…"}",
                onClick = onConnect,
                enabled = state.selected != null,
                modifier = Modifier.fillMaxWidth().testTag("setup-rig-transport-connect"),
            )
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextAction(text = "Back", onClick = onBack, modifier = Modifier.testTag("setup-rig-transport-back"))
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.options.forEach { option ->
                TransportCard(
                    option = option,
                    selected = option.kind == state.selected,
                    onSelect = { onSelect(option.kind) },
                )
            }
        }
        Text(
            text = "Both links carry the CAT data losslessly. This is the wireless choice that " +
                "costs nothing — audio over Bluetooth, chosen back on the Input step, is the " +
                "one that does.",
            style = OrtType.cardBody,
            color = OrtColors.textDim,
        )
    }
}

@Composable
private fun TransportCard(option: RigTransportOption, selected: Boolean, onSelect: () -> Unit) {
    // The whole card is tappable, not only the RadioRow's own line -- the capability bullets and
    // the cost line underneath are part of choosing this transport too (`Setup-Rig-Transport.dc.html`
    // draws the entire card as one unit). RadioRow keeps its own selectable() for the dot/label
    // itself; both point at the identical onSelect, so a tap anywhere in the card and a tap
    // narrowly on the row agree.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, if (selected) OrtColors.accentGreenDim else OrtColors.lineDefault, RoundedCornerShape(10.dp))
            .background(OrtColors.bgCard, RoundedCornerShape(10.dp))
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(14.dp)
            .testTag("setup-rig-transport-option-${option.kind.name.lowercase()}"),
    ) {
        RadioRow(label = option.label, selected = selected, onClick = onSelect, subtitle = option.subLabel)
        Column(
            modifier = Modifier.padding(start = 32.dp, top = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (option.capabilities.isNotEmpty()) {
                Text(
                    text = option.capabilities.joinToString(", ").replaceFirstChar { it.uppercase() },
                    style = OrtType.chip,
                    color = OrtColors.textDim,
                )
            }
            Text(text = option.costLine, style = OrtType.chip, color = OrtColors.accentAmberText)
        }
    }
}
