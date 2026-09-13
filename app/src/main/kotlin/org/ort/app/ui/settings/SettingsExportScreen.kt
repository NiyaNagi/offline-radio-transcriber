package org.ort.app.ui.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
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

        ExportSaveFileButton(
            fileName = fileName,
            sizeBytes = sizeBytes,
            enabled = scope != ExportScope.RANGE,
            onClick = onSaveFile,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = OrtSpacing.md, bottom = OrtSpacing.lg),
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
 * screen cannot yet vouch for must never be shown as if it could.
 *
 * This is the *full, real* label — always the single-line shape, filename and size never
 * shortened — used for the accessible name ([ExportSaveFileButton]'s own `contentDescription`) and
 * for the visible label itself below [LARGE_FONT_SCALE_THRESHOLD] ([ExportSaveFileButtonContent]),
 * where it already fits on one line without needing to wrap or truncate at all.
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

/** R-1044/etc.'s own threshold family (`ui/screens/LiveMonitorScreen.kt`, `ui/setup/LevelScreen.kt`,
 * `ui/setup/ReadyScreen.kt` each define their own copy at the same figure) — the smallest scale a
 * real capture showed a two-fact row needed to stack rather than share one line. */
private const val LARGE_FONT_SCALE_THRESHOLD = 1.5f

/**
 * R-1050 (register, round 2 — the round 1 fix was sent back): the artboard's button *names the
 * export* — `Save file · <filename> · <size>` — so the operator knows what will be written before
 * they commit (R-1045(b)'s whole purpose). Round 1 used a fixed character budget
 * (`MAX_VISIBLE_FILE_NAME_LENGTH`, first 26, then 12) that had no idea how much of the button's own
 * real width was actually available at the device's own real font metrics: 26 let the appended size
 * overflow past the button (silently eaten by the `TextOverflow.Ellipsis` backstop); 12 threw away
 * the scope word *and* the timestamp — the two facts that say *which* export this is — while using
 * only about half the button's own measured width. [SaveFileLabelPlan] fixes this by *measuring*
 * real candidate strings against the real available width with a real
 * [androidx.compose.ui.text.TextMeasurer] and the button's own real [TextStyle] — never a character
 * count standing in for a pixel width. `internal`, not `private` — `SettingsExportScreenGeometryTest`'s
 * own tests call [planSaveFileLabel] directly with an explicit, known `availableWidthPx`, the most
 * precise way to prove its decision ladder for each width scenario the register cares about,
 * independent of what any particular Robolectric layout pass happens to measure for a given nominal
 * dp width (Robolectric's own font/layout metrics do not reliably match a real device's — this
 * package's own R-260/R-552 precedent already states this, and this round's own real-device
 * recapture confirms it again: several nominal widths this file first tried rendering at measured
 * as fitting the *entire* name and size on Robolectric alone, never even reaching the ladder's
 * shorter rungs, while the real device needed them). */
internal data class SaveFileLabelPlan(val filenameLine: String, val sizeLine: String?)

/**
 * For each of [filenameCandidates]' rungs, from most to least informative: try it plus the size on
 * one line; if that does not fit, try it alone (the size gets its own third line rather than the
 * name being cut to make room for it — "put the size on its own third line rather than cutting the
 * name further", the register's own instruction) — and only move to the next, shorter rung once
 * even the candidate alone does not fit. The first that measures within [availableWidthPx] at
 * [style] wins — [textMeasurer] measures with unbounded constraints, so the width returned is a
 * candidate's own real, natural, single-line width, never an estimate. The ladder's own last rung
 * (`ort-export-….<ext>`, extension always kept) is short enough to fit any real supported device
 * width in practice (device-verified — the round that found the previous rung insufficient here is
 * this round's own CHANGELOG entry), so the loop always returns from inside itself; there is no
 * separate "nothing fit" branch to fall through to.
 */
internal fun planSaveFileLabel(
    fileName: String,
    sizeBytes: Long?,
    availableWidthPx: Int,
    style: TextStyle,
    textMeasurer: TextMeasurer,
): SaveFileLabelPlan {
    fun widthOf(text: String): Int = textMeasurer.measure(text = text, style = style).size.width
    val sizeSuffix = sizeBytes?.let { " · ${formatExportSize(it)}" }
    val candidates = filenameCandidates(fileName)
    for (candidate in candidates) {
        if (sizeSuffix != null && widthOf(candidate + sizeSuffix) <= availableWidthPx) {
            return SaveFileLabelPlan(candidate + sizeSuffix, null)
        }
        if (widthOf(candidate) <= availableWidthPx) {
            return SaveFileLabelPlan(candidate, sizeSuffix)
        }
    }
    // Narrower than any real supported device width (device-verified for the ladder's own
    // shortest rung) — the narrowest candidate is still shown in full, never a silently dropped
    // identity; `softWrap = false` plus the caller's own `overflow = TextOverflow.Ellipsis` is the
    // last-resort backstop for a width this build does not actually support.
    return SaveFileLabelPlan(candidates.last(), sizeSuffix)
}

/**
 * R-1050 (register): the real name's own shape from [ExportCoordinator.suggestedFileName] is
 * `ort-export-<scope>-<yyyyMMdd>-<HHmmss>.<ext>` — five hyphen-delimited stem tokens, the last two
 * always all-digit (the timestamp). A *ladder* of candidates beyond the name itself, each one
 * dropping a little more, in the order the register asked for: first the scope word (the token(s)
 * between the fixed `ort-export-` prefix and the timestamp) — "prefer keeping the timestamp over
 * the scope word when something must go" — then, only if even that does not fit, the timestamp's
 * own trailing token(s) one at a time (the time-of-day before the date, since the date alone still
 * says which night this is), never the extension, which every candidate keeps. Round 2's own first
 * attempt stopped after the one "drop the scope word" candidate and let a plain `Text`-level
 * `overflow = TextOverflow.Ellipsis` backstop cut whatever was still too wide — the real device
 * recapture verifying *that* fix found it cutting into the timestamp and the extension both, on a
 * screen narrow enough that even the scope-less candidate did not fit; this ladder exists so the
 * measured choice in [planSaveFileLabel] never needs that backstop to actually fire in practice, and
 * the extension survives even in that narrow case. Every cut is always a real hyphen already in
 * [fileName], never mid-digit-run. If [fileName] does not match the expected shape (a future naming
 * change, a test fixture with no scope token at all), the real name is the only candidate — never a
 * guess at a structure that is not actually there. Internal, not private —
 * `SettingsExportScreenGeometryTest`'s own tests assert this pure function directly, the most
 * precise way to prove "never a hyphen-delimited token's own middle" for every input shape this
 * function must handle, not only the one real shape a render-level test happens to exercise.
 */
internal fun filenameCandidates(fileName: String): List<String> {
    val dot = fileName.lastIndexOf('.')
    val extension = if (dot >= 0) fileName.substring(dot) else ""
    val stem = if (dot >= 0) fileName.substring(0, dot) else fileName
    val tokens = stem.split('-')
    val timestampStart = tokens.indexOfFirst { it.isNotEmpty() && it.all(Char::isDigit) }
    if (timestampStart < 3 || timestampStart > tokens.size - 2) return listOf(fileName)
    val prefix = tokens.subList(0, 2).joinToString("-")
    val timestampTokens = tokens.subList(timestampStart, tokens.size)
    val ladder = (timestampTokens.size downTo 0).map { keep ->
        val kept = timestampTokens.take(keep)
        val middle = if (kept.isEmpty()) "…" else "…-" + kept.joinToString("-")
        "$prefix-$middle$extension"
    }
    return (listOf(fileName) + ladder).distinct()
}

/**
 * R-1050 (register): the R-1045(b) shape — [org.ort.app.ui.components.PrimaryButton] wrapping the
 * single, always-one-line [buildSaveButtonLabel] — is correct at 1.0 (the label already fits on one
 * line there) but at font scale 2.0 the label's own natural word-wrap broke *inside* the filename's
 * own timestamp (`ort-export-tonight-202609` / `13-022935.adi · 8 KB` — a genuine on-device finding,
 * not a hypothetical), reading as a corrupted name rather than a wrapped sentence. This composable
 * replaces that direct `PrimaryButton` call, reusing its exact visual style, and delegates the
 * visible content to [ExportSaveFileButtonContent] (below [LARGE_FONT_SCALE_THRESHOLD]: the same
 * single-line label as before; at or above it: [planSaveFileLabel]'s measured two- or three-line
 * shape). The content is split into its own composable purely so a test can render it directly:
 * this button's own `clearAndSetSemantics` (below — the R-380/R-543 accessible-name fix this file's
 * own sibling buttons all carry, see `PrimaryButton`'s own doc comment for why it cannot be dropped)
 * genuinely prunes every descendant from the semantics tree on a real device (R-543's own finding),
 * so no semantics-based query from inside a test could ever reach a tagged node beneath it
 * otherwise.
 */
@Composable
private fun ExportSaveFileButton(
    fileName: String?,
    sizeBytes: Long?,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = buildSaveButtonLabel(fileName, sizeBytes)
    val bg = if (enabled) OrtColors.accentGreen else OrtColors.bgChip
    val fg = if (enabled) OrtColors.accentOnGreen else OrtColors.textDisabled
    Box(
        modifier = modifier
            .requiredHeightIn(min = 48.dp)
            .background(bg, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp)
            .testTag("export-save-file-button")
            .clearAndSetSemantics {
                contentDescription = label
                // R-380 correction (WP2, gate-blocking) — see `PrimaryButton`'s own doc comment.
                this.text = AnnotatedString(label)
                role = Role.Button
                if (enabled) {
                    onClick(label = null) {
                        onClick()
                        true
                    }
                } else {
                    disabled()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        ExportSaveFileButtonContent(fileName = fileName, sizeBytes = sizeBytes, color = fg)
    }
}

/**
 * [ExportSaveFileButton]'s own visible content, split out — see that composable's own doc comment
 * for why: `internal`, not `private`, purely so `SettingsExportScreenGeometryTest`'s own tests can
 * render it directly, inside an equivalent test host, and reach its tagged nodes at all.
 *
 * R-1050(b): a fixed `Modifier.padding(vertical = OrtSpacing.md)` around the content, *inside* the
 * button's own `requiredHeightIn(min = 48.dp)` floor — before this, the button had no vertical
 * padding of its own at all, only `contentAlignment = Center` inside a Box that grows to fit its
 * content exactly once that content exceeds 48dp, so a two-line label filled the button with zero
 * slack on either side; the lead's own capture showed the *result* of that (first line flush
 * against the top edge, more room below the last line) rather than its cause, but either way the
 * fix is the same: reserve real, equal space top and bottom, unconditionally, so the button's own
 * padding survives however tall the label grows — one line, two, or [planSaveFileLabel]'s own
 * third.
 *
 * R-1050 round 2: [BoxWithConstraints] gives the real available width for the filename/size lines
 * (this button's own real horizontal padding already subtracted, since this composable sits inside
 * [ExportSaveFileButton]'s already-padded `Box`) in px, converted with the real [LocalDensity] —
 * the same real pixels [rememberTextMeasurer]'s `TextMeasurer` measures candidate strings against
 * in [planSaveFileLabel]. Never a character count guessing at what a pixel width allows.
 */
@Composable
internal fun ExportSaveFileButtonContent(
    fileName: String?,
    sizeBytes: Long?,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val fontScale = LocalDensity.current.fontScale
    val style = OrtType.control.copy(fontWeight = FontWeight.Medium)
    Box(
        // `fillMaxWidth()` here, not left to the caller's own outer Box, so this content always
        // centres itself across the button's *full* inner width regardless of how that outer Box
        // aligns an unconstrained child (R-1050 follow-up, found writing this composable's own
        // isolated test host, which has no reason to default to the button's own alignment).
        modifier = modifier.fillMaxWidth().padding(vertical = OrtSpacing.md),
        contentAlignment = Alignment.Center,
    ) {
        if (fileName != null && fontScale >= LARGE_FONT_SCALE_THRESHOLD) {
            BoxWithConstraints {
                val density = LocalDensity.current
                val availableWidthPx = with(density) { maxWidth.roundToPx() }
                val textMeasurer = rememberTextMeasurer()
                val plan = remember(fileName, sizeBytes, availableWidthPx, style) {
                    planSaveFileLabel(fileName, sizeBytes, availableWidthPx, style, textMeasurer)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Save file",
                        style = style,
                        color = color,
                        modifier = Modifier.testTag("export-save-file-line1"),
                    )
                    Text(
                        text = plan.filenameLine,
                        style = style,
                        color = color,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("export-save-file-line2"),
                    )
                    plan.sizeLine?.let {
                        Text(
                            text = it,
                            style = style,
                            color = color,
                            softWrap = false,
                            modifier = Modifier.testTag("export-save-file-line3"),
                        )
                    }
                }
            }
        } else {
            Text(
                text = buildSaveButtonLabel(fileName, sizeBytes),
                style = style,
                color = color,
                modifier = Modifier.testTag("export-save-file-line1"),
            )
        }
    }
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
