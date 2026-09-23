package org.ort.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.ort.app.ui.OfflinePromiseCopy
import org.ort.app.ui.components.DrillInHeader
import org.ort.app.ui.components.KeyValueRow
import org.ort.app.ui.components.OrtIcons
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/** `Settings-About.dc.html`: the offline promise, stated plainly, plus the real app/Android
 * version read from the package manager (never a literal copied from the artboard).
 *
 * R-138 (round 4, System validator): the title row gains a leading icon — the guide's stroke set
 * (`ui/components/OrtIcons.kt`, outside this round's file ownership) has no bespoke "about" glyph,
 * so this reuses [OrtIcons.settings] rather than adding one to a package this round may not edit;
 * a closer match is WP2's to add.
 */
@Composable
public fun SettingsAboutScreen(state: SettingsAboutViewState, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DrillInHeader(parentLabel = "Settings", onBack = onBack)
        Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = OrtIcons.settings,
                    contentDescription = null,
                    tint = OrtColors.accentGreen,
                    modifier = Modifier.padding(end = OrtSpacing.sm).size(20.dp),
                )
                Text(text = "Offline radio transcriber", style = OrtType.screenTitle, color = OrtColors.textHigh)
            }
            Text(
                text = state.appVersionLabel,
                style = OrtType.subtitle,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.sm),
            )

            SectionHeader(label = "The offline promise", modifier = Modifier.padding(top = OrtSpacing.md))
            // R-1165: these paragraphs used to be re-typed literals that had already drifted from
            // the Welcome sheet's supposedly verbatim copy (`:net` against `only one module`), and
            // both went on stating a voiceprint guarantee D38 had made false. One list now feeds
            // both surfaces; see `OfflinePromiseCopy` for the wording's spec obligations.
            OfflinePromiseCopy.POINTS.forEachIndexed { index, point ->
                Text(
                    text = point,
                    style = OrtType.bodyProse,
                    color = OrtColors.textBody,
                    modifier = Modifier.padding(top = OrtSpacing.sm).testTag("settings-about-promise-point-$index"),
                )
            }

            SectionHeader(label = "Build", modifier = Modifier.padding(top = OrtSpacing.lg))
            // R-138 (round 7, register): board row order is Models, Runtime, Radio, Android,
            // Licences — `Android` used to lead. `Models` now carries the real sherpa-onnx version
            // (`gradle/libs.versions.toml`'s `sherpaOnnx` entry, via `BuildConfig` — see
            // `SettingsPolling.about`'s own comment); `Runtime`/`Radio` stay the honest, version-free
            // statements they already were — neither ONNX Runtime nor usb-serial-for-android is an
            // actual Gradle dependency of this build yet (checked `gradle/libs.versions.toml` again
            // before writing this), so there is no real version to read for either without
            // fabricating one.
            KeyValueRow(key = "Models", value = "sherpa-onnx ${state.sherpaOnnxVersionLabel} · whisper · Silero")
            KeyValueRow(key = "Runtime", value = "ONNX Runtime · NNAPI where this device offers it")
            KeyValueRow(key = "Radio", value = "usb-serial-for-android")
            KeyValueRow(key = "Android", value = state.androidVersionLabel, subLine = "min ${state.minSdkLabel}")
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
