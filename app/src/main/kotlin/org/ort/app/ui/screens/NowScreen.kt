package org.ort.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.style.TextAlign
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

    // R-461: `Now-Idle.dc.html`'s own meta row — centred, "USB Audio Device"/"TH-D75A" each with
    // their own leading dot, "tier 3" without one. Always rendered (never conditionally dropped):
    // an unconfigured input/radio reads its own honest "No input"/"No radio" placeholder — with no
    // dot, since a dot claims a real, present device — rather than the segment silently vanishing.
    NowIdleMetaRow(
        inputLabel = state.inputLabel,
        rigLabel = state.rigLabel,
        tierLabel = state.tierLabel ?: "tier —",
        modifier = Modifier.padding(top = OrtSpacing.sm),
    )

    // R-416: `Now-Idle.dc.html`'s own divider ahead of EARLIER NIGHTS — unconditional, the same
    // hairline `Drawer.kt`'s own Divider draws (OrtColors.lineDefault), independent of whether the
    // meta row above it has anything to show.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = OrtSpacing.lg)
            .height(1.dp)
            .background(OrtColors.lineDefault)
            .testTag("now-idle-earlier-nights-divider"),
    )
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

/** R-461 (`Now-Idle.dc.html`'s own meta row beneath "Start capture"): centred, a leading dot on
 * each of [inputLabel]/[rigLabel] when they are real (never on the honest "No input"/"No radio"
 * fallback — a dot claims a real, present device), no dot on [tierLabel]. */
@Composable
private fun NowIdleMetaRow(inputLabel: String?, rigLabel: String?, tierLabel: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NowIdleMetaItem(label = inputLabel ?: "No input", real = inputLabel != null)
        Text(text = "·", style = OrtType.subLine, color = OrtColors.textFaint)
        NowIdleMetaItem(label = rigLabel ?: "No radio", real = rigLabel != null)
        Text(text = "·", style = OrtType.subLine, color = OrtColors.textFaint)
        Text(text = tierLabel, style = OrtType.subLine, color = OrtColors.textDim)
    }
}

@Composable
private fun NowIdleMetaItem(label: String, real: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (real) {
            Box(modifier = Modifier.size(6.dp).background(OrtColors.accentGreen, CircleShape))
        }
        Text(text = label, style = OrtType.subLine, color = OrtColors.textDim)
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
            EarlierNightMetaLine(subLine = row.subLine, gapsLabel = row.gapsLabel)
        }
    }
}

/**
 * R-260 (`overnight/N01-now@2x.png`): at font scale 2.0 a plain `Row` gave the trailing " · 1 gap"
 * whatever sliver of width was left over after [subLine] claimed the line, so it wrapped one
 * character per line rather than as a whole token — the same collapsed-column defect
 * [org.ort.app.ui.components.LogRowMarkerLine] (R-244) already fixed for a badge, by the same fix:
 * a `FlowRow` (never a plain `Row`, which cannot wrap at all) lets [gapsLabel] drop to its own line
 * as one unit whenever it does not fit next to [subLine], instead of being squeezed into whatever
 * width remains on the current one. `softWrap = false` on the gap token itself is belt-and-braces —
 * even if a future layout change ever handed it a narrower slot than a `FlowRow` item should get, it
 * would clip as a whole token rather than stack into single characters, since a truncated count is
 * still readable and a vertical stack of one character per line is not.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EarlierNightMetaLine(subLine: String, gapsLabel: String?) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(0.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = subLine, style = OrtType.subLine, color = OrtColors.textDim)
        gapsLabel?.let {
            Text(
                text = " · $it",
                style = OrtType.subLine,
                color = OrtColors.accentAmberText,
                softWrap = false,
                modifier = Modifier.testTag("now-earlier-night-gap"),
            )
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

    if (state.overCount == 0) {
        // R-174: a session seconds old genuinely has not listened through the other ~23 hours of
        // the day — ActivityPatternChart would honestly draw them as the full-width NOT_LISTENING
        // hatch (FR-UI-12), which is accurate but is exactly the alarming full-hatch chart
        // `Now-First.dc.html` deliberately avoids this early. This is not the same chart with
        // different data; it is a distinct, near-empty baseline that commits to nothing about
        // hours not yet lived through.
        FirstSessionChartBaseline(
            axisStart = state.axisStartLabel,
            axisEnd = state.axisEndLabel,
            modifier = Modifier.padding(top = OrtSpacing.lg).testTag("now-first-session-chart"),
        )
    } else if (state.activityPattern.isNotEmpty()) {
        // R-415: `Main.dc.html`'s populated hour chart has no title at all (a regression of
        // R-021/R-075, which already established this) — passing one here put "ACTIVITY BY HOUR
        // (UTC)" back above the chart. `title = null` is `ActivityPatternChart`'s own honest
        // no-title default; not passed at all, so a future default change is never silently undone
        // here again.
        ActivityPatternChart(
            pattern = state.activityPattern,
            modifier = Modifier.padding(top = OrtSpacing.lg).testTag("now-activity-chart"),
            title = null,
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

    StationsHeardSection(state = state, onOpenStation = onOpenStation, onOpenStations = onOpenStations)
}

/** `Main.dc.html`/`Now-First.dc.html`'s "Stations heard" section — split out of [ActiveContent]
 * itself (detekt's `LongMethod`, the same split this package's row family already reaches for
 * rather than suppressing). R-417 (`Now-First.dc.html`): the empty-state second line "Listening
 * since 23:32." — the session's own real start time (`state.axisStartLabel`, the same fact the
 * hour chart's own left axis label uses), never fabricated when it is genuinely unknown. */
@Composable
private fun StationsHeardSection(
    state: NowViewState.Active,
    onOpenStation: (String) -> Unit,
    onOpenStations: () -> Unit,
) {
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
        state.axisStartLabel?.let { started ->
            Text(
                text = "Listening since $started.",
                style = OrtType.subLine,
                color = OrtColors.textFaint,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
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

/**
 * `Now-First.dc.html`'s flat chart baseline (R-174): a near-flat bar strip (the first bar carries a
 * faint 6%-height mark — this session's own first, still-forming hour — every other bar is bottom
 * ruled only, committing to nothing) with "the chart fills as the night goes on" centered between
 * the axis labels, replacing [ActivityPatternChart] for the one state where that chart's honest
 * per-hour hatch would misread as alarm rather than freshness. See [NowViewState.Active]'s own doc
 * comment for why [ActivityPatternChart] itself is not reused, only called, for this case.
 */
@Composable
private fun FirstSessionChartBaseline(axisStart: String?, axisEnd: String?, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().height(38.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(1.5.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(0.06f)
                    .background(OrtColors.textFaint.copy(alpha = 0.35f)),
            )
            repeat(FIRST_SESSION_BASELINE_BAR_COUNT - 1) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(1.dp)
                        .align(Alignment.Bottom)
                        .background(OrtColors.textFaint.copy(alpha = 0.25f)),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = OrtSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            axisStart?.let { Text(text = it, style = OrtType.timeFreq, color = OrtColors.textFaint) }
            Text(
                text = "the chart fills as the night goes on",
                style = OrtType.subLine,
                color = OrtColors.textFaint,
                modifier = Modifier.weight(1f).padding(horizontal = OrtSpacing.sm),
                textAlign = TextAlign.Center,
            )
            axisEnd?.let { Text(text = it, style = OrtType.timeFreq, color = OrtColors.textFaint) }
        }
    }
}

private const val FIRST_SESSION_BASELINE_BAR_COUNT = 16

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
