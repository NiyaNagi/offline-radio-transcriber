package org.ort.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import org.ort.app.status.StatusViewState
import org.ort.app.ui.components.ActivityPatternChart
import org.ort.app.ui.components.EmptyState
import org.ort.app.ui.components.FailedState
import org.ort.app.ui.components.LiveBar
import org.ort.app.ui.components.LiveBarViewState
import org.ort.app.ui.components.PrimaryButton
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.data.EarlierNightRow
import org.ort.app.ui.data.NowStationRow
import org.ort.app.ui.data.NowSummaryViewState
import org.ort.app.ui.data.NowViewState
import org.ort.app.ui.data.NowViewStateMapper
import org.ort.app.ui.data.WorthKnowingItem
import org.ort.app.ui.data.WorthKnowingTone
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType
import org.ort.core.AttributionState

/**
 * The "Now" home (ui-conformance-plan WP4, R-030/R-033/R-036/R-037; `Main.dc.html`,
 * `Now-Idle.dc.html`, `Now-First.dc.html`). A pure function of [state] — polling and I/O live in
 * [org.ort.app.ui.screens.NowContent].
 *
 * The field dump this screen used to embed (`org.ort.app.ui.screens.StatusScreen`) moved to
 * [org.ort.app.ui.screens.CaptureStatusScreen] (R-033) — this screen shows the session, not the
 * capture machinery.
 */
@Composable
public fun NowScreen(
    state: NowViewState,
    modifier: Modifier = Modifier,
    liveBar: LiveBarViewState? = null,
    onOpenLive: () -> Unit = {},
    onStartCapture: () -> Unit = {},
    onOpenStation: (String) -> Unit = {},
    // Both default to a no-op so every existing caller (including the deprecated legacy overload
    // below) keeps compiling unchanged; `NowContent` wires the real navigation once the caller
    // (WP3's `OrtNavHost.kt`) reaches this destination for it.
    onOpenStations: () -> Unit = {},
    onOpenModels: () -> Unit = {},
) {
    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(OrtSpacing.lg),
        ) {
            when (state) {
                is NowViewState.Idle -> IdleContent(state = state, onStartCapture = onStartCapture)
                is NowViewState.Active -> ActiveContent(
                    state = state,
                    onOpenStation = onOpenStation,
                    onOpenStations = onOpenStations,
                    onOpenModels = onOpenModels,
                )
            }
        }
        if (liveBar != null && state is NowViewState.Active) {
            LiveBar(state = liveBar, onClick = onOpenLive, modifier = Modifier.testTag("now-livebar"))
        }
    }
}

/**
 * The **deprecated legacy overload** — kept only so
 * `app/src/main/kotlin/org/ort/app/ui/navigation/OrtNavHost.kt`'s inline `NowContent` (WP3's file,
 * not yet repointed at [org.ort.app.ui.screens.NowContent]/[NowViewState]) keeps compiling. See
 * [NowViewStateMapper.legacyFrom]'s own doc comment. Not the R-030/R-033/R-036/R-037
 * implementation — that is the [NowViewState] overload above; WP3's own row is what removes this.
 */
@Deprecated("Use NowScreen(state: NowViewState, ...) - kept only until WP3 repoints OrtNavHost.kt's NowContent")
@Composable
public fun NowScreen(status: StatusViewState, summary: NowSummaryViewState, modifier: Modifier = Modifier) {
    val isCapturing = status.stateLabel == "Capturing"
    NowScreen(
        state = NowViewStateMapper.legacyFrom(summary.overCount, summary.stationCount, isCapturing),
        modifier = modifier,
    )
}

// -------------------------------------------------------------------------------------------
// Now-Idle.dc.html
// -------------------------------------------------------------------------------------------

@Composable
private fun IdleContent(state: NowViewState.Idle, onStartCapture: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(modifier = Modifier.size(5.dp).background(OrtColors.markerUnknown, CircleShape))
        Text(
            text = "Not capturing",
            style = OrtType.screenTitle,
            color = OrtColors.textHigh,
            modifier = Modifier.testTag("now-idle-title"),
        )
    }
    state.lastSessionSummaryLabel?.let {
        Text(
            text = it,
            style = OrtType.subtitle,
            color = OrtColors.textDim,
            modifier = Modifier.padding(top = 3.dp).semantics { contentDescription = it },
        )
    }

    PrimaryButton(
        text = "Start capture",
        onClick = onStartCapture,
        modifier = Modifier.fillMaxWidth().padding(top = OrtSpacing.lg).testTag("now-idle-start-capture"),
    )

    val inputRigTier = listOfNotNull(state.inputLabel, state.rigLabel, state.tierLabel)
    if (inputRigTier.isNotEmpty()) {
        Text(
            text = inputRigTier.joinToString(" · "),
            style = OrtType.subLine,
            color = OrtColors.textDim,
            modifier = Modifier.padding(top = OrtSpacing.sm),
        )
    }

    SectionHeader(label = "Earlier nights", modifier = Modifier.padding(top = OrtSpacing.lg))
    if (state.earlierNights.isEmpty()) {
        EmptyState(message = "No earlier nights yet.")
    } else {
        state.earlierNights.forEach { row -> EarlierNightRowContent(row) }
    }

    state.canGetBetter?.let { row ->
        SectionHeader(label = "Can get better", modifier = Modifier.padding(top = OrtSpacing.lg))
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = OrtSpacing.sm).testTag("now-can-get-better"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(OrtColors.bgScreen),
            )
            Column(modifier = Modifier.weight(1f).padding(start = OrtSpacing.md)) {
                Text(text = row.headline, style = OrtType.control, color = OrtColors.textHigh)
                Text(text = row.subLine, style = OrtType.subLine, color = OrtColors.textDim)
            }
            Text(text = "Improve", style = OrtType.subtitle, color = OrtColors.accentGreen)
        }
    }
}

@Composable
private fun EarlierNightRowContent(row: EarlierNightRow) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = OrtSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = row.title, style = OrtType.rowTitle, color = OrtColors.textHigh)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = row.subLine, style = OrtType.subLine, color = OrtColors.textDim)
                row.gapsLabel?.let {
                    Text(
                        text = " · $it",
                        style = OrtType.subLine,
                        color = OrtColors.accentAmberText,
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------
// Main.dc.html / Now-First.dc.html
// -------------------------------------------------------------------------------------------

@Composable
private fun ActiveContent(
    state: NowViewState.Active,
    onOpenStation: (String) -> Unit,
    onOpenStations: () -> Unit,
    onOpenModels: () -> Unit,
) {
    Text(
        text = state.sessionTitle,
        style = OrtType.screenTitle,
        color = OrtColors.textHigh,
        modifier = Modifier.testTag("now-active-title"),
    )
    Text(
        text = state.summaryLabel,
        style = OrtType.subtitle,
        color = OrtColors.textDim,
        modifier = Modifier.padding(top = 3.dp).semantics { contentDescription = state.summaryLabel },
    )

    if (state.activityPattern.isNotEmpty()) {
        ActivityPatternChart(
            pattern = state.activityPattern,
            modifier = Modifier.padding(top = OrtSpacing.lg).testTag("now-activity-chart"),
            title = "ACTIVITY BY HOUR (UTC)",
            axisStart = state.axisStartLabel,
            axisEnd = state.axisEndLabel,
            notListeningLabel = state.notListeningLabel,
        )
    }

    state.missingModel?.let { missing ->
        FailedState(
            title = missing.title,
            body = missing.body,
            actionLabel = missing.actionLabel,
            onAction = onOpenModels,
            modifier = Modifier.padding(top = OrtSpacing.lg).testTag("now-missing-model"),
        )
    }

    SectionHeader(label = "Worth knowing", modifier = Modifier.padding(top = OrtSpacing.lg))
    if (state.worthKnowing.isEmpty()) {
        EmptyState(
            message = "Nothing yet.",
            subMessage = "The digest builds as overs arrive.",
        )
    } else {
        state.worthKnowing.forEach { item -> WorthKnowingRow(item) }
    }

    SectionHeader(
        label = "Stations heard",
        modifier = Modifier.padding(top = OrtSpacing.lg),
        trailingActionLabel = if (state.stations.totalCount > 0) "All ${state.stations.totalCount}" else null,
        onTrailingAction = onOpenStations,
    )
    if (state.stations.rows.isEmpty() && state.stations.unidentifiedLabel == null) {
        Text(
            text = state.stations.emptyMessage ?: "None yet.",
            style = OrtType.bodyProse,
            color = OrtColors.textMuted,
        )
    } else {
        state.stations.rows.forEach { row -> StationRowContent(row, onClick = { onOpenStation(row.stationId) }) }
        state.stations.unidentifiedLabel?.let { label ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = OrtSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(OrtSpacing.md),
            ) {
                Box(modifier = Modifier.size(5.dp).background(OrtColors.markerUnknown, CircleShape))
                Text(
                    text = label,
                    style = OrtType.subtitle.copy(fontStyle = FontStyle.Italic),
                    color = OrtColors.textLow,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun WorthKnowingRow(item: WorthKnowingItem) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = OrtSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(OrtSpacing.md),
    ) {
        WorthKnowingDot(tone = item.tone, modifier = Modifier.padding(top = 5.dp))
        Column {
            Text(text = item.headline, style = OrtType.bodyProse, color = OrtColors.textHigh)
            Text(
                text = item.subLine,
                style = OrtType.cardBody,
                color = OrtColors.textFaint,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

/** Reuses the CONFIRMED (solid)/AMBIGUOUS (half-ring) shape language `Main.dc.html` itself draws
 * for a digest item, drawn locally rather than through [org.ort.app.ui.components.AttributionMarker]
 * — a digest item is not an attribution and has no real confidence to satisfy that component's
 * `Attribution` factories (`NowViewState.WorthKnowingTone`'s own doc comment explains why). */
@Composable
private fun WorthKnowingDot(tone: WorthKnowingTone, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(9.dp)) {
        when (tone) {
            WorthKnowingTone.NOMINAL -> drawCircle(color = OrtColors.accentGreen)
            WorthKnowingTone.DEGRADED -> {
                drawCircle(color = OrtColors.accentAmber, style = Stroke(width = 1.5.dp.toPx()))
                clipRect(right = size.width / 2) {
                    drawCircle(color = OrtColors.accentAmber)
                }
            }
        }
    }
}

@Composable
private fun StationRowContent(row: NowStationRow, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = OrtSpacing.sm)
            .clip(RoundedCornerShape(6.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("now-station-${row.stationId}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OrtSpacing.md),
    ) {
        StationMarker(state = row.marker)
        Text(
            text = row.callsign,
            style = OrtType.callsignRow,
            color = if (row.marker == AttributionState.CONFIRMED) OrtColors.textHigh else OrtColors.textBody,
            modifier = Modifier.width(80.dp),
        )
        Text(
            text = row.countLabel,
            style = OrtType.transcript,
            color = OrtColors.textTime,
            modifier = Modifier.weight(1f),
        )
        Text(text = row.lastTimeLabel, style = OrtType.timeFreq, color = OrtColors.textFaint)
    }
}

@Composable
private fun StationMarker(state: AttributionState, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(9.dp)) {
        when (state) {
            AttributionState.CONFIRMED -> drawCircle(color = OrtColors.accentGreen)
            AttributionState.INFERRED ->
                drawCircle(color = OrtColors.accentGreen, style = Stroke(width = 1.5.dp.toPx()))
            AttributionState.AMBIGUOUS, AttributionState.UNKNOWN -> drawCircle(color = OrtColors.markerUnknown)
        }
    }
}
