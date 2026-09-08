package org.ort.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import org.ort.app.ui.components.CheckboxRow
import org.ort.app.ui.components.DrillInHeader
import org.ort.app.ui.components.FailedState
import org.ort.app.ui.components.FilterChip
import org.ort.app.ui.components.FilterChipRow
import org.ort.app.ui.components.RadioRow
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

private enum class ExportScope { TONIGHT, RANGE, EVERYTHING }
private enum class ExportFormat { ADIF, CSV, JSON, TEXT }

/**
 * `Settings-Export.dc.html` (FR-EXP-1..6). No ADIF/CSV/JSON/text exporter exists anywhere in
 * `:app` or `:pipeline` (grepped the whole tree before writing this) — the scope/include/format
 * controls are real and interactive (so the screen matches the board and a future exporter has
 * something to read), but `Save file` is built against no real producer, so it renders disabled
 * with an honest reason rather than a fake success (guide §6.8 / this package's brief).
 */
@Composable
public fun SettingsExportScreen(state: SettingsExportViewState, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var scope by remember { mutableStateOf(ExportScope.TONIGHT) }
    var format by remember { mutableStateOf(ExportFormat.ADIF) }
    var includeLog by remember { mutableStateOf(true) }
    var includeTranscripts by remember { mutableStateOf(true) }
    var includeDigest by remember { mutableStateOf(true) }
    var includeHistory by remember { mutableStateOf(false) }
    var includeAudio by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DrillInHeader(parentLabel = "Settings", onBack = onBack)
        Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg)) {
            Text(text = "Export", style = OrtType.screenTitle, color = OrtColors.textHigh)
            Text(
                text = "A file you save yourself. The app has nowhere to send it.",
                style = OrtType.subtitle,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.sm),
            )

            SectionHeader(label = "What", modifier = Modifier.padding(top = OrtSpacing.md))
            RadioRow(
                label = "Tonight",
                selected = scope == ExportScope.TONIGHT,
                onClick = { scope = ExportScope.TONIGHT },
                count = "${state.tonightOverCount}",
            )
            RadioRow(
                label = "A range of nights",
                selected = scope == ExportScope.RANGE,
                onClick = { scope = ExportScope.RANGE },
            )
            RadioRow(
                label = "Everything",
                selected = scope == ExportScope.EVERYTHING,
                onClick = { scope = ExportScope.EVERYTHING },
                count = "${state.allSessionCount} sessions",
            )

            SectionHeader(label = "Include", modifier = Modifier.padding(top = OrtSpacing.md))
            CheckboxRow(
                label = "Log with attributions and their state",
                checked = includeLog,
                onCheckedChange = { includeLog = it },
            )
            CheckboxRow(
                label = "Transcripts, current version",
                checked = includeTranscripts,
                onCheckedChange = { includeTranscripts = it },
            )
            CheckboxRow(label = "Digest", checked = includeDigest, onCheckedChange = { includeDigest = it })
            CheckboxRow(
                label = "Superseded transcripts and corrections history",
                checked = includeHistory,
                onCheckedChange = { includeHistory = it },
            )
            CheckboxRow(label = "Audio", checked = includeAudio, onCheckedChange = { includeAudio = it })

            SectionHeader(label = "Format", modifier = Modifier.padding(top = OrtSpacing.md))
            FilterChipRow(modifier = Modifier.fillMaxWidth().padding(top = OrtSpacing.sm)) {
                ExportFormat.entries.forEach { candidate ->
                    FilterChip(label = candidate.name, selected = format == candidate, onClick = { format = candidate })
                }
            }
            Text(
                text = "Attributions and their state travel with every callsign; an inferred or " +
                    "ambiguous over is never written as a plain confirmed record (FR-EXP-4).",
                style = OrtType.subLine,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs),
            )

            FailedState(
                title = "Export is not available in this build",
                body = "No ADIF, CSV, JSON or text exporter exists in :app or :pipeline yet. Your " +
                    "selections above are kept; nothing is silently sent or written until one does.",
                modifier = Modifier.padding(top = OrtSpacing.lg, bottom = OrtSpacing.lg),
            )
        }
    }
}
