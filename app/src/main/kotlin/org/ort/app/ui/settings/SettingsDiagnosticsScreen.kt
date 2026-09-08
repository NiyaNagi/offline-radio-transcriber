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
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.components.Tile
import org.ort.app.ui.improve.Plurals
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/**
 * `Settings-Diagnostics.dc.html` (FR-OBS-3/5): what a bundle would contain, shown before it
 * exists. No diagnostics-bundle producer exists in `:app` or `:pipeline` (grepped before writing
 * this) — `Preview`/`Save bundle` are honestly disabled; the file list below is what the bundle
 * *would* contain, described, never claimed to already be sitting on disk with a fabricated size.
 */
@Composable
public fun SettingsDiagnosticsScreen(
    state: SettingsDiagnosticsViewState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DrillInHeader(parentLabel = "Settings", onBack = onBack)
        Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg)) {
            Text(text = "Diagnostics", style = OrtType.screenTitle, color = OrtColors.textHigh)
            Text(
                text = "What a bundle contains, shown before it exists",
                style = OrtType.subtitle,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.sm),
            )

            // R-137 (round 4, System validator): each `Tile` asked for `fillMaxWidth()` instead of
            // `weight(1f)` — inside a `Row`, that makes every tile claim the *whole* row's width
            // (not a fair share of it), so the second and third tiles were laid out entirely off
            // the visible screen to the right rather than side by side. Only the first ("capture
            // state") ever showed.
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = OrtSpacing.sm),
                horizontalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
            ) {
                Tile(figure = state.aliveLabel, caption = "capture state", modifier = Modifier.weight(1f))
                Tile(figure = state.realTimeFactorLabel, caption = "RTF, small model", modifier = Modifier.weight(1f))
                Tile(
                    figure = state.failedPassCount?.toString() ?: "not tracked",
                    caption = "failed passes",
                    modifier = Modifier.weight(1f),
                )
            }

            // R-137: "In the bundle · N files · X.X MB" is the board's header shape — the byte
            // total is not repeated here (no producer writes these files, so no real size exists
            // to report; `Settings-Diagnostics.dc.html`'s "2.1 MB" is an illustrative example, not
            // a fact this build could compute — constitution I).
            SectionHeader(
                label = "In the bundle · ${Plurals.count(state.files.size, "file")}",
                modifier = Modifier.padding(top = OrtSpacing.lg),
            )
            state.files.forEach { file ->
                Column(modifier = Modifier.padding(vertical = OrtSpacing.xs)) {
                    Text(text = file.name, style = OrtType.callsignRow, color = OrtColors.textHigh)
                    Text(text = file.description, style = OrtType.subLine, color = OrtColors.textDim)
                }
            }

            SectionHeader(label = "Never included", modifier = Modifier.padding(top = OrtSpacing.lg))
            Text(
                text = "Audio. Transcripts. Callsigns. Voiceprints. Names. Location. Every log line above " +
                    "would be scrubbed of callsigns before it is written.",
                style = OrtType.cardBody,
                color = OrtColors.textBody,
                modifier = Modifier.padding(top = OrtSpacing.xs),
            )

            FailedState(
                title = "Preview and Save bundle are not available in this build",
                body = "No diagnostics-bundle producer exists in :app or :pipeline yet — this screen states " +
                    "what a bundle would contain, not what one currently does.",
                modifier = Modifier.padding(top = OrtSpacing.lg, bottom = OrtSpacing.lg),
            )
        }
    }
}
