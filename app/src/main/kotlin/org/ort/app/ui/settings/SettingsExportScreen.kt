package org.ort.app.ui.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.ort.app.export.ExportCoordinator
import org.ort.app.export.ExportCountPreview
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
import java.util.Locale

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
 *
 * [previewCount] (R-1035, register — the operator's own device: an ADIF export "wrote a header
 * and zero QSO records," correct per FR-EXP-4 and honest about why, but discovered only *after*
 * the write): recomputed, reactively, for the exact [ExportRequest] `Save file` would use, every
 * time [scope]/[format]/`includeTranscripts` changes — [ExportFooter] renders its
 * [ExportCountPreview] *before* the write, never a separate estimate (this parameter is the same
 * function [onSaveFile]'s own real caller eventually writes with — [ExportCoordinator.previewCount]
 * itself states why the same producer must answer both). Defaults to the real
 * [ExportCoordinator.previewCount] so every existing caller keeps compiling and now genuinely
 * shows the real count with no wiring change of its own; a test injects a behavioural fake instead
 * of a real `Context`-backed Room read (this module's own `SettingsExportScreenTest`).
 *
 * [previewSizeBytes]/[suggestedFileName] (R-1045(b), register, design: `Settings-Export.dc.html`'s
 * button reads `Save file · <filename> · <size>`, the build showed only `Save file`).
 * [suggestedFileName] is never a second, independently-invented naming rule — it *is*
 * [ExportCoordinator.suggestedFileName], the exact function [onSaveFile]'s own real caller
 * ([org.ort.app.ui.settings.SettingsExportSubScreen]) already hands the Storage Access Framework
 * picker, only bound here to a fixed `Instant` per [scope]/[format] combination (via `remember`) so
 * the displayed name does not visibly tick over between recompositions while the operator is still
 * looking at it. [previewSizeBytes] defaults to [ExportCoordinator.previewSizeBytes] — the real
 * byte count [ExportCoordinator.build] would produce, resolved the same "run the real producer
 * before the write" way [org.ort.app.fieldreport.bundle.FieldReportBundleBuilder.preview] already
 * does for its own bundle (that function's own doc comment has the full reasoning) — never an
 * estimate presented as a size (constitution I/VI). Both follow [previewCount]'s own injection
 * idiom exactly: a test supplies a behavioural fake for each (this module's own
 * `SettingsExportScreenTest`).
 */
@Composable
public fun SettingsExportScreen(
    state: SettingsExportViewState,
    onBack: () -> Unit,
    onSaveFile: (ExportRequest) -> Unit = {},
    modifier: Modifier = Modifier,
    previewCount: suspend (Context, ExportRequest) -> ExportCountPreview = ExportCoordinator::previewCount,
    previewSizeBytes: suspend (Context, ExportRequest) -> Long = ExportCoordinator::previewSizeBytes,
    suggestedFileName: (ExportRequest) -> String = { ExportCoordinator.suggestedFileName(it) },
) {
    var scope by remember { mutableStateOf(ExportScope.TONIGHT) }
    var format by remember { mutableStateOf(ExportFileFormat.ADIF) }
    var includeTranscripts by remember { mutableStateOf(true) }
    // R-1035: `null` exactly while a freshly-selected combination's own preview has not resolved
    // yet, while `RANGE` is selected (no [ExportRequestScope] exists for it —
    // [ExportScope.toRequestScope]'s own kdoc — so no request, and no preview, is ever built for
    // it), or while [previewCount] itself failed. Register R-1039 (halt): found running the real
    // tour against the real `overnight` scenario while verifying this fix,
    // [ExportCoordinator.previewCount]'s own real read *used to* throw on data this build can
    // produce (`IllegalArgumentException: CONFIRMED transmission ... has no resolvable callsign`,
    // `ExportCoordinator.toExportAttribution`) — that specific crash is now fixed at the
    // coordinator layer itself (a missing catalog callsign falls back to the transmission's own
    // real `stationId`; a genuine data inconsistency is represented honestly via
    // `org.ort.pipeline.export.ExportAttribution.UnresolvedCallsign` rather than thrown). This
    // `runCatching` stays regardless, as defence-in-depth for a real Room read this screen cannot
    // fully control the failure modes of (I/O, cancellation, a future regression) — a diagnostic
    // preview must not turn a read-only screen into a new crash surface, and this already logs
    // rather than hiding, so it costs nothing to keep: `runCatching` treats a failure the same
    // honest way a `notMeasuredReason` elsewhere in this codebase treats an absent signal: nothing
    // shown, never a crash and never a fabricated count (constitution I).
    var preview by remember { mutableStateOf<ExportCountPreview?>(null) }
    // R-1045(b): the same absent-signal shape as [preview] above — `null` while `RANGE` is
    // selected, still loading, or [previewSizeBytes] itself failed; never a fabricated size.
    var sizeBytes by remember { mutableStateOf<Long?>(null) }
    val context = LocalContext.current

    // R-1045(b): the artboard's filename never depends on `includeTranscripts` (only [scope]/
    // [format] feed [ExportCoordinator.suggestedFileName]), so it is `remember`-ed off those two
    // alone — otherwise every `includeTranscripts` toggle would also re-mint a fresh timestamp for
    // a name the operator has not actually changed the scope/format of.
    val displayFileName = if (scope == ExportScope.RANGE) {
        null
    } else {
        remember(scope, format) {
            suggestedFileName(ExportRequest(scope = scope.toRequestScope(), format = format))
        }
    }

    LaunchedEffect(scope, format, includeTranscripts) {
        if (scope == ExportScope.RANGE) {
            preview = null
            sizeBytes = null
        } else {
            val request = ExportRequest(
                scope = scope.toRequestScope(),
                format = format,
                includeTranscripts = includeTranscripts,
            )
            preview = runCatching { previewCount(context, request) }.onFailure {
                // Logged, never silent (constitution: never delete/drop a real failure quietly) —
                // this is exactly the on-device failure this file's own doc comment names.
                android.util.Log.w("SettingsExportScreen", "previewCount failed; showing no preview", it)
            }.getOrNull()
            sizeBytes = runCatching { previewSizeBytes(context, request) }.onFailure {
                android.util.Log.w("SettingsExportScreen", "previewSizeBytes failed; showing filename only", it)
            }.getOrNull()
        }
    }

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
                preview = preview,
                format = format,
                fileName = displayFileName,
                sizeBytes = sizeBytes,
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

/**
 * R-1044 (register, design): `Settings-Export.dc.html`'s `.opt` rows carry a 1px divider above
 * every row (and one more below the last row of each group) plus 4px of vertical padding around
 * the row's own content — the build drew neither, so at font scale 2.0 a wrapped sub-line's own
 * last line ran straight into the next row's title with no seam between them. [OrtColors.lineFaint]
 * is the same hairline token the artboard's own `oklch(0.21 0.010 250)` names (that colour's own
 * doc comment in `OrtColors.kt` — "between station rows" — states the exact value), never a new one
 * invented for this row. The padding is added via each row's own `modifier` (so it composes with
 * [RadioRow]/[CheckboxRow]'s own `heightIn(min = 44.dp)` rather than replacing it — the visible row
 * only grows taller, the 44dp floor and the vertically-centred marker are unaffected).
 */
@Composable
private fun OptionDivider(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().height(1.dp).background(OrtColors.lineFaint))
}

/** `Settings-Export.dc.html`'s "What" section — split out of [SettingsExportScreen] purely to
 * keep that function under detekt's `LongMethod` threshold; every row, label and test tag moved
 * verbatim. R-1044: a divider above every row and one more after the last (see [OptionDivider]'s
 * own kdoc). */
@Composable
private fun ExportScopeSection(
    state: SettingsExportViewState,
    scope: ExportScope,
    onScopeChange: (ExportScope) -> Unit,
) {
    SectionHeader(label = "What", modifier = Modifier.padding(top = OrtSpacing.md))
    OptionDivider()
    RadioRow(
        label = "Tonight",
        selected = scope == ExportScope.TONIGHT,
        onClick = { onScopeChange(ExportScope.TONIGHT) },
        count = "${state.tonightOverCount}",
        modifier = Modifier.padding(vertical = OrtSpacing.xs).testTag("export-scope-tonight"),
    )
    OptionDivider()
    RadioRow(
        label = "A range of nights",
        selected = scope == ExportScope.RANGE,
        onClick = { onScopeChange(ExportScope.RANGE) },
        modifier = Modifier.padding(vertical = OrtSpacing.xs).testTag("export-scope-range"),
    )
    OptionDivider()
    RadioRow(
        label = "Everything",
        selected = scope == ExportScope.EVERYTHING,
        onClick = { onScopeChange(ExportScope.EVERYTHING) },
        count = Plurals.count(state.allSessionCount, "session"),
        modifier = Modifier.padding(vertical = OrtSpacing.xs).testTag("export-scope-everything"),
    )
    OptionDivider()
}

/** `Settings-Export.dc.html`'s "Include" section — split out of [SettingsExportScreen] for the
 * same `LongMethod` reason as [ExportScopeSection]. See [SettingsExportScreen]'s own kdoc for why
 * `Log with attributions`/`Digest`/`Superseded transcripts...`/`Audio` are pinned or disabled
 * rather than interactive — only `Transcripts, current version` genuinely toggles behaviour. R-1044:
 * a divider above every row and one more after the last (see [OptionDivider]'s own kdoc). */
@Composable
private fun ExportIncludeSection(includeTranscripts: Boolean, onIncludeTranscriptsChange: (Boolean) -> Unit) {
    SectionHeader(label = "Include", modifier = Modifier.padding(top = OrtSpacing.md))
    OptionDivider()
    CheckboxRow(
        label = "Log with attributions and their state",
        checked = true,
        onCheckedChange = {},
        subLine = "the state travels with every callsign — an export without it would be a lie; " +
            "always included, the one thing every format actually writes",
        modifier = Modifier.padding(vertical = OrtSpacing.xs),
    )
    OptionDivider()
    CheckboxRow(
        label = "Transcripts, current version",
        checked = includeTranscripts,
        onCheckedChange = onIncludeTranscriptsChange,
        subLine = "superseded versions optional below",
        modifier = Modifier.padding(vertical = OrtSpacing.xs).testTag("export-checkbox-transcripts"),
    )
    OptionDivider()
    CheckboxRow(
        label = "Digest",
        checked = false,
        onCheckedChange = {},
        subLine = "not yet written by this exporter",
        modifier = Modifier.padding(vertical = OrtSpacing.xs).testTag("export-checkbox-digest"),
    )
    OptionDivider()
    CheckboxRow(
        label = "Superseded transcripts and corrections history",
        checked = false,
        onCheckedChange = {},
        subLine = "not yet written by this exporter",
        modifier = Modifier.padding(vertical = OrtSpacing.xs).testTag("export-checkbox-history"),
    )
    OptionDivider()
    CheckboxRow(
        label = "Audio",
        checked = false,
        onCheckedChange = {},
        subLine = "not yet written by this exporter",
        modifier = Modifier.padding(vertical = OrtSpacing.xs).testTag("export-checkbox-audio"),
    )
    OptionDivider()
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

/** The promise banner, the `RANGE`-only honest `FailedState`, the R-1035 count preview, and the
 * real `Save file` button — split out of [SettingsExportScreen] purely to keep that function under
 * detekt's length limit.
 *
 * R-135 (round 4, System validator): the promise banner names what would leave the device through
 * export — a fact true regardless of which scope is selected — so it shows unconditionally, not
 * only once a scope is exportable. Verbatim from `Settings-Export.dc.html`.
 *
 * Register R-1009 (WPX): the "Export is not available in this build" `FailedState` used to show
 * unconditionally, because no exporter existed at all. One now does — the `FailedState` shows only
 * while [scope] is `ExportScope.RANGE` (the one scope with no real query behind it, [ExportScope]'s
 * own kdoc), and `Save file` calls [onSaveFile] and is enabled for the other two.
 *
 * R-1035: [preview] renders between the `FailedState` and the button — `null` (still loading, or
 * `RANGE`) shows nothing rather than a fabricated count.
 *
 * R-1045(b): [fileName]/[sizeBytes] feed [buildSaveButtonLabel] — see that function's own kdoc for
 * why [sizeBytes] alone (never [fileName]) is allowed to be honestly absent from the label. */
@Composable
private fun ExportFooter(
    scope: ExportScope,
    preview: ExportCountPreview?,
    format: ExportFileFormat,
    fileName: String?,
    sizeBytes: Long?,
    onSaveFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
        } else {
            ExportPreviewRow(preview = preview, format = format, modifier = Modifier.padding(top = OrtSpacing.md))
        }

        PrimaryButton(
            text = buildSaveButtonLabel(fileName = fileName, sizeBytes = sizeBytes),
            onClick = onSaveFile,
            enabled = scope != ExportScope.RANGE,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = OrtSpacing.md, bottom = OrtSpacing.lg)
                .testTag("export-save-file-button"),
        )
    }
}

/**
 * R-1045(b) (register, design): `Settings-Export.dc.html`'s button reads
 * `Save file · <filename> · <size>`; the build showed only `Save file`. [fileName] is `null` only
 * while `RANGE` is selected ([SettingsExportScreen]'s own `displayFileName`) — `Save file` is
 * disabled for that scope regardless, so the label degrades to plain "Save file" rather than
 * naming a file that will never be written. [sizeBytes] is separately nullable — still loading, or
 * [org.ort.app.export.ExportCoordinator.previewSizeBytes] itself failed — and is honestly omitted
 * from the label rather than replaced with an estimate (constitution I/VI): the artboard's own
 * `184 KB` is real, [ExportCoordinator.previewSizeBytes]'s own kdoc explains how, but a size this
 * screen cannot yet vouch for must never be shown as if it could. [PrimaryButton]'s own `Text` has
 * no `maxLines`/`overflow` cap (unlike [ExportPreviewRow]'s own `FlowRow` fix, this is a *single*
 * `Text`, which already wraps at word/hyphen boundaries by default) — a long filename wraps the
 * button onto a second line rather than being clipped or overflowing it, and [PrimaryButton]'s own
 * `requiredHeightIn(min = 48.dp)` is a floor, not a fixed height, so the button grows to fit.
 */
private fun buildSaveButtonLabel(fileName: String?, sizeBytes: Long?): String = buildString {
    append("Save file")
    fileName?.let {
        append(" · ")
        append(it)
    }
    if (fileName != null) {
        sizeBytes?.let {
            append(" · ")
            append(formatExportSize(it))
        }
    }
}

/** `184 KB`/`1.4 MB` — the same threshold/precision idiom this package's own
 * `SettingsContent.kt#formatFieldReportSize`/`SettingsPolling.kt#formatDiagnosticsSize` already use
 * for a real, computed byte count; not shared code with either (each of those three call sites
 * formats a size for a different, independently-owned screen) but the identical rule, so the same
 * number reads the same way everywhere in Settings. */
private fun formatExportSize(bytes: Long): String {
    val mb = bytes / 1_000_000.0
    val kb = bytes / 1_000.0
    return if (mb >= 1.0) "%.1f MB".format(Locale.ROOT, mb) else "%.0f KB".format(Locale.ROOT, kb)
}

/**
 * **R-1035** (register): shows how many of [ExportCountPreview.totalCount] records would actually
 * become a record a reader sees — [ExportCountPreview.exportableCount], and why the rest would
 * not — *before* `Save file` is pressed, from [ExportCoordinator.previewCount] itself, never a
 * second, independently-scoped estimate. `null` renders nothing (still loading — constitution I:
 * never a fabricated count).
 *
 * Built from several small `Text` runs, not one interpolated sentence, so every count is its own
 * separately tagged, independently-queryable node (`export-preview-exportable`/`-total`/
 * `-excluded`) — a test asserts the real `Int`s this row is built from directly, never the
 * surrounding wording (" of ", " as QSOs · ", [exclusionReasonWord]'s own phrase) — constitution
 * II: a caption's prose is not something a test may lock a designer's copy to, only the facts it
 * reports.
 *
 * R-1045(a) (register, design, `overnight/CF07-settings-export@2x-end.png`): a plain (non-wrapping)
 * `Row` measures every one of these `Text` segments against one shared width budget, consumed in
 * order — at font scale 2.0 the neutral segments alone ("39", " of ", "42", " as QSOs · ") could
 * consume the whole line before the amber "3 have no identified station" segment was even
 * measured, leaving it a hanging sliver at the row's right edge that then wrapped one word per
 * line down a narrow column — the same defect class `ui/screens/NowScreen.kt`'s own R-260/R-552
 * (`EarlierNightMetaLine`/`NowIdleMetaRow`) and `ui/components/Rows.kt`'s own R-373/R-420 already
 * fixed for exactly this shape, by exactly this fix: `FlowRow` (never a plain `Row`, which cannot
 * wrap at all) gives every segment room to be measured as a whole, atomic unit, and wraps a segment
 * that does not fit onto a fresh line that starts at the row's own left edge — never a narrow
 * leftover column — rather than shrinking or hyphen-splitting it. `spacedBy(0.dp)` matches
 * `EarlierNightMetaLine`'s own choice: the spacing already lives inside each segment's own leading/
 * trailing text (`" of "`, `" as QSOs · "`), so the row adds none of its own.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExportPreviewRow(preview: ExportCountPreview?, format: ExportFileFormat, modifier: Modifier = Modifier) {
    if (preview == null) return
    val dimColor = OrtColors.textDim
    val flagColor = OrtColors.accentAmberDim
    FlowRow(
        modifier = modifier.fillMaxWidth().testTag("export-preview"),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (preview.totalCount == 0) {
            Text(text = "Nothing to export for this scope.", style = OrtType.subLine, color = dimColor)
        } else {
            Text(
                text = "${preview.exportableCount}",
                style = OrtType.subLine,
                color = dimColor,
                modifier = Modifier.testTag("export-preview-exportable"),
            )
            Text(text = " of ", style = OrtType.subLine, color = dimColor)
            Text(
                text = "${preview.totalCount}",
                style = OrtType.subLine,
                color = dimColor,
                modifier = Modifier.testTag("export-preview-total"),
            )
            if (preview.excludedCount == 0) {
                Text(text = " will be exported.", style = OrtType.subLine, color = dimColor)
            } else {
                Text(text = " as QSOs · ", style = OrtType.subLine, color = dimColor)
                Text(
                    text = "${preview.excludedCount}",
                    style = OrtType.subLine,
                    color = flagColor,
                    modifier = Modifier.testTag("export-preview-excluded"),
                )
                Text(
                    text = " ${exclusionReasonWord(format)}",
                    style = OrtType.subLine,
                    color = flagColor,
                    // R-1045(a): geometry-only tag — a test may assert where this segment renders
                    // (its own left edge on a wrapped line, never a narrow trailing column) but
                    // never its text content, which stays free to be reworded at any time
                    // (constitution II, this file's own `exclusionReasonWord` doc comment).
                    modifier = Modifier.testTag("export-preview-excluded-reason"),
                )
            }
        }
    }
}

/**
 * **R-1035**: honest about a format's own real limits — only [ExportFileFormat.ADIF] ever excludes
 * a record at all (`AdifExportWriter`'s own `<EOR>` gate, [ExportCountPreview]'s own kdoc). Never
 * asserted directly by a test ([ExportPreviewRow]'s own doc comment) — a designer may rephrase
 * this word at any time without breaking test coverage of the counts it qualifies.
 */
private fun exclusionReasonWord(format: ExportFileFormat): String = when (format) {
    ExportFileFormat.ADIF -> "have no identified station"
    ExportFileFormat.CSV, ExportFileFormat.JSON, ExportFileFormat.TEXT -> "are excluded"
}
