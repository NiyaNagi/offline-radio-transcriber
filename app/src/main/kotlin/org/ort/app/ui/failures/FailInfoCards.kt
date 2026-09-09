package org.ort.app.ui.failures

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.DrillInHeader
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtType

/**
 * F14/F17 — `Fail-Clock.dc.html`/`Fail-Interrupted.dc.html`. Both boards use a green dot and
 * `bg/card`/`bg/selected`, not amber — they report something the app *handled correctly*
 * (a DST jump accounted for, an interrupted pass safely re-queued), never a degradation. Neither
 * has a runtime signal today — see [DebugFailureOverride]'s kdoc.
 *
 * [FailClockCard]/[FailInterruptedCard] below stay as the compact treatment for a future
 * Session-detail embedding (register R-147: "the Session-detail embedding stays a WP10
 * follow-up" — this package does not own `Detail*.kt`). [FailClockScreen]/[FailInterruptedScreen]
 * are the full-screen presentation R-147 asks for in the meantime, routed by [FailureHost]
 * exactly like F19–F22's own standalone screens.
 */

@Composable
public fun FailClockCard(state: ClockViewState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(OrtColors.bgCard, RoundedCornerShape(10.dp))
            .padding(13.dp)
            .testTag("failure-clock-card"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(modifier = Modifier.size(9.dp).background(OrtColors.accentGreen, CircleShape))
            Text(
                text = "Daylight saving ended during this session",
                style = OrtType.subtitle,
                color = OrtColors.textHigh,
            )
        }
        Text(
            text = "${state.offsetChangeLabel} happened during this session. Every duration here is from the " +
                "phone's monotonic clock (ran for ${state.ranForLabel}, ${state.startedLabel} to " +
                "${state.endedLabel}), so nothing is off by an hour, and the log is ordered by when things " +
                "actually happened, not by what the clock said.",
            style = OrtType.cardBody,
            color = OrtColors.textMuted,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
public fun FailInterruptedCard(state: InterruptedViewState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(OrtColors.bgSelected, RoundedCornerShape(8.dp))
            .padding(12.dp)
            .testTag("failure-interrupted-card"),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(modifier = Modifier.padding(top = 5.dp).size(9.dp).background(OrtColors.accentGreen, CircleShape))
        Column {
            Text(
                text = "${state.overCount} overs were mid-transcription when the app stopped",
                style = OrtType.control,
                color = OrtColors.textHigh,
            )
            Text(
                text = "Found in processing at launch with no result. Returned to captured and re-queued — " +
                    "their audio was safely on disk before any pass began (${state.gapLabel}). They run again " +
                    "from the start; a pass that ran twice produces the same record.",
                style = OrtType.cardBody,
                color = OrtColors.textSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** F14 full screen — register R-147. `Fail-Clock.dc.html`'s facts table plus its "Around the
 * change" log, from [ClockViewState.logRows]. Register R-448: the board's own "‹ Earlier nights"
 * back header — [onContinue] is the only dismiss this screen already has (F14 is one of the two
 * dismissable takeovers, `FailureHost.kt`'s own `isTakeoverShown`/`dismiss.onDismissClock`), so the
 * header's back chevron reuses it rather than inventing a second way to leave. */
@Composable
public fun FailClockScreen(state: ClockViewState, onContinue: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OrtColors.bgScreen)
            .failureScreenInset()
            .verticalScroll(rememberScrollState())
            .testTag("failure-clock-screen"),
    ) {
        DrillInHeader(
            parentLabel = "Earlier nights",
            onBack = onContinue,
            modifier = Modifier.testTag("failure-clock-back"),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            Box(modifier = Modifier.size(9.dp).background(OrtColors.accentGreen, CircleShape))
            Text(
                text = "Daylight saving ended during this session",
                style = OrtType.screenTitle,
                color = OrtColors.textHigh,
            )
        }
        if (state.nightLabel.isNotEmpty()) {
            Text(
                text = state.nightLabel,
                style = OrtType.subtitle,
                color = OrtColors.textDim,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        Text(
            text = "${state.offsetChangeLabel} happened during this session. Every duration here is from the " +
                "phone's monotonic clock, so nothing is off by an hour, and the log is ordered by when things " +
                "actually happened, not by what the clock said.",
            style = OrtType.cardBody,
            color = OrtColors.textMuted,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
        SectionLabel("Facts", modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
        val windowValue = state.windowLabel.ifEmpty { "${state.startedLabel} – ${state.endedLabel}" }
        InfoRow(key = "Window", value = windowValue, modifier = Modifier.padding(horizontal = 20.dp))
        InfoRow(key = "Ran for", value = state.ranForLabel, modifier = Modifier.padding(horizontal = 20.dp))
        InfoRow(key = "Change", value = state.offsetChangeLabel, modifier = Modifier.padding(horizontal = 20.dp))
        if (state.logRows.isNotEmpty()) {
            SectionLabel("Around the change", modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            state.logRows.forEach { row -> ClockLogRowView(row) }
        }
        TextAction(
            text = "OK",
            onClick = onContinue,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp).testTag("failure-clock-continue"),
        )
    }
}

@Composable
private fun ClockLogRowView(row: ClockLogRow, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column {
            Text(text = row.timeLabel, style = OrtType.control, color = OrtColors.textHigh)
            Text(text = row.offsetLabel, style = OrtType.subLine, color = OrtColors.textFaint)
        }
        Column {
            row.callsign?.let { Text(text = it, style = OrtType.control, color = OrtColors.textHigh) }
            Text(text = row.text, style = OrtType.subLine, color = OrtColors.textDim)
        }
    }
}

/** F17 full screen — register R-147. `Fail-Interrupted.dc.html`'s facts plus its "The N overs"
 * list, from [InterruptedViewState.overs]. */
@Composable
public fun FailInterruptedScreen(state: InterruptedViewState, onContinue: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OrtColors.bgScreen)
            .failureScreenInset()
            .verticalScroll(rememberScrollState())
            .testTag("failure-interrupted-screen"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            Box(modifier = Modifier.size(9.dp).background(OrtColors.accentGreen, CircleShape))
            Text(
                text = "${state.overCount} overs were mid-transcription when the app stopped",
                style = OrtType.screenTitle,
                color = OrtColors.textHigh,
            )
        }
        Text(
            text = "Found in processing at launch with no result. Returned to captured and re-queued — their " +
                "audio was safely on disk before any pass began. They run again from the start; a pass that " +
                "ran twice produces the same record.",
            style = OrtType.cardBody,
            color = OrtColors.textMuted,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
        SectionLabel("Facts", modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
        InfoRow(key = "Gap", value = state.gapLabel, modifier = Modifier.padding(horizontal = 20.dp))
        if (state.backlogLabel.isNotEmpty()) {
            InfoRow(key = "Backlog", value = state.backlogLabel, modifier = Modifier.padding(horizontal = 20.dp))
        }
        if (state.overs.isNotEmpty()) {
            SectionLabel(
                "The ${state.overs.size} overs",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            state.overs.forEach { over -> InterruptedOverRowView(over) }
        }
        TextAction(
            text = "OK",
            onClick = onContinue,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp).testTag("failure-interrupted-continue"),
        )
    }
}

@Composable
private fun InterruptedOverRowView(over: InterruptedOverRow, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column {
            Text(text = over.timeLabel, style = OrtType.control, color = OrtColors.textHigh)
            Text(text = over.statusLabel, style = OrtType.subLine, color = OrtColors.textFaint)
        }
        Text(text = over.text, style = OrtType.subLine, color = OrtColors.textDim)
    }
}
