package org.ort.app.ui.failures

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtType

/**
 * F14/F17 — `Fail-Clock.dc.html`/`Fail-Interrupted.dc.html`. Both boards use a green dot and
 * `bg/card`/`bg/selected`, not amber — they report something the app *handled correctly*
 * (a DST jump accounted for, an interrupted pass safely re-queued), never a degradation. Neither
 * has a runtime signal today — see [DebugFailureOverride]'s kdoc.
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
