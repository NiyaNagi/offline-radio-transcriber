@file:Suppress("MatchingDeclarationName") // RadioPickerViewState is one of several public declarations here.

package org.ort.app.ui.setup

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.ort.app.ui.components.Banner
import org.ort.app.ui.components.BannerTone
import org.ort.app.ui.components.OrtIcons
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.setup.RigPickerCatalogue.pickerOrder
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtType
import org.ort.rig.NullRigModule
import org.ort.rig.catalogue.RigCatalogue
import org.ort.rig.catalogue.RigCatalogueEntry

/** S09's whole view-state (`Setup-Rig.dc.html`, D33/FR-RIG-16). [presetLabel] is `null` once the
 * operator has overridden the rig-transport preset ([SetupStore.modeOverriddenRig]). [importError]
 * is the message from a rejected `Import it` attempt (FR-RIG-19), cleared on the next successful
 * pick. */
public data class RadioPickerViewState(
    val catalogue: RigCatalogue,
    val presetLabel: String? = null,
    val importError: String? = null,
)

/**
 * S09 (`Setup-Rig.dc.html`, D33, FR-RIG-1/16/17/18/19) — generated from `:rig`'s [RigCatalogue]
 * (via [RigPickerCatalogue]), never a hardcoded three-row list. Every entry is a [NavigationRow]
 * with the catalogue's own generated capability sub-line; the null module and the generic entry
 * are the picker's last two rows (AC-135), immediately above `Import it`. Choosing a real rig
 * moves to S09b ([SetupStep.RIG_TRANSPORT]); choosing the null module ("No radio") or the bottom
 * `Not now` both mean the same thing S09 has always meant for that choice — manual frequency entry
 * (R-344's fix, unchanged).
 */
@Composable
public fun RadioScreen(
    state: RadioPickerViewState,
    onChoose: (RigCatalogueEntry) -> Unit,
    onImport: () -> Unit,
    onNotNow: () -> Unit,
    banner: String? = null,
) {
    SetupScaffold(
        step = SetupStep.RADIO,
        title = "Radio",
        subtitle = "Read the frequency and squelch from the rig itself",
        onBack = null,
        titleOptional = true,
        bottomActions = {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextAction(
                    text = "Not now — you can connect one later from Settings",
                    onClick = onNotNow,
                    modifier = Modifier.testTag("setup-radio-not-now"),
                )
            }
        },
    ) {
        banner?.let {
            Banner(
                title = "The rig connection was lost",
                body = it,
                tone = BannerTone.DEGRADED,
                modifier = Modifier.fillMaxWidth().testTag("setup-radio-absent-banner"),
            )
        }
        Text(
            text = "Without a radio connection every over is logged against the frequency you " +
                "type in. With one, the app reads the frequency, mode and squelch per band as " +
                "they change — and on a dual-band rig, attributes each over to the band whose " +
                "squelch opened.",
            style = OrtType.bodyProse,
            color = OrtColors.textSecondary,
        )
        state.presetLabel?.let { PresetChip(modeLabel = it, modifier = Modifier.testTag("setup-radio-preset-chip")) }
        Column {
            state.catalogue.pickerOrder().forEach { entry ->
                NavigationRow(
                    title = entry.displayName,
                    subtitle = RigPickerCatalogue.subtitleFor(entry),
                    onClick = { onChoose(entry) },
                    modifier = Modifier.testTag("setup-radio-entry-${entry.id}"),
                    icon = if (entry.id == NullRigModule.ID) OrtIcons.frequencies else OrtIcons.rig,
                )
            }
        }
        Row(modifier = Modifier.testTag("setup-radio-import-row")) {
            Text(
                text = "Have a descriptor file for another rig?",
                style = OrtType.cardBody,
                color = OrtColors.textDim,
            )
            TextAction(text = "Import it", onClick = onImport, modifier = Modifier.testTag("setup-radio-import"))
        }
        state.importError?.let {
            Text(
                text = it,
                style = OrtType.cardBody,
                color = OrtColors.accentAmberText,
                modifier = Modifier.testTag("setup-radio-import-error"),
            )
        }
    }
}
