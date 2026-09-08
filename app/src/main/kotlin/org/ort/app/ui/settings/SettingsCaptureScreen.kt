package org.ort.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.ort.app.ui.components.DrillInHeader
import org.ort.app.ui.components.KeyValueRow
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.components.ToggleRow
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/** `Settings-Capture.dc.html`: input/level facts from `InputStatus`/`LevelStatus` (WP11c). */
@Composable
public fun SettingsCaptureScreen(
    state: SettingsCaptureViewState,
    onBack: () -> Unit,
    onToggleLevelWarn: (Boolean) -> Unit,
    onToggleNoiseReduction: (Boolean) -> Unit,
    onToggleBandPass: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
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
            KeyValueRow(key = "Device", value = state.inputLabel, subLine = state.inputSubLine)

            SectionHeader(label = "Level", modifier = Modifier.padding(top = OrtSpacing.md))
            KeyValueRow(key = "Speech", value = state.levelLabel, subLine = state.levelSubLine)
            ToggleRow(
                label = "Warn when out of band",
                checked = state.levelWarnEnabled,
                onCheckedChange = onToggleLevelWarn,
                subLine = "notification and status surface · below −30 or clipping",
            )

            SectionHeader(label = "Enhancement", modifier = Modifier.padding(top = OrtSpacing.md))
            ToggleRow(
                label = "Noise reduction before transcription",
                checked = state.noiseReductionEnabled,
                onCheckedChange = onToggleNoiseReduction,
                subLine = "on the copy the models hear · the retained audio is untouched",
            )
            ToggleRow(
                label = "Band-pass for FM voice",
                checked = state.bandPassEnabled,
                onCheckedChange = onToggleBandPass,
                subLine = "300–3000 Hz · off if you monitor anything but voice",
            )

            SectionHeader(
                label = "Frequency, when no radio is connected",
                modifier = Modifier.padding(top = OrtSpacing.md),
            )
            KeyValueRow(
                key = "Log overs against",
                value = state.manualFrequencyMhz ?: "not set",
                subLine = "used only while the rig is disconnected or absent",
            )

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
