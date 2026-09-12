package org.ort.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.ort.app.export.ExportFileFormat
import org.ort.app.export.ExportRequest
import org.ort.app.export.ExportRequestScope
import org.ort.app.ui.components.CheckboxRow
import org.ort.app.ui.components.DrillInHeader
import org.ort.app.ui.components.FailedState
import org.ort.app.ui.components.FilterChip
import org.ort.app.ui.components.FilterChipRow
import org.ort.app.ui.components.OrtIcons
import org.ort.app.ui.components.PrimaryButton
import org.ort.app.ui.components.RadioRow
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.improve.Plurals
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/** Constitution III's never-included list, `Settings-Export.dc.html` verbatim (R-135, round 4
 * System validator: this must show even while export is unbuilt, not only once a real exporter
 * exists — the promise is about what would leave the device, not about whether that path works). */
private const val EXPORT_NEVER_INCLUDED_PROMISE =
    "Never exported, by any option: voiceprints, names you gave stations, notes, your location, " +
        "the level of any signal that would locate you."

/** `RANGE` ("a range of nights") has no [ExportRequestScope] counterpart — see
 * [org.ort.app.export.ExportRequestScope]'s own kdoc for why: no date-range picker exists in this
 * build to supply the two dates a real query would need. [toRequestScope] is only ever called
 * once `Save file` is known to be enabled (never for `RANGE`, which disables it), so its `error()`
 * for that branch is an invariant check, not a reachable path. */
private enum class ExportScope { TONIGHT, RANGE, EVERYTHING }

private fun ExportScope.toRequestScope(): ExportRequestScope = when (this) {
    ExportScope.TONIGHT -> ExportRequestScope.TONIGHT
    ExportScope.EVERYTHING -> ExportRequestScope.EVERYTHING
    ExportScope.RANGE -> error("Save file must be disabled while RANGE is selected — see toRequestScope's own kdoc")
}

/**
 * `Settings-Export.dc.html` (FR-EXP-1..6, register R-1009/WPX). ADIF/CSV/JSON/text exporters now
 * exist in `:pipeline`'s `org.ort.pipeline.export` package and are wired here through
 * [org.ort.app.export.ExportCoordinator] via [onSaveFile] — `Save file` produces a real file for
 * the `Tonight`/`Everything` scopes (FR-EXP-1/2). `A range of nights` has no date-range picker
 * anywhere in this build yet (see [ExportScope]'s own kdoc), so `Save file` disables itself
 * honestly while it is selected rather than silently mapping it onto a scope the operator did not
 * choose (constitution I).
 *
 * `Digest`, `Superseded transcripts and corrections history` and `Audio` stay visible (matching
 * the artboard) but are deliberately non-interactive this round — no writer in `:pipeline` folds
 * digest text, transcript history or audio into any of the four flat export formats yet. Their own
 * sub-lines say so, honestly, rather than accepting a tap that changes nothing real. `Log with
 * attributions and their state` is the one thing every format actually writes, so it is pinned on
 * rather than offered as a togglable no-op. `Transcripts, current version` is the one real,
 * interactive include beyond scope and format — [org.ort.app.export.ExportRequest
 * .includeTranscripts] genuinely strips transcript text (and its model provenance alongside it)
 * when unchecked.
 */
@Composable
public fun SettingsExportScreen(
    state: SettingsExportViewState,
    onBack: () -> Unit,
    onSaveFile: (ExportRequest) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var scope by remember { mutableStateOf(ExportScope.TONIGHT) }
    var format by remember { mutableStateOf(ExportFileFormat.ADIF) }
    var includeTranscripts by remember { mutableStateOf(true) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // A stable handle onto this screen's own (outer, vertical) scroll container — the
            // `Format` row nests a second, horizontal one (`FilterChipRow`), so a bare
            // `performScrollTo()`/`hasScrollAction()` in a test can find either; this tag lets a
            // test target the outer one unambiguously (`SettingsExportScreenTest`'s own doc
            // comment has the finding this fixes).
            .testTag("export-screen-scroll"),
    ) {
        DrillInHeader(parentLabel = "Settings", onBack = onBack)
        Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg)) {
            Text(text = "Export", style = OrtType.screenTitle, color = OrtColors.textHigh)
            Text(
                text = "A file you save yourself. The app has nowhere to send it.",
                style = OrtType.subtitle,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.sm),
            )

            ExportScopeSection(state = state, scope = scope, onScopeChange = { scope = it })
            ExportIncludeSection(
                includeTranscripts = includeTranscripts,
                onIncludeTranscriptsChange = { includeTranscripts = it },
            )
            ExportFormatSection(format = format, onFormatChange = { format = it })

            ExportFooter(
                scope = scope,
                onSaveFile = {
                    onSaveFile(
                        ExportRequest(
                            scope = scope.toRequestScope(),
                            format = format,
                            includeTranscripts = includeTranscripts,
                        ),
                    )
                },
            )
        }
    }
}

/** `Settings-Export.dc.html`'s "What" section — split out of [SettingsExportScreen] purely to
 * keep that function under detekt's `LongMethod` threshold; every row, label and test tag moved
 * verbatim. */
@Composable
private fun ExportScopeSection(
    state: SettingsExportViewState,
    scope: ExportScope,
    onScopeChange: (ExportScope) -> Unit,
) {
    SectionHeader(label = "What", modifier = Modifier.padding(top = OrtSpacing.md))
    RadioRow(
        label = "Tonight",
        selected = scope == ExportScope.TONIGHT,
        onClick = { onScopeChange(ExportScope.TONIGHT) },
        count = "${state.tonightOverCount}",
        modifier = Modifier.testTag("export-scope-tonight"),
    )
    RadioRow(
        label = "A range of nights",
        selected = scope == ExportScope.RANGE,
        onClick = { onScopeChange(ExportScope.RANGE) },
        modifier = Modifier.testTag("export-scope-range"),
    )
    RadioRow(
        label = "Everything",
        selected = scope == ExportScope.EVERYTHING,
        onClick = { onScopeChange(ExportScope.EVERYTHING) },
        count = Plurals.count(state.allSessionCount, "session"),
        modifier = Modifier.testTag("export-scope-everything"),
    )
}

/** `Settings-Export.dc.html`'s "Include" section — split out of [SettingsExportScreen] for the
 * same `LongMethod` reason as [ExportScopeSection]. See [SettingsExportScreen]'s own kdoc for why
 * `Log with attributions`/`Digest`/`Superseded transcripts...`/`Audio` are pinned or disabled
 * rather than interactive — only `Transcripts, current version` genuinely toggles behaviour. */
@Composable
private fun ExportIncludeSection(includeTranscripts: Boolean, onIncludeTranscriptsChange: (Boolean) -> Unit) {
    SectionHeader(label = "Include", modifier = Modifier.padding(top = OrtSpacing.md))
    CheckboxRow(
        label = "Log with attributions and their state",
        checked = true,
        onCheckedChange = {},
        subLine = "the state travels with every callsign — an export without it would be a lie; " +
            "always included, the one thing every format actually writes",
    )
    CheckboxRow(
        label = "Transcripts, current version",
        checked = includeTranscripts,
        onCheckedChange = onIncludeTranscriptsChange,
        subLine = "superseded versions optional below",
        modifier = Modifier.testTag("export-checkbox-transcripts"),
    )
    CheckboxRow(
        label = "Digest",
        checked = false,
        onCheckedChange = {},
        subLine = "not yet written by this exporter",
        modifier = Modifier.testTag("export-checkbox-digest"),
    )
    CheckboxRow(
        label = "Superseded transcripts and corrections history",
        checked = false,
        onCheckedChange = {},
        subLine = "not yet written by this exporter",
        modifier = Modifier.testTag("export-checkbox-history"),
    )
    CheckboxRow(
        label = "Audio",
        checked = false,
        onCheckedChange = {},
        subLine = "not yet written by this exporter",
        modifier = Modifier.testTag("export-checkbox-audio"),
    )
}

/** `Settings-Export.dc.html`'s "Format" section — split out of [SettingsExportScreen] for the same
 * `LongMethod` reason as [ExportScopeSection]. */
@Composable
private fun ExportFormatSection(format: ExportFileFormat, onFormatChange: (ExportFileFormat) -> Unit) {
    SectionHeader(label = "Format", modifier = Modifier.padding(top = OrtSpacing.md))
    FilterChipRow(modifier = Modifier.fillMaxWidth().padding(top = OrtSpacing.sm)) {
        ExportFileFormat.entries.forEach { candidate ->
            FilterChip(
                label = candidate.name,
                selected = format == candidate,
                onClick = { onFormatChange(candidate) },
                modifier = Modifier.testTag("export-format-${candidate.name}"),
            )
        }
    }
    Text(
        text = "Attributions and their state travel with every callsign; an inferred or " +
            "ambiguous over is never written as a plain confirmed record (FR-EXP-4).",
        style = OrtType.subLine,
        color = OrtColors.textDim,
        modifier = Modifier.padding(top = OrtSpacing.xs),
    )
}

/** The promise banner, the `RANGE`-only honest `FailedState`, and the real `Save file` button —
 * split out of [SettingsExportScreen] purely to keep that function under detekt's length limit.
 *
 * R-135 (round 4, System validator): the promise banner names what would leave the device through
 * export — a fact true regardless of which scope is selected — so it shows unconditionally, not
 * only once a scope is exportable. Verbatim from `Settings-Export.dc.html`.
 *
 * Register R-1009 (WPX): the "Export is not available in this build" `FailedState` used to show
 * unconditionally, because no exporter existed at all. One now does — the `FailedState` shows only
 * while [scope] is `ExportScope.RANGE` (the one scope with no real query behind it, [ExportScope]'s
 * own kdoc), and `Save file` calls [onSaveFile] and is enabled for the other two. */
@Composable
private fun ExportFooter(scope: ExportScope, onSaveFile: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = OrtSpacing.lg)
                .background(OrtColors.bgCard, RoundedCornerShape(10.dp))
                .padding(OrtSpacing.md),
        ) {
            Icon(
                imageVector = OrtIcons.lock,
                contentDescription = null,
                tint = OrtColors.accentGreen,
                modifier = Modifier.padding(top = 1.dp).size(16.dp),
            )
            Text(
                text = EXPORT_NEVER_INCLUDED_PROMISE,
                style = OrtType.cardBody,
                color = OrtColors.textBody,
                modifier = Modifier.padding(start = OrtSpacing.sm),
            )
        }

        if (scope == ExportScope.RANGE) {
            FailedState(
                title = "A range of nights is not available in this build",
                body = "No date-range picker exists yet to choose the two dates this scope would " +
                    "need. Choose Tonight or Everything, or use the debug dump for a full " +
                    "session-by-session record.",
                modifier = Modifier.padding(top = OrtSpacing.md),
            )
        }

        PrimaryButton(
            text = "Save file",
            onClick = onSaveFile,
            enabled = scope != ExportScope.RANGE,
            modifier = Modifier.fillMaxWidth().padding(top = OrtSpacing.md, bottom = OrtSpacing.lg),
        )
    }
}
