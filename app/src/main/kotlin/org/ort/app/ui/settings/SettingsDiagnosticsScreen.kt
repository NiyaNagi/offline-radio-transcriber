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
import org.ort.app.ui.components.PrimaryButton
import org.ort.app.ui.components.SecondaryButton
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.components.Tile
import org.ort.app.ui.improve.Plurals
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/**
 * `Settings-Diagnostics.dc.html` (FR-OBS-1/3/5): what a bundle contains, real and computed before
 * it exists on disk — WP11e's `DiagnosticsBundleBuilder` (round 9, register R-137) is the real
 * producer behind every file's size and the header total; `Preview`/`Save bundle` are both real
 * actions now, wired by `SettingsContent` (this screen itself takes no `Context`, per this
 * package's own polling-stays-out-of-screens rule).
 *
 * [onPreview] opens [previewOpen] — an in-app, full-screen listing of the exact same real entries
 * (name, clause, size) and total `Save bundle` would write, before committing to it. The board's
 * own prose ("Preview opens every file in a reader before you save") reads as launching a
 * per-file *external* viewer — genuinely a different, larger feature (a `FileProvider`, a manifest
 * change, one `ACTION_VIEW` intent per file) than a single Compose screen can add on its own; this
 * is the honest, real, in-scope substitute reported to the coordinator for a decision on whether
 * the external-reader shape is still wanted as a follow-up.
 */
@Composable
public fun SettingsDiagnosticsScreen(
    state: SettingsDiagnosticsViewState,
    onBack: () -> Unit,
    onPreview: () -> Unit,
    onSaveBundle: () -> Unit,
    modifier: Modifier = Modifier,
    previewOpen: Boolean = false,
    onDismissPreview: () -> Unit = {},
    saveConfirmationLabel: String? = null,
) {
    if (previewOpen) {
        DiagnosticsPreviewScreen(state = state, onDone = onDismissPreview, modifier = modifier)
        return
    }
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

            // R-137 (round 9): "In the bundle · N files · X.X MB" is now the board's own shape,
            // header total included — real, WP11e's `DiagnosticsBundleBuilder.preview`.
            SectionHeader(
                label = "In the bundle · ${Plurals.count(state.files.size, "file")} · ${state.totalSizeLabel}",
                modifier = Modifier.padding(top = OrtSpacing.lg),
            )
            state.files.forEach { file -> DiagnosticsFileRow(file = file) }

            SectionHeader(label = "Never included", modifier = Modifier.padding(top = OrtSpacing.lg))
            // R-137 (round 7, then round 9): `Settings-Diagnostics.dc.html`'s own scrubbing example
            // — "resolved [callsign] at 0.94" — is real now, not hypothetical: WP11e's
            // `CallsignScrubber` actually runs on every log entry `DiagnosticsBundleBuilder`
            // renders, so this reads "are scrubbed", not "would be".
            Text(
                text = "Audio. Transcripts. Callsigns. Voiceprints. Names. Location. The logs above are " +
                    "scrubbed of callsigns before they are written — a line reads " +
                    "\"resolved [callsign] at 0.94\", never the callsign itself.",
                style = OrtType.cardBody,
                color = OrtColors.textBody,
                modifier = Modifier.padding(top = OrtSpacing.xs),
            )

            saveConfirmationLabel?.let { label ->
                Text(
                    text = label,
                    style = OrtType.cardBody,
                    color = OrtColors.accentGreen,
                    modifier = Modifier.padding(top = OrtSpacing.lg),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = OrtSpacing.lg, bottom = OrtSpacing.lg),
                horizontalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
            ) {
                SecondaryButton(text = "Preview", onClick = onPreview, modifier = Modifier.weight(1f))
                PrimaryButton(text = "Save bundle", onClick = onSaveBundle, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** One `IN THE BUNDLE` row — name, real clause, real trailing size (the board's own `.f`/`.sz`
 * shape). Split out of [SettingsDiagnosticsScreen] purely to keep that function under detekt's
 * length limit. */
@Composable
private fun DiagnosticsFileRow(file: SettingsDiagnosticsFileViewState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = OrtSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = file.name, style = OrtType.callsignRow, color = OrtColors.textHigh)
            Text(text = file.description, style = OrtType.subLine, color = OrtColors.textDim)
        }
        Text(text = file.sizeLabel, style = OrtType.subLine, color = OrtColors.textFaint)
    }
}

/** `Preview` (R-137, round 9): the exact real entries/total `Save bundle` would write, shown
 * before committing to it — see [SettingsDiagnosticsScreen]'s own doc comment for why this is an
 * in-app listing rather than the board's own per-file external-reader wording. */
@Composable
private fun DiagnosticsPreviewScreen(
    state: SettingsDiagnosticsViewState,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DrillInHeader(parentLabel = "Diagnostics", onBack = onDone)
        Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg)) {
            Text(text = "Preview", style = OrtType.screenTitle, color = OrtColors.textHigh)
            Text(
                text = "Exactly what Save bundle will write · ${Plurals.count(state.files.size, "file")} · " +
                    state.totalSizeLabel,
                style = OrtType.subtitle,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.sm),
            )
            state.files.forEach { file -> DiagnosticsFileRow(file = file) }
            PrimaryButton(
                text = "Done",
                onClick = onDone,
                modifier = Modifier.fillMaxWidth().padding(top = OrtSpacing.lg, bottom = OrtSpacing.lg),
            )
        }
    }
}
