package org.ort.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.AttributionRow
import org.ort.app.ui.components.InProgressRing
import org.ort.app.ui.components.OrtIcons
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.components.rememberTimeColumnWidth
import org.ort.app.ui.data.CaptureStateTone
import org.ort.app.ui.data.CaptureStatusViewState
import org.ort.app.ui.data.LevelViewState
import org.ort.app.ui.data.LiveMonitorOverRow
import org.ort.app.ui.data.LiveMonitorOversViewState
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/**
 * `Live-Monitor.dc.html` (design-intent N07, R-1007) — the live instrument the operator asked for
 * after running thirty minutes of capture with no way to tell what was happening: elapsed and
 * level in flight, the Pass A partial as it arrives, and this session's overs newest-first, each
 * carrying the state it is actually in (constitution I — never render one as finished when it is
 * not, never omit one because its state is awkward).
 *
 * Reached two ways, both landing here (this package's own report names the seam each wires
 * through): [org.ort.app.ui.screens.CaptureStatusContent]'s own embedded live bar (`onOpenLive`,
 * previously the unwired no-op this row's whole reason for existing), and the pinned bar shown on
 * every *other* destination (`OrtNavHost.kt`'s host-level copy — the route this package's own row
 * added). Deliberately renders **no [org.ort.app.ui.components.LiveBar] of its own**: this screen
 * *is* the live surface (the state row's dot and the level card are both continuously live), and
 * the artboard's own footer is the dedicated "room / Full log" row below, never a second copy of
 * the bar stacked under it — see this package's report on why that would have been a third
 * `embedsOwnLiveBar` case in `OrtNavHost.kt` this round deliberately does not add.
 *
 * [status]/[level] are the exact facts [CaptureStatusScreen] itself renders
 * ([org.ort.app.ui.data.CaptureStatusViewState], [org.ort.app.ui.data.LevelViewState]) — reused
 * directly rather than re-derived, so the state title, the elapsed/heartbeat sentence and the level
 * numbers can never disagree between the two screens. [hearingText] is
 * [org.ort.app.ui.data.LiveBarPolling.newestPassAPartial]'s own real read, the identical fact the
 * pinned bar's own partial text already is — this screen's own report states the one artboard
 * number this cannot honestly show (the "Hearing now" card's own elapsed-seconds figure) and why.
 */
@Composable
public fun LiveMonitorScreen(
    status: CaptureStatusViewState,
    level: LevelViewState,
    hearingText: String?,
    overs: LiveMonitorOversViewState,
    localMicrophone: Boolean,
    modifier: Modifier = Modifier,
    actions: LiveMonitorActions = LiveMonitorActions(),
) {
    var confirmingStop by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        LiveMonitorTopBar(
            haltActionLabel = status.haltActionLabel,
            onBack = actions.onBack,
            onStopRequested = { confirmingStop = true },
            modifier = Modifier.testTag("live-monitor-top-bar"),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = OrtSpacing.lg),
        ) {
            LiveMonitorStateRow(status = status, modifier = Modifier.padding(top = OrtSpacing.sm))
            LiveMonitorLevelCard(level = level, modifier = Modifier.padding(top = OrtSpacing.lg))
            if (hearingText != null) {
                LiveMonitorHearingCard(text = hearingText, modifier = Modifier.padding(top = OrtSpacing.lg))
            }
            LiveMonitorOversSection(
                overs = overs,
                onOpenOver = actions.onOpenOver,
                modifier = Modifier.padding(top = OrtSpacing.lg),
            )
            Spacer(modifier = Modifier.height(OrtSpacing.lg))
        }
        LiveMonitorFooter(
            localMicrophone = localMicrophone,
            onOpenFullLog = actions.onOpenFullLog,
            modifier = Modifier.testTag("live-monitor-footer"),
        )
    }

    if (confirmingStop) {
        StopConfirmDialog(
            title = status.haltConfirmTitle,
            body = status.haltConfirmBody,
            onConfirm = {
                confirmingStop = false
                actions.onStop()
            },
            onDismiss = { confirmingStop = false },
        )
    }
}

@Composable
private fun LiveMonitorTopBar(
    haltActionLabel: String?,
    onBack: () -> Unit,
    onStopRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OrtSpacing.md),
    ) {
        Icon(
            imageVector = OrtIcons.back,
            contentDescription = "Back to Capture",
            tint = OrtColors.textIcon,
            modifier = Modifier
                .size(21.dp)
                .clickable(role = Role.Button, onClickLabel = "Back to Capture", onClick = onBack)
                .testTag("live-monitor-back"),
        )
        Text(text = "Live", style = OrtType.bodyProse, color = OrtColors.textIcon)
        Spacer(modifier = Modifier.weight(1f))
        if (haltActionLabel != null) {
            TextAction(
                text = haltActionLabel,
                onClick = onStopRequested,
                modifier = Modifier.testTag("live-monitor-stop"),
            )
        }
    }
}

@Composable
private fun LiveMonitorStateRow(status: CaptureStatusViewState, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(modifier = Modifier.size(9.dp).background(stateDotColor(status.stateTone), CircleShape))
            Text(
                text = status.stateLabel,
                style = OrtType.screenTitle,
                color = OrtColors.textHigh,
                modifier = Modifier.testTag("live-monitor-title"),
            )
        }
        Text(
            text = status.sinceElapsedLabel,
            style = OrtType.subtitle,
            color = OrtColors.textDim,
            modifier = Modifier.padding(top = 3.dp).testTag("live-monitor-since"),
        )
    }
}

private fun stateDotColor(tone: CaptureStateTone) = when (tone) {
    CaptureStateTone.NOMINAL -> OrtColors.accentGreen
    CaptureStateTone.DEGRADED -> OrtColors.accentAmber
    CaptureStateTone.HALTED -> OrtColors.haltFill
    CaptureStateTone.IDLE -> OrtColors.markerUnknown
}

/**
 * A condensed version of [LevelMeterScreen]'s own `LevelHistoryChart` (guide §6.6's envelope, at
 * this screen's own smaller card height rather than that screen's full 150dp meter) — deliberately
 * not shared: that composable is `private` to a file outside this package's row, and this card
 * drops the axis labels that chart carries, which its own doc comment ties directly to its own
 * `BoxWithConstraints`'s taller geometry. Same underlying facts either way
 * ([LevelViewState.historyDbfs], the fixed target band, the clip line at 0 dBFS) — never a
 * fabricated waveform.
 */
@Composable
private fun LiveMonitorLevelCard(level: LevelViewState, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        if (level.notMeasuredReason != null) {
            Text(
                text = "No level signal yet this session.",
                style = OrtType.subtitle,
                color = OrtColors.textDim,
                modifier = Modifier.testTag("live-monitor-level-not-measured"),
            )
            return@Column
        }
        LevelEnvelopeChart(history = level.historyDbfs)
        LevelCardCaption(level = level, modifier = Modifier.padding(top = 6.dp))
    }
}

/** [LiveMonitorLevelCard]'s own Canvas — split out purely to keep that composable under detekt's
 * `LongMethod` limit. Never a fabricated waveform: every bar is one of [history]'s own real
 * entries, the target band and clip line are the artboard's fixed reference marks, not a reading. */
@Composable
private fun LevelEnvelopeChart(history: List<Float>, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(58.dp)
            .background(OrtColors.bgAudio, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp)
            .testTag("live-monitor-level-chart"),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val ceiling = LevelViewState.CHART_CEILING_DBFS
            val floor = LevelViewState.CHART_FLOOR_DBFS
            val range = ceiling - floor
            fun yFor(dbfs: Float): Float = size.height * ((ceiling - dbfs) / range).coerceIn(0f, 1f)

            val bandTopY = yFor(LevelViewState.TARGET_BAND_TOP_DBFS)
            val bandBottomY = yFor(LevelViewState.TARGET_BAND_BOTTOM_DBFS)
            drawRect(
                color = OrtColors.accentGreen.copy(alpha = 0.10f),
                topLeft = Offset(0f, bandTopY),
                size = Size(size.width, bandBottomY - bandTopY),
            )
            val clipY = yFor(ceiling)
            drawLine(
                color = OrtColors.haltText.copy(alpha = 0.55f),
                start = Offset(0f, clipY),
                end = Offset(size.width, clipY),
                strokeWidth = 1.dp.toPx(),
            )

            if (history.isNotEmpty()) {
                val gapPx = 1.dp.toPx()
                val barWidth = (size.width - gapPx * (history.size - 1)) / history.size
                history.forEachIndexed { index, dbfs ->
                    val x = index * (barWidth + gapPx)
                    val barTopY = yFor(dbfs)
                    val color = when {
                        dbfs >= LevelViewState.TARGET_BAND_TOP_DBFS -> OrtColors.accentGreen
                        dbfs >= LevelViewState.TARGET_BAND_BOTTOM_DBFS -> OrtColors.chartGreenRamp[1]
                        else -> OrtColors.meterWarn
                    }
                    drawRect(color = color, topLeft = Offset(x, barTopY), size = Size(barWidth, size.height - barTopY))
                }
            }
        }
    }
}

/** [LiveMonitorLevelCard]'s own dBFS/band-state row — split out for the same reason
 * [LevelEnvelopeChart] is. */
@Composable
private fun LevelCardCaption(level: LevelViewState, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        val dbfsLine = listOfNotNull(
            level.peakDbfsLabel,
            level.noiseFloorDbfsLabel?.let { "floor $it" },
            level.headroomLabel?.let { "$it headroom" },
        ).joinToString(" · ")
        Text(
            text = dbfsLine,
            style = OrtType.axis,
            color = OrtColors.textDim,
            modifier = Modifier.weight(1f, fill = false).testTag("live-monitor-level-dbfs"),
        )
        level.bandStateSentence?.let { sentence ->
            Text(
                text = bandStateShortLabel(sentence),
                style = OrtType.axis,
                color = bandStateColor(level.bandStateTone),
            )
        }
    }
}

/** The chart's own short trailing label — the full sentence [LevelViewState.bandStateSentence]
 * carries is the drill-in's own space to spell it out; this card's own row has room only for the
 * artboard's short form ("in the band"/"above the band"/"below the band"/"clipping"). Derived from
 * [LevelViewState.bandStateTone] plus whether the sentence names "clipping" specifically — never a
 * second, independently-computed band judgement (constitution: one fact, read once). */
private fun bandStateShortLabel(sentence: String): String = when {
    sentence.startsWith("Clipping") -> "clipping"
    sentence.startsWith("Above") -> "above the band"
    sentence.startsWith("Below") -> "below the band"
    else -> "in the band"
}

private fun bandStateColor(tone: CaptureStateTone?) = when (tone) {
    CaptureStateTone.NOMINAL -> OrtColors.accentGreen
    CaptureStateTone.DEGRADED -> OrtColors.accentAmber
    CaptureStateTone.HALTED -> OrtColors.haltText
    CaptureStateTone.IDLE, null -> OrtColors.textDim
}

@Composable
private fun LiveMonitorHearingCard(text: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        SectionHeader(label = "Hearing now")
        Row(
            modifier = Modifier
                .padding(top = 7.dp)
                .fillMaxWidth()
                .background(OrtColors.bgRaised, RoundedCornerShape(8.dp))
                .testTag("live-monitor-hearing"),
        ) {
            // guide §6.16-adjacent left rule (`Live-Monitor.dc.html`'s `border-left: 2px solid
            // accent/green`) — a 2dp coloured bar rather than `Modifier.border`, which draws on
            // all four edges of a `RoundedCornerShape` and would clip the rounded corners.
            Box(modifier = Modifier.width(2.dp).fillMaxHeight().background(OrtColors.accentGreen))
            Column(modifier = Modifier.weight(1f).padding(start = 10.dp, top = 11.dp, end = 12.dp, bottom = 11.dp)) {
                Text(
                    text = text,
                    style = OrtType.control.copy(fontStyle = FontStyle.Italic),
                    color = OrtColors.textPrior,
                )
                Text(
                    text = "Pass A partial · not attributed — partials never are",
                    style = OrtType.subLine,
                    color = OrtColors.textFaint,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
        }
    }
}

@Composable
private fun LiveMonitorOversSection(
    overs: LiveMonitorOversViewState,
    onOpenOver: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "LOGGED TONIGHT",
                style = OrtType.sectionLabel,
                color = OrtColors.textFaint,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = overs.summaryLabel,
                style = OrtType.axis,
                color = OrtColors.textFaint,
                modifier = Modifier.testTag("live-monitor-summary"),
            )
        }
        overs.overs.forEachIndexed { index, row ->
            if (index > 0) Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(OrtColors.lineRow))
            LiveMonitorRow(
                row = row,
                onClick = { onOpenOver(row.id) },
                modifier = Modifier.testTag("live-monitor-row-${row.id}"),
            )
        }
    }
}

/** [LiveMonitorRow]'s own interaction/semantics modifier, split out purely to keep that composable
 * under detekt's `LongMethod` limit — the same reason [RejectedRow]'s own
 * `rejectedRowInteractionModifier` exists (`ui/components/Rows.kt`). A [LiveMonitorOverRow
 * .ListenedSilence] row is not an over at all (nothing to open — [org.ort.app.ui.data
 * .LiveMonitorOverRow]'s own doc comment), so it gets a plain, non-clickable, merged-description
 * node instead of a real `Role.Button`. */
private fun liveMonitorRowInteractionModifier(row: LiveMonitorOverRow, onClick: () -> Unit): Modifier =
    if (row is LiveMonitorOverRow.ListenedSilence) {
        Modifier.semantics(mergeDescendants = true) { contentDescription = liveMonitorRowDescription(row) }
    } else {
        Modifier.clickable(role = Role.Button, onClick = onClick).clearAndSetSemantics {
            val description = liveMonitorRowDescription(row)
            contentDescription = description
            text = AnnotatedString(description)
            role = Role.Button
            onClick(label = null) {
                onClick()
                true
            }
        }
    }

@Composable
private fun LiveMonitorRow(row: LiveMonitorOverRow, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .then(liveMonitorRowInteractionModifier(row, onClick))
            .padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Text(
            text = row.timeLabel,
            style = OrtType.timeFreq,
            color = OrtColors.textDim,
            maxLines = 1,
            softWrap = false,
            // R-1023: this used a fixed `width(62.dp)`, which the artboard also specified and has
            // since dropped (`Live-Monitor.dc.html`'s own `.when` comment) — a fixed width cannot
            // survive font scaling, so at 2.0 every row's timestamp clipped mid-character
            // ("13:4(", "13:39"). Third instance of the family this session after R-880 and R-1017.
            // `rememberTimeColumnWidth()` is the shared fix already applied to the identical
            // `HH:MM:SS` column in `LogRow`/`GapRow`/`RejectedRow` (`ui/components/Rows.kt`, R-205):
            // a `widthIn(min = …)` floor measured against this host's real font metrics, not a
            // guessed constant — the column sizes to its own content and never shrinks below it.
            modifier = Modifier.widthIn(min = rememberTimeColumnWidth()).testTag("live-monitor-row-time"),
        )
        Column(modifier = Modifier.weight(1f)) {
            when (row) {
                is LiveMonitorOverRow.Transcribing -> TranscribingRowBody(row)
                is LiveMonitorOverRow.Waiting -> WaitingRowBody(row)
                is LiveMonitorOverRow.Resolved -> ResolvedRowBody(row)
                is LiveMonitorOverRow.NotTranscribed -> NotTranscribedRowBody(row)
                is LiveMonitorOverRow.ListenedSilence -> ListenedSilenceRowBody(row)
            }
        }
    }
}

@Composable
private fun TranscribingRowBody(row: LiveMonitorOverRow.Transcribing) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        InProgressRing(size = 13.dp, color = OrtColors.accentGreen, strokeWidth = 1.8.dp)
        Text(text = "Transcribing", style = OrtType.control, color = OrtColors.textSecondary)
    }
    val tier = row.tierLabel ?: "tier not measured"
    Text(
        text = "${row.durationLabel} · ${row.passLabel} · $tier",
        style = OrtType.subLine,
        color = OrtColors.textFaint,
        modifier = Modifier.padding(top = 3.dp),
    )
}

@Composable
private fun WaitingRowBody(row: LiveMonitorOverRow.Waiting) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(modifier = Modifier.size(13.dp).border(width = 1.5.dp, color = OrtColors.lineHandle, shape = CircleShape))
        Text(text = "Waiting for Pass B", style = OrtType.control, color = OrtColors.textDim)
    }
    val ahead = if (row.aheadCount == 1) "1 ahead of it" else "${row.aheadCount} ahead of it"
    Text(
        text = "${row.durationLabel} · $ahead",
        style = OrtType.subLine,
        color = OrtColors.textFaint,
        modifier = Modifier.padding(top = 3.dp),
    )
}

@Composable
private fun ResolvedRowBody(row: LiveMonitorOverRow.Resolved) {
    AttributionRow(attribution = row.attribution, callsign = row.callsign)
    Text(
        text = row.transcript,
        style = OrtType.transcript,
        color = OrtColors.textHigh,
        modifier = Modifier.padding(top = 3.dp),
    )
    val caption = listOfNotNull(
        row.durationLabel,
        row.frequencyLabel,
        row.attributionStateLabel,
        row.inferredFromLabel?.let { "inferred from $it" },
    ).joinToString(" · ")
    Text(text = caption, style = OrtType.subLine, color = OrtColors.textDim, modifier = Modifier.padding(top = 3.dp))
}

@Composable
private fun NotTranscribedRowBody(row: LiveMonitorOverRow.NotTranscribed) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(
            text = "not transcribed",
            style = OrtType.control.copy(fontStyle = FontStyle.Italic),
            color = OrtColors.textSecondary,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(
            imageVector = OrtIcons.gapWarn,
            contentDescription = null,
            tint = OrtColors.accentAmberText,
            modifier = Modifier.size(15.dp),
        )
    }
    val attempts = row.attemptsLabel ?: "Pass B errored"
    Text(
        text = "${row.durationLabel} · $attempts · the audio is kept",
        style = OrtType.subLine,
        color = OrtColors.textDim,
        modifier = Modifier.padding(top = 3.dp),
    )
}

@Composable
private fun ListenedSilenceRowBody(row: LiveMonitorOverRow.ListenedSilence) {
    Text(text = "Nothing heard for ${row.durationLabel}", style = OrtType.control, color = OrtColors.textDim)
    Text(
        text = "listening the whole time — squelch never opened",
        style = OrtType.subLine,
        color = OrtColors.textFaint,
        modifier = Modifier.padding(top = 3.dp),
    )
}

private fun liveMonitorRowDescription(row: LiveMonitorOverRow): String = when (row) {
    is LiveMonitorOverRow.Transcribing -> "${row.timeLabel}, transcribing, ${row.durationLabel}, ${row.passLabel}"
    is LiveMonitorOverRow.Waiting -> "${row.timeLabel}, waiting for Pass B, ${row.aheadCount} ahead of it"
    is LiveMonitorOverRow.Resolved -> listOfNotNull(
        row.timeLabel,
        row.attributionStateLabel,
        row.callsign,
        row.transcript,
        row.inferredFromLabel?.let { "inferred from $it" },
    ).joinToString(", ")
    is LiveMonitorOverRow.NotTranscribed ->
        "${row.timeLabel}, not transcribed, ${row.attemptsLabel ?: "Pass B errored"}, the audio is kept"
    is LiveMonitorOverRow.ListenedSilence ->
        "${row.timeLabel}, nothing heard for ${row.durationLabel}, listening the whole time"
}

@Composable
private fun LiveMonitorFooter(localMicrophone: Boolean, onOpenFullLog: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(OrtColors.bgLive)
            .padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        if (localMicrophone) {
            Icon(
                imageVector = OrtIcons.builtInMic,
                contentDescription = null,
                tint = OrtColors.textDim,
                modifier = Modifier.size(15.dp),
            )
            Text(text = "room", style = OrtType.textAction, color = OrtColors.textDim, modifier = Modifier.weight(1f))
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }
        TextAction(text = "Full log", onClick = onOpenFullLog, modifier = Modifier.testTag("live-monitor-full-log"))
    }
}
