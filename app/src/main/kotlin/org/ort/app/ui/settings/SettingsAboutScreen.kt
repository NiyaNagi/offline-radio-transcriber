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
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/** `Settings-About.dc.html`: the offline promise, stated plainly, plus the real app/Android
 * version read from the package manager (never a literal copied from the artboard). */
@Composable
public fun SettingsAboutScreen(state: SettingsAboutViewState, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DrillInHeader(parentLabel = "Settings", onBack = onBack)
        Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg)) {
            Text(text = "Offline radio transcriber", style = OrtType.screenTitle, color = OrtColors.textHigh)
            Text(
                text = state.appVersionLabel,
                style = OrtType.subtitle,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.sm),
            )

            SectionHeader(label = "The offline promise", modifier = Modifier.padding(top = OrtSpacing.md))
            Text(
                text = "No part of capture, transcription, resolution or the reader can reach the " +
                    "network. The build enforces it: only :net may link an HTTP client, and none of " +
                    "these is that module.",
                style = OrtType.bodyProse,
                color = OrtColors.textBody,
                modifier = Modifier.padding(top = OrtSpacing.sm),
            )
            Text(
                text = "Voiceprints, the names you give stations, what this phone has learned about who " +
                    "is around when, and your location never leave it — not in an export, a " +
                    "contribution, a diagnostic bundle or a backup.",
                style = OrtType.bodyProse,
                color = OrtColors.textBody,
                modifier = Modifier.padding(top = OrtSpacing.sm),
            )
            Text(
                text = "Nothing is deleted quietly. Every attribution carries its confidence. A weaker " +
                    "phone knows less; it is never more wrong.",
                style = OrtType.bodyProse,
                color = OrtColors.textBody,
                modifier = Modifier.padding(top = OrtSpacing.sm),
            )

            SectionHeader(label = "Build", modifier = Modifier.padding(top = OrtSpacing.lg))
            KeyValueRow(key = "Android", value = state.androidVersionLabel, subLine = "min ${state.minSdkLabel}")
            KeyValueRow(key = "Models", value = "sherpa-onnx · whisper · Silero")
            KeyValueRow(key = "Radio", value = "usb-serial-for-android")
            KeyValueRow(key = "Licences", value = "Apache 2.0 · third-party notices")

            SectionHeader(label = "Source and spec", modifier = Modifier.padding(top = OrtSpacing.lg))
            Text(
                text = "Every screen in this app is specified by a requirement id and drawn as an " +
                    "artboard before it is built. The functional spec, the design canvas and the audit " +
                    "register ship with the source.",
                style = OrtType.cardBody,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.lg),
            )
        }
    }
}
