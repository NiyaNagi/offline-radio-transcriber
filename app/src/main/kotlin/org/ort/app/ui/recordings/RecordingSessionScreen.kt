package org.ort.app.ui.recordings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.AttributionRow
import org.ort.app.ui.components.DrillInHeader
import org.ort.app.ui.components.LoadingState
import org.ort.app.ui.components.OrtIcons
import org.ort.app.ui.components.ScoreChip
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.components.Sheet
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.components.rememberTimeColumnWidth
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType
import org.ort.core.AttributionState
import org.ort.pipeline.archive.ArchiveState
import org.ort.pipeline.archive.SessionAudioDeletionRefusal
import org.ort.pipeline.archive.SessionAudioExportRefusal

/**
 * `Recording-Session.dc.html` (RC02): one recording session, in full — the coverage strip, every
 * over and gap in order, and the session actions (play all, export, label, delete with the space
 * it frees stated before it is tapped). A pure function of [state] plus whatever the transport
 * controller and the three pending-sheet states say right now — [RecordingSessionContent] is the
 * stateful entry point that polls the real data and wires [actions] to the real services.
 *
 * [state] `null` renders the loading row (R-1051's own discipline — never the empty/failed default
 * before the first real read returns).
 */
@Composable
public fun RecordingSessionScreen(
    state: RecordingSessionViewState?,
    playingOverId: String?,
    isPlaying: Boolean,
    actions: RecordingSessionActions,
    deleteSheet: RecordingSessionDeleteState,
    exportSheet: RecordingSessionExportState,
    labelSheet: RecordingSessionLabelSheetViewState?,
    modifier: Modifier = Modifier,
) {
    // R-1201 (register): RC02's own step shape (`recordingSession`) had no case of its own and fell
    // through to the `EARLIER_NIGHTS` destination default, `Text("Recordings")` — which this
    // screen's own back label carries *and* the always-composed drawer row does, so it proved
    // neither that RC02 had been reached nor that anything at all had rendered.
    Column(modifier = modifier.fillMaxSize().testTag("recording-session-screen")) {
        DrillInHeader(parentLabel = "Recordings", onBack = actions.onBack)
        if (state == null) {
            // Round 2 (coordinator review), register R-1022: the shared `LoadingState` (not a plain
            // `Text`) so a screenshot tour step's own placeholder detection recognises this session
            // is still loading, the same discipline every other WPRC/WPI screen already holds itself
            // to — RC02's own `RECORDING_SESSION_LOADING_TEST_TAG` still names the outer node so
            // this screen's own tests need not change what they assert on.
            Column(modifier = Modifier.padding(OrtSpacing.lg).testTag(RECORDING_SESSION_LOADING_TEST_TAG)) {
                LoadingState(message = "Loading recording session")
            }
            return@Column
        }
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = OrtSpacing.lg),
        ) {
            RecordingSessionHeaderSection(state.header, modifier = Modifier.padding(top = OrtSpacing.xs))
            val firstOverId = state.rows.filterIsInstance<RecordingSessionRow.Over>().firstOrNull()?.id
            RecordingSessionActionsRow(
                deleteFreesBytesLabel = recordingSessionByteLabel(state.deleteFreesBytes),
                exportAvailable = state.exportAvailable,
                firstOverId = firstOverId,
                actions = actions,
                modifier = Modifier.padding(top = OrtSpacing.md),
            )
            RecordingSessionCoverageCard(state.coverage, modifier = Modifier.padding(top = OrtSpacing.md))
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = OrtSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionHeader(label = "Overs · in order", modifier = Modifier.weight(1f))
                Text(
                    text = "${state.rows.count { it is RecordingSessionRow.Over }}",
                    style = OrtType.axis,
                    color = OrtColors.textFaint,
                    modifier = Modifier.padding(end = OrtSpacing.sm),
                )
                // Not on the artboard directly — this session's own link into the Log
                // (`openLogFiltered`, a Recordings origin so back returns here), the same "a full
                // log escape hatch beside the compact list" N07's own `Full log` action establishes.
                TextAction(
                    text = "Log",
                    onClick = actions.onOpenLog,
                    modifier = Modifier.testTag(RECORDING_SESSION_OPEN_LOG_TEST_TAG),
                )
            }
            Column(modifier = Modifier.padding(top = OrtSpacing.xs)) {
                state.rows.forEach { row ->
                    when (row) {
                        is RecordingSessionRow.Over -> RecordingSessionOverRow(
                            row = row,
                            isPlaying = isPlaying && playingOverId == row.id,
                            actions = actions,
                            modifier = Modifier.testTag("rc02-over-row-${row.id}"),
                        )
                        is RecordingSessionRow.Gap -> RecordingSessionGapRow(
                            row = row,
                            modifier = Modifier.testTag("rc02-gap-row-${row.id}"),
                        )
                    }
                }
            }
        }
    }

    if (deleteSheet != RecordingSessionDeleteState.Idle) {
        DeleteConfirmSheet(deleteSheet, actions)
    }
    if (exportSheet != RecordingSessionExportState.Idle) {
        ExportSheet(exportSheet, actions)
    }
    if (labelSheet != null) {
        LabelSheet(labelSheet, actions)
    }
}

/** Every callback [RecordingSessionScreen] fires — bundled per this codebase's own
 * `NavHostCallbacks`/`TransportBarActions` shape, so this composable's own parameter count stays
 * under detekt's `LongParameterList`. */
public data class RecordingSessionActions(
    public val onBack: () -> Unit = {},
    public val onOpenLog: () -> Unit = {},
    public val onPlayAll: () -> Unit = {},
    public val onPlayRow: (String) -> Unit = {},
    public val onOpenTransmission: (String) -> Unit = {},
    public val onOpenStation: (String) -> Unit = {},
    public val onRetry: (String) -> Unit = {},
    public val onOpenLabelSheet: (String) -> Unit = {},
    public val onCloseLabelSheet: () -> Unit = {},
    public val onSetMarkedForTraining: (Boolean) -> Unit = {},
    public val onSetRating: (String) -> Unit = {},
    public val onOpenDeleteSheet: () -> Unit = {},
    public val onCloseDeleteSheet: () -> Unit = {},
    public val onConfirmDelete: () -> Unit = {},
    public val onOpenExportSheet: () -> Unit = {},
    public val onCloseExportSheet: () -> Unit = {},
    public val onConfirmExport: () -> Unit = {},
)

@Composable
private fun RecordingSessionHeaderSection(header: RecordingSessionHeaderViewState, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(text = header.dateLabel, style = OrtType.screenTitle, color = OrtColors.textHigh)
        Text(
            text = listOfNotNull(header.timeRangeLabel, header.durationLabel, header.modeLabel).joinToString(" · "),
            style = OrtType.subtitle,
            color = OrtColors.textDim,
            modifier = Modifier.padding(top = 3.dp),
        )
        Text(
            text = header.countsLabel,
            style = OrtType.signal,
            color = OrtColors.textLow,
            modifier = Modifier.padding(top = 4.dp),
        )
        // D50 (Q22, FR-SEG-10, AC-162): the durable half of the disclosure — the session's own
        // record of which detector produced its boundaries, surviving after the live bar's own
        // chip disappears at session end. Named for every session, not only a fallback one.
        Text(
            text = "Segmentation: ${header.vadDetectorLabel}",
            style = OrtType.subLine,
            color = OrtColors.textFaint,
            modifier = Modifier.padding(top = 3.dp).testTag(RECORDING_SESSION_VAD_DETECTOR_TEST_TAG),
        )
    }
}

/**
 * The artboard's own interaction column is silent on what the top `Label` tile does (unlike every
 * other tile, whose destination it states outright) — the per-row `Label` action wired on
 * [RecordingSessionOverRow] is this screen's real, primary entry point into
 * [org.ort.pipeline.label.TransmissionLabelRepository]. This tile is a shortcut to the same sheet
 * for [firstOverId] — this session's own first real over, never a fabricated target — so the tile
 * is not a dead tap while a genuinely session-wide label action remains unspecified (recorded here
 * rather than guessed silently, constitution VIII's own "accepted deviations are written down"
 * discipline applied to an interaction gap, not a visual one).
 */
@Composable
private fun RecordingSessionActionsRow(
    deleteFreesBytesLabel: String,
    exportAvailable: Boolean,
    firstOverId: String?,
    actions: RecordingSessionActions,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(OrtSpacing.sm)) {
        ActionTile(
            icon = OrtIcons.play,
            label = "Play all",
            onClick = actions.onPlayAll,
            modifier = Modifier.weight(1f).testTag(RECORDING_SESSION_PLAY_ALL_TEST_TAG),
        )
        ActionTile(
            icon = OrtIcons.export,
            label = "Export",
            iconTint = OrtColors.textIcon,
            enabled = exportAvailable,
            onClick = actions.onOpenExportSheet,
            modifier = Modifier.weight(1f).testTag(RECORDING_SESSION_EXPORT_TEST_TAG),
        )
        ActionTile(
            icon = OrtIcons.edit,
            label = "Label",
            iconTint = OrtColors.textIcon,
            enabled = firstOverId != null,
            onClick = { firstOverId?.let(actions.onOpenLabelSheet) },
            modifier = Modifier.weight(1f).testTag(RECORDING_SESSION_LABEL_TILE_TEST_TAG),
        )
        ActionTile(
            icon = OrtIcons.trash,
            label = "Delete",
            iconTint = OrtColors.haltText,
            caption = "frees $deleteFreesBytesLabel",
            onClick = actions.onOpenDeleteSheet,
            modifier = Modifier.weight(1f).testTag(RECORDING_SESSION_DELETE_TEST_TAG),
        )
    }
}

@Composable
private fun ActionTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconTint: Color = OrtColors.accentGreen,
    caption: String? = null,
    enabled: Boolean = true,
) {
    val description = listOfNotNull(label, caption).joinToString(", ")
    Column(
        modifier = modifier
            .heightIn(min = 58.dp)
            .background(OrtColors.bgCard, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick)
            .padding(vertical = OrtSpacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) iconTint else OrtColors.textDisabled,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            style = OrtType.chip,
            color = if (enabled) OrtColors.textBody else OrtColors.textDisabled,
            modifier = Modifier.padding(top = 4.dp),
        )
        caption?.let {
            Text(text = it, style = OrtType.axis, color = OrtColors.textFaint, modifier = Modifier.padding(top = 1.dp))
        }
    }
}

@Composable
private fun RecordingSessionCoverageCard(coverage: RecordingSessionCoverageViewState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(OrtColors.bgRaised, RoundedCornerShape(8.dp))
            .padding(horizontal = OrtSpacing.md, vertical = OrtSpacing.sm)
            .testTag(RECORDING_SESSION_COVERAGE_TEST_TAG),
    ) {
        SectionHeader(label = "Coverage")
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp)
                .padding(top = 8.dp)
                .background(OrtColors.lineDefault, RoundedCornerShape(3.dp)),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                coverage.gaps.forEach { gap ->
                    val left = size.width * gap.fractionStart
                    val right = size.width * gap.fractionEnd
                    drawRect(
                        color = OrtColors.hatchBar,
                        topLeft = Offset(left, 0f),
                        size = Size((right - left).coerceAtLeast(1f), size.height),
                    )
                }
                coverage.ticks.forEach { tick ->
                    val x = size.width * ((tick.fractionStart + tick.fractionEnd) / 2f)
                    val color = if (tick.failed) OrtColors.accentAmber else OrtColors.accentGreen
                    drawLine(
                        color = color,
                        start = Offset(x, size.height * 0.3f),
                        end = Offset(x, size.height),
                        strokeWidth = 2.dp.toPx(),
                    )
                }
                coverage.playheadFraction?.let { fraction ->
                    val x = size.width * fraction
                    drawLine(
                        color = OrtColors.textHigh,
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 2.dp.toPx(),
                    )
                }
            }
        }
        if (coverage.axisLabels.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                coverage.axisLabels.forEach { label ->
                    Text(text = label, style = OrtType.axis, color = OrtColors.textLow)
                }
            }
        }
    }
}

private fun overRowDescription(row: RecordingSessionRow.Over): String = listOfNotNull(
    row.timeLabel,
    row.attributionStateLabel,
    row.callsign,
    row.transcript,
    row.statusReasonLabel,
    row.trainingLabel,
).joinToString(", ")

/** guide's own "2.0" ceiling (FR-A11Y-3, AC-63) — the same figure [LiveMonitorScreen.kt]'s own
 * `LARGE_FONT_SCALE_THRESHOLD` (R-1027) and `ui/components/Rows.kt`'s own `LogRow` (R-373) already
 * apply for "a fixed-width sibling column squeezes the weighted one below its own floor." Not
 * re-imported from either — both are `private`/file-local too, the same small policy-constant shape
 * this codebase already repeats per file rather than sharing. */
private const val RC02_LARGE_FONT_SCALE_THRESHOLD = 1.5f

/**
 * **Round 3** (coordinator review, device evidence): below [RC02_LARGE_FONT_SCALE_THRESHOLD] this
 * row keeps its one-line shape — a fixed play control and time column beside a weighted content
 * column and a fixed Label action. At or above it, the fixed columns (play, time, Label) together
 * left the weighted column under a third of the row's own real width — a transcript wrapping one
 * or two words per line, a facts line breaking at every `·`, an INFERRED confidence chip cut to a
 * sliver (`round2\tour\overnight\RC02-recording-session@2x-end.png`, WA7HJR/KJ7ABC). The same
 * threshold-based restack `LiveMonitorScreen.kt`'s own `LevelCardCaption` (R-1027) and
 * `ui/components/Rows.kt`'s own `LogRow` (R-373) already establish: time, play and Label move to
 * their own top row (still reachable, never buried, never removed), and the transcript/attribution/
 * facts content renders at the row's own full width beneath it.
 */
@Composable
private fun RecordingSessionOverRow(
    row: RecordingSessionRow.Over,
    isPlaying: Boolean,
    actions: RecordingSessionActions,
    modifier: Modifier = Modifier,
) {
    val rowModifier = modifier
        .fillMaxWidth()
        .heightIn(min = 44.dp)
        .then(
            if (isPlaying) {
                Modifier.background(OrtColors.bgCurrent).border(2.dp, OrtColors.accentGreen)
            } else {
                Modifier
            },
        )
        .padding(vertical = OrtSpacing.sm)
        .semantics(mergeDescendants = true) { contentDescription = overRowDescription(row) }
    if (LocalDensity.current.fontScale >= RC02_LARGE_FONT_SCALE_THRESHOLD) {
        RecordingSessionOverRowStacked(row, isPlaying, actions, rowModifier)
    } else {
        RecordingSessionOverRowCompact(row, isPlaying, actions, rowModifier)
    }
}

@Composable
private fun RecordingSessionOverRowCompact(
    row: RecordingSessionRow.Over,
    isPlaying: Boolean,
    actions: RecordingSessionActions,
    modifier: Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(OrtSpacing.sm)) {
        RecordingSessionPlayControl(
            hasAudio = row.hasAudio,
            isPlaying = isPlaying,
            onClick = { actions.onPlayRow(row.id) },
            modifier = Modifier.testTag("rc02-play-${row.id}"),
        )
        Text(
            text = row.timeLabel,
            style = OrtType.timeFreq,
            color = OrtColors.textDim,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(top = 1.dp).widthIn(min = rememberTimeColumnWidth()),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(role = Role.Button, onClick = { actions.onOpenTransmission(row.id) })
                .testTag(recordingSessionOverContentTestTag(row.id)),
        ) {
            RecordingSessionOverBody(row, actions)
        }
        TextAction(
            text = "Label",
            onClick = { actions.onOpenLabelSheet(row.id) },
            modifier = Modifier.testTag("rc02-label-${row.id}"),
        )
    }
}

@Composable
private fun RecordingSessionOverRowStacked(
    row: RecordingSessionRow.Over,
    isPlaying: Boolean,
    actions: RecordingSessionActions,
    modifier: Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(OrtSpacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RecordingSessionPlayControl(
                hasAudio = row.hasAudio,
                isPlaying = isPlaying,
                onClick = { actions.onPlayRow(row.id) },
                modifier = Modifier.testTag("rc02-play-${row.id}"),
            )
            Text(
                text = row.timeLabel,
                style = OrtType.timeFreq,
                color = OrtColors.textDim,
                maxLines = 1,
                softWrap = false,
            )
            Spacer(modifier = Modifier.weight(1f))
            TextAction(
                text = "Label",
                onClick = { actions.onOpenLabelSheet(row.id) },
                modifier = Modifier.testTag("rc02-label-${row.id}"),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = { actions.onOpenTransmission(row.id) })
                .testTag(recordingSessionOverContentTestTag(row.id)),
        ) {
            RecordingSessionOverBody(row, actions)
        }
    }
}

@Composable
private fun RecordingSessionOverBody(row: RecordingSessionRow.Over, actions: RecordingSessionActions) {
    when (row.status) {
        RecordingSessionOverStatus.RESOLVED -> ResolvedOverBody(row)
        RecordingSessionOverStatus.PROCESSING -> ProcessingOverBody()
        RecordingSessionOverStatus.REJECTED -> RejectedOverBody(row)
        RecordingSessionOverStatus.FAILED -> FailedOverBody(row, actions)
    }
}

internal fun recordingSessionOverContentTestTag(overId: String): String = "rc02-over-content-$overId"

internal fun recordingSessionOverChipTestTag(overId: String): String = "rc02-over-chip-$overId"

@Composable
private fun ResolvedOverBody(row: RecordingSessionRow.Over) {
    row.attribution?.let { attribution ->
        Row {
            AttributionRow(
                attribution = attribution,
                callsign = row.callsign,
                alternate = row.alternate,
                showScore = false,
            )
            if (attribution.state == AttributionState.INFERRED) {
                attribution.confidence?.let {
                    ScoreChip(
                        confidence = it,
                        modifier = Modifier.padding(start = 4.dp).testTag(recordingSessionOverChipTestTag(row.id)),
                    )
                }
            }
        }
    }
    row.transcript?.let { text ->
        Text(
            text = text,
            style = OrtType.transcript,
            color = OrtColors.textHigh,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
    Text(
        text = listOfNotNull(
            row.durationLabel,
            row.frequencyLabel,
            row.attributionStateLabel,
            row.inferredFromLabel?.let { "inferred from $it" },
        ).joinToString(" · "),
        style = OrtType.subLine,
        color = OrtColors.textDim,
        modifier = Modifier.padding(top = 3.dp),
    )
    row.trainingLabel?.let {
        Text(text = it, style = OrtType.badge, color = OrtColors.accentGreen, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun ProcessingOverBody() {
    Text(text = "processing…", style = OrtType.control.copy(fontStyle = FontStyle.Italic), color = OrtColors.textDim)
}

@Composable
private fun RejectedOverBody(row: RecordingSessionRow.Over) {
    Text(
        text = "rejected · ${row.statusReasonLabel}",
        style = OrtType.subLine,
        color = OrtColors.textFaint,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun FailedOverBody(row: RecordingSessionRow.Over, actions: RecordingSessionActions) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(
                text = "not transcribed",
                style = OrtType.control.copy(fontStyle = FontStyle.Italic),
                color = OrtColors.textSecondary,
            )
            Icon(
                imageVector = OrtIcons.gapWarn,
                contentDescription = null,
                tint = OrtColors.accentAmberText,
                modifier = Modifier.size(14.dp),
            )
            if (row.canRetry) {
                TextAction(
                    text = "Retry",
                    onClick = { actions.onRetry(row.id) },
                    modifier = Modifier.testTag("rc02-retry-${row.id}"),
                )
            }
        }
        Text(
            text = listOfNotNull(row.durationLabel, row.statusReasonLabel, "the audio is kept").joinToString(" · "),
            style = OrtType.subLine,
            color = OrtColors.textDim,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@Composable
private fun RecordingSessionPlayControl(
    hasAudio: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!hasAudio) {
        Box(modifier = modifier.size(30.dp))
        return
    }
    Box(
        modifier = modifier
            .size(30.dp)
            .then(
                if (isPlaying) {
                    Modifier.background(OrtColors.accentGreen, CircleShape)
                } else {
                    Modifier.border(1.5.dp, OrtColors.lineControl, CircleShape)
                },
            )
            .clickable(
                role = Role.Button,
                onClickLabel = if (isPlaying) "Pause" else "Play",
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (isPlaying) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(modifier = Modifier.size(width = 3.dp, height = 11.dp).background(OrtColors.accentOnGreen))
                Box(modifier = Modifier.size(width = 3.dp, height = 11.dp).background(OrtColors.accentOnGreen))
            }
        } else {
            Icon(
                imageVector = OrtIcons.play,
                contentDescription = null,
                tint = OrtColors.textIcon,
                modifier = Modifier.size(11.dp),
            )
        }
    }
}

@Composable
private fun RecordingSessionGapRow(row: RecordingSessionRow.Gap, modifier: Modifier = Modifier) {
    val description = listOfNotNull(
        row.timeLabel,
        row.titleLabel,
        row.causeLabel,
        row.resumedLabel,
        "nothing was recorded",
    ).joinToString(", ")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(vertical = OrtSpacing.sm)
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
    ) {
        Box(modifier = Modifier.size(30.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (row.bluetoothAudioDropped) OrtIcons.interruptedConnector else OrtIcons.gapWarn,
                contentDescription = null,
                tint = OrtColors.accentGapDim,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = row.timeLabel,
            style = OrtType.timeFreq,
            color = OrtColors.textLow,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(top = 1.dp).widthIn(min = rememberTimeColumnWidth()),
        )
        Column {
            Text(text = row.titleLabel, style = OrtType.control, color = OrtColors.textDim)
            Text(
                text = listOfNotNull(row.causeLabel, row.resumedLabel, "nothing was recorded").joinToString(" · "),
                style = OrtType.subLine,
                color = OrtColors.textFaint,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Sheets — Delete, Export, Label.
// ---------------------------------------------------------------------------------------------

@Composable
private fun DeleteConfirmSheet(state: RecordingSessionDeleteState, actions: RecordingSessionActions) {
    Sheet(title = "Delete this session's audio", modifier = Modifier.testTag(RECORDING_SESSION_DELETE_SHEET_TEST_TAG)) {
        when (state) {
            is RecordingSessionDeleteState.Preview -> {
                // The artboard splits `Delete` and its own "frees N GB" onto two lines — repeated
                // here so the two never collide even at a large font scale.
                Text(text = "Delete", style = OrtType.control, color = OrtColors.textHigh)
                Text(
                    text = "frees ${recordingSessionByteLabel(state.bytesToFree)}",
                    style = OrtType.subLine,
                    color = OrtColors.textDim,
                    modifier = Modifier.padding(top = 2.dp).testTag(RECORDING_SESSION_DELETE_FREES_TEST_TAG),
                )
                DeletePreviewBreakdown(state)
                RecordingSessionSheetActions(
                    confirmLabel = "Delete",
                    onConfirm = actions.onConfirmDelete,
                    onDismiss = actions.onCloseDeleteSheet,
                )
            }
            is RecordingSessionDeleteState.Refused -> RecordingSessionRefusalBody(
                message = deletionRefusalMessage(state.reason),
                onDismiss = actions.onCloseDeleteSheet,
            )
            is RecordingSessionDeleteState.Deleted -> {
                Text(
                    text = "Deleted · freed ${recordingSessionByteLabel(state.bytesFreed)}",
                    style = OrtType.control,
                    color = OrtColors.accentGreen,
                    modifier = Modifier.testTag(RECORDING_SESSION_DELETE_DONE_TEST_TAG),
                )
                TextAction(
                    text = "Close",
                    onClick = actions.onCloseDeleteSheet,
                    modifier = Modifier.padding(top = OrtSpacing.sm),
                )
            }
            RecordingSessionDeleteState.Idle -> Unit
        }
    }
}

/**
 * Round 2 (coordinator review): names what "frees N" above is actually made of — "Over audio" and
 * "Raw archive", the same two labels RC01's own budget card uses
 * ([org.ort.app.ui.recordings.RecordingsScreen]) — each shown only while it is a real, present
 * fact: a half already removed, or an archive this session never kept, is an honest omission
 * (constitution I), never a fabricated zero-byte line for something that was never there or is
 * already gone.
 */
@Composable
private fun DeletePreviewBreakdown(state: RecordingSessionDeleteState.Preview) {
    if (!state.overAudioAlreadyRemoved) {
        Text(
            text = "Over audio · ${recordingSessionByteLabel(state.overAudioBytes)}",
            style = OrtType.subLine,
            color = OrtColors.textFaint,
            modifier = Modifier.padding(top = 6.dp).testTag(RECORDING_SESSION_DELETE_OVER_AUDIO_TEST_TAG),
        )
    }
    if (state.archiveState == ArchiveState.KEPT) {
        Text(
            text = "Raw archive · ${recordingSessionByteLabel(state.archiveBytes)}",
            style = OrtType.subLine,
            color = OrtColors.textFaint,
            modifier = Modifier.padding(top = 2.dp).testTag(RECORDING_SESSION_DELETE_ARCHIVE_TEST_TAG),
        )
    }
}

private fun deletionRefusalMessage(reason: SessionAudioDeletionRefusal): String = when (reason) {
    SessionAudioDeletionRefusal.SessionNotFound -> "This session no longer exists."
    SessionAudioDeletionRefusal.SessionCapturing ->
        "This session is capturing right now — its audio cannot be deleted while it runs."
    SessionAudioDeletionRefusal.ProcessingInProgress -> "A pass is still running against this session's audio."
}

@Composable
private fun ExportSheet(state: RecordingSessionExportState, actions: RecordingSessionActions) {
    Sheet(title = "Export this session's audio", modifier = Modifier.testTag(RECORDING_SESSION_EXPORT_SHEET_TEST_TAG)) {
        when (state) {
            is RecordingSessionExportState.Preview -> {
                Text(
                    text = "${state.fileCount} files · ${recordingSessionByteLabel(state.totalBytes)}",
                    style = OrtType.control,
                    color = OrtColors.textHigh,
                    modifier = Modifier.testTag(RECORDING_SESSION_EXPORT_PREVIEW_TEST_TAG),
                )
                Text(
                    text = state.suggestedFileName,
                    style = OrtType.subLine,
                    color = OrtColors.textDim,
                    modifier = Modifier.padding(top = 3.dp),
                )
                RecordingSessionSheetActions(
                    confirmLabel = "Save",
                    onConfirm = actions.onConfirmExport,
                    onDismiss = actions.onCloseExportSheet,
                )
            }
            is RecordingSessionExportState.Refused -> RecordingSessionRefusalBody(
                message = exportRefusalMessage(state.reason),
                onDismiss = actions.onCloseExportSheet,
            )
            is RecordingSessionExportState.Writing -> Text(
                text = "Writing · ${recordingSessionByteLabel(state.bytesWritten)} of " +
                    recordingSessionByteLabel(state.totalBytes),
                style = OrtType.control,
                color = OrtColors.textDim,
                modifier = Modifier.testTag(RECORDING_SESSION_EXPORT_WRITING_TEST_TAG),
            )
            is RecordingSessionExportState.Written -> {
                Text(
                    text = "Saved · ${state.fileCount} files, ${recordingSessionByteLabel(state.totalBytes)}",
                    style = OrtType.control,
                    color = OrtColors.accentGreen,
                    modifier = Modifier.testTag(RECORDING_SESSION_EXPORT_DONE_TEST_TAG),
                )
                TextAction(
                    text = "Close",
                    onClick = actions.onCloseExportSheet,
                    modifier = Modifier.padding(top = OrtSpacing.sm),
                )
            }
            RecordingSessionExportState.Idle -> Unit
        }
    }
}

private fun exportRefusalMessage(reason: SessionAudioExportRefusal): String = when (reason) {
    SessionAudioExportRefusal.SessionNotFound -> "This session no longer exists."
    // Round 3 (coordinator review): `reason.reason` is `:pipeline`'s own diagnostic string (a
    // session id, lowercase, built for a log line) -- keyed on the typed refusal alone, never on
    // that sentence's own text (constitution II), so its wording can change on either side
    // without this operator-facing copy drifting or breaking.
    is SessionAudioExportRefusal.NothingToExport -> "Nothing to export — no audio from this session is on the phone."
    SessionAudioExportRefusal.ArchiveCapturingNow ->
        "The continuous archive is still being written for this " +
            "session — export over audio alone, or wait for it to end."
}

@Composable
private fun LabelSheet(state: RecordingSessionLabelSheetViewState, actions: RecordingSessionActions) {
    Sheet(title = state.callsignLabel, modifier = Modifier.testTag(RECORDING_SESSION_LABEL_SHEET_TEST_TAG)) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Mark for training",
                style = OrtType.control,
                color = OrtColors.textHigh,
                modifier = Modifier.weight(1f),
            )
            TextAction(
                text = if (state.markedForTraining) "On" else "Off",
                onClick = { actions.onSetMarkedForTraining(!state.markedForTraining) },
                modifier = Modifier.testTag(RECORDING_SESSION_LABEL_TOGGLE_TEST_TAG),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(OrtSpacing.sm)) {
            TextAction(
                text = "Good",
                onClick = { actions.onSetRating("good") },
                modifier = Modifier.testTag(RECORDING_SESSION_LABEL_GOOD_TEST_TAG),
            )
            TextAction(
                text = "Bad",
                onClick = { actions.onSetRating("bad") },
                modifier = Modifier.testTag(RECORDING_SESSION_LABEL_BAD_TEST_TAG),
            )
        }
        TextAction(text = "Done", onClick = actions.onCloseLabelSheet, modifier = Modifier.padding(top = OrtSpacing.sm))
    }
}

@Composable
private fun RecordingSessionRefusalBody(message: String, onDismiss: () -> Unit) {
    Text(
        text = message,
        style = OrtType.cardBody,
        color = OrtColors.accentAmberText,
        modifier = Modifier.testTag(RECORDING_SESSION_REFUSAL_TEST_TAG),
    )
    TextAction(text = "Close", onClick = onDismiss, modifier = Modifier.padding(top = OrtSpacing.sm))
}

@Composable
private fun RecordingSessionSheetActions(confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Row(modifier = Modifier.padding(top = OrtSpacing.md), horizontalArrangement = Arrangement.spacedBy(OrtSpacing.sm)) {
        TextAction(text = "Cancel", onClick = onDismiss)
        TextAction(
            text = confirmLabel,
            onClick = onConfirm,
            modifier = Modifier.testTag(RECORDING_SESSION_SHEET_CONFIRM_TEST_TAG),
        )
    }
}

public const val RECORDING_SESSION_LOADING_TEST_TAG: String = "rc02-loading"
public const val RECORDING_SESSION_VAD_DETECTOR_TEST_TAG: String = "rc02-vad-detector"
public const val RECORDING_SESSION_OPEN_LOG_TEST_TAG: String = "rc02-open-log"
public const val RECORDING_SESSION_COVERAGE_TEST_TAG: String = "rc02-coverage"
public const val RECORDING_SESSION_PLAY_ALL_TEST_TAG: String = "rc02-play-all"
public const val RECORDING_SESSION_EXPORT_TEST_TAG: String = "rc02-export-tile"
public const val RECORDING_SESSION_LABEL_TILE_TEST_TAG: String = "rc02-label-tile"
public const val RECORDING_SESSION_DELETE_TEST_TAG: String = "rc02-delete-tile"
public const val RECORDING_SESSION_DELETE_SHEET_TEST_TAG: String = "rc02-delete-sheet"
public const val RECORDING_SESSION_DELETE_FREES_TEST_TAG: String = "rc02-delete-frees"
public const val RECORDING_SESSION_DELETE_OVER_AUDIO_TEST_TAG: String = "rc02-delete-over-audio"
public const val RECORDING_SESSION_DELETE_ARCHIVE_TEST_TAG: String = "rc02-delete-archive"
public const val RECORDING_SESSION_DELETE_DONE_TEST_TAG: String = "rc02-delete-done"
public const val RECORDING_SESSION_EXPORT_SHEET_TEST_TAG: String = "rc02-export-sheet"
public const val RECORDING_SESSION_EXPORT_PREVIEW_TEST_TAG: String = "rc02-export-preview"
public const val RECORDING_SESSION_EXPORT_WRITING_TEST_TAG: String = "rc02-export-writing"
public const val RECORDING_SESSION_EXPORT_DONE_TEST_TAG: String = "rc02-export-done"
public const val RECORDING_SESSION_LABEL_SHEET_TEST_TAG: String = "rc02-label-sheet"
public const val RECORDING_SESSION_LABEL_TOGGLE_TEST_TAG: String = "rc02-label-toggle"
public const val RECORDING_SESSION_LABEL_GOOD_TEST_TAG: String = "rc02-label-good"
public const val RECORDING_SESSION_LABEL_BAD_TEST_TAG: String = "rc02-label-bad"
public const val RECORDING_SESSION_REFUSAL_TEST_TAG: String = "rc02-refusal"
public const val RECORDING_SESSION_SHEET_CONFIRM_TEST_TAG: String = "rc02-sheet-confirm"
