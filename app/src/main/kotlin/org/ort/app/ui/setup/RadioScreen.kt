@file:Suppress("MatchingDeclarationName") // RadioPickerViewState is one of several public declarations here.

package org.ort.app.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
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
 * pick. [emphasizedEntryId] (R-815, reviewer finding) names the one catalogue entry id the board
 * draws with the emphasized weight-500/`text/high`/green-icon treatment — the currently
 * chosen/preset rig ([SetupStore.rigId]) if one is already recorded, else the catalogue's own
 * verified entry; `null` emphasizes nothing (a catalogue with no verified entry and nothing chosen
 * yet). */
public data class RadioPickerViewState(
    val catalogue: RigCatalogue,
    val presetLabel: String? = null,
    val importError: String? = null,
    val emphasizedEntryId: String? = null,
)

/** R-813 (reviewer finding): the null module's own [RigCatalogueEntry.displayName]
 * (`NullRigModule.DISPLAY_NAME`, "Manual (no rig connected)") is a generic, catalogue-wide label —
 * the board's own copy for this row is specific to S09's own "I have no rig at all" framing and is
 * rendered here regardless of what the catalogue entry itself is named. */
internal fun displayNameFor(entry: RigCatalogueEntry): String =
    if (entry.id == NullRigModule.ID) "No radio — I will enter the frequency" else entry.displayName

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
                val emphasized = entry.id == state.emphasizedEntryId
                NavigationRow(
                    title = displayNameFor(entry),
                    subtitle = RigPickerCatalogue.subtitleFor(entry),
                    onClick = { onChoose(entry) },
                    modifier = Modifier.testTag("setup-radio-entry-${entry.id}"),
                    titleColor = if (emphasized) OrtColors.textHigh else OrtColors.textBody,
                    icon = if (entry.id == NullRigModule.ID) OrtIcons.frequencies else OrtIcons.rig,
                    iconTint = if (emphasized) OrtColors.accentGreen else OrtColors.textIconDim,
                )
            }
        }
        // R-814 (reviewer finding): one flex row, gap 8dp, matching Setup-Rig.dc.html's own
        // `display: flex; align-items: center; gap: 8px` markup for this line -- previously an
        // unaligned Row with no spacing, which laid the two children out with no gap at all.
        Row(
            modifier = Modifier.testTag("setup-radio-import-row"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
