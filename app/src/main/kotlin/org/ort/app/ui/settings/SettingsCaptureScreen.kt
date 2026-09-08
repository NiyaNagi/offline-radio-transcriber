package org.ort.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.ort.app.ui.components.DrillInHeader
import org.ort.app.ui.components.KeyValueRow
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.components.TextField
import org.ort.app.ui.components.ToggleRow
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/**
 * `Settings-Capture.dc.html`: input/level facts from `InputStatus`/`LevelStatus` (WP11c).
 *
 * R-132 (round 4, System validator): [onOpenInputSetup] backs both `Device`'s `Change` and
 * `Re-verify the route now`'s `Verify` — both real actions land on the same place in this build
 * (`SetupActivity` reopened at its `INPUT` step, `SettingsContent`'s own doc comment says exactly
 * why: there is no narrower "re-verify only, keep the same device" entry point, only the full
 * input-selection-then-verify step `Setup-Input.dc.html`/`Setup-Verify.dc.html` already is).
 * [onOpenLevelMeter] defaults to a no-op so every existing caller keeps compiling unchanged; the
 * host (`OrtNavHost`, WP3's row) is expected to wire it to the real `Level-Meter` destination the
 * same way it wires every other cross-package drill-in.
 */
@Composable
public fun SettingsCaptureScreen(
    state: SettingsCaptureViewState,
    onBack: () -> Unit,
    toggles: SettingsCaptureToggleActions,
    modifier: Modifier = Modifier,
    onOpenInputSetup: () -> Unit = {},
    onOpenLevelMeter: () -> Unit = {},
    onEditManualFrequency: ((String) -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DrillInHeader(parentLabel = "Settings", onBack = onBack)
        Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg)) {
            Text(text = "Input and level", style = OrtType.screenTitle, color = OrtColors.textHigh)
            Text(
                text = "Changing the input re-verifies the route before capture continues",
                style = OrtType.subtitle,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.sm),
            )

            SectionHeader(label = "Input", modifier = Modifier.padding(top = OrtSpacing.md))
            KeyValueRow(
                key = "Device",
                value = state.inputLabel,
                subLine = state.inputSubLine,
                trailingMarker = { TextAction(text = "Change", onClick = onOpenInputSetup) },
            )
            KeyValueRow(
                key = "Re-verify the route now",
                value = "",
                subLine = "30 s · capture pauses for it · a gap is recorded",
                trailingMarker = { TextAction(text = "Verify", onClick = onOpenInputSetup) },
            )

            SectionHeader(label = "Level", modifier = Modifier.padding(top = OrtSpacing.md))
            KeyValueRow(
                key = "Speech",
                value = state.levelLabel,
                subLine = state.levelSubLine,
                trailingMarker = { TextAction(text = "Meter", onClick = onOpenLevelMeter) },
            )
            ToggleRow(
                label = "Warn when out of band",
                checked = state.levelWarnEnabled,
                onCheckedChange = toggles.onToggleLevelWarn,
                subLine = "notification and status surface · below −30 or clipping",
            )

            SectionHeader(label = "Enhancement", modifier = Modifier.padding(top = OrtSpacing.md))
            ToggleRow(
                label = "Noise reduction before transcription",
                checked = state.noiseReductionEnabled,
                onCheckedChange = toggles.onToggleNoiseReduction,
                subLine = "on the copy the models hear · the retained audio is untouched",
            )
            ToggleRow(
                label = "Band-pass for FM voice",
                checked = state.bandPassEnabled,
                onCheckedChange = toggles.onToggleBandPass,
                subLine = "300–3000 Hz · off if you monitor anything but voice",
            )

            SectionHeader(
                label = "Frequency, when no radio is connected",
                modifier = Modifier.padding(top = OrtSpacing.md),
            )
            ManualFrequencyRow(manualFrequencyMhz = state.manualFrequencyMhz, onEdit = onEditManualFrequency)

            Text(
                text = "The level is set on the radio, not here. The app reads it and tells you when it " +
                    "drifts; it never adjusts gain on the way in, so what is retained is what the radio put out.",
                style = OrtType.cardBody,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.lg, bottom = OrtSpacing.lg),
            )
        }
    }
}

/** R-132 (register, round 4 System validator): `Edit` is real when a caller wires [onEdit] —
 * `SettingsStore.manualFrequencyMhz` is a real writable field (confirmed by reading
 * `SettingsStore.kt` before adding this), previously read-only in this screen. `null` (the
 * default `onEditManualFrequency` [SettingsCaptureScreen] passes through) keeps the row honest for
 * any caller that has not wired a save path yet: no `Edit` action that would silently do nothing.
 * Split out of [SettingsCaptureScreen] purely to keep that function under detekt's length limit. */
@Composable
private fun ManualFrequencyRow(
    manualFrequencyMhz: String?,
    onEdit: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember(manualFrequencyMhz) { mutableStateOf(manualFrequencyMhz.orEmpty()) }
    if (onEdit != null && editing) {
        TextField(
            value = draft,
            onValueChange = { draft = it },
            label = "Log overs against, MHz",
            modifier = modifier,
            trailingAction = {
                TextAction(text = "Save", onClick = {
                    onEdit(draft)
                    editing = false
                })
            },
        )
    } else {
        KeyValueRow(
            key = "Log overs against",
            value = manualFrequencyMhz ?: "not set",
            subLine = "used only while the rig is disconnected or absent",
            modifier = modifier,
            trailingMarker = onEdit?.let { { TextAction(text = "Edit", onClick = { editing = true }) } },
        )
    }
}
