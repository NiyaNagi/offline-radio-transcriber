package org.ort.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.AttributionRow
import org.ort.app.ui.components.Badge
import org.ort.app.ui.components.BadgeKind
import org.ort.app.ui.components.EmptyState
import org.ort.app.ui.components.OrtIcons
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.data.FrequencyMeanwhileEntry
import org.ort.app.ui.data.ThreadCardViewState
import org.ort.app.ui.data.ThreadListViewState
import org.ort.app.ui.data.pluralize
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType
import org.ort.core.Attribution

/**
 * The "Threads" destination (R-044, ui-conformance WP5; `design/canvas/Threads.dc.html`,
 * `Threads-Ungrouped.dc.html`, FR-UI-2). [state] is one of the three honest states
 * [ThreadListViewState] models — nothing captured, everything individually reachable because
 * grouping is not built yet (the real state today, since nothing populates `threadId` before M6),
 * or real conversation cards. Kind (QSO/Net/Activation/Activity) renders only when the data
 * supports it (guide §9's "never fabricate") — no card here ever shows a guessed kind, since no
 * `:data` column carries one yet.
 */
@Composable
public fun ThreadScreen(
    state: ThreadListViewState,
    onOpenThread: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenFrequency: (Long) -> Unit = {},
    onLearnMoreAboutTier: () -> Unit = {},
    onImproveAtHome: () -> Unit = {},
) {
    Column(modifier = modifier.fillMaxSize()) {
        when (state) {
            ThreadListViewState.Empty ->
                EmptyState(
                    message = "No overs yet.",
                    subMessage = "Threads groups transmissions into conversations once there is something to group.",
                    modifier = Modifier.fillMaxWidth().padding(OrtSpacing.lg),
                )

            is ThreadListViewState.Ungrouped -> UngroupedThreads(
                state = state,
                onOpenFrequency = onOpenFrequency,
                onLearnMoreAboutTier = onLearnMoreAboutTier,
                onImproveAtHome = onImproveAtHome,
            )

            is ThreadListViewState.Grouped -> GroupedThreads(state = state, onOpenThread = onOpenThread)
        }
    }
}

@Composable
private fun GroupedThreads(state: ThreadListViewState.Grouped, onOpenThread: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm)) {
        Text(text = "Threads", style = OrtType.screenTitle)
        Text(
            text = state.summary,
            style = OrtType.subtitle,
            color = OrtColors.textDim,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(state.cards, key = { it.threadId }) { card ->
            ThreadCard(card = card, onClick = { onOpenThread(card.threadId) })
        }
    }
}

@Composable
private fun ThreadCard(card: ThreadCardViewState, onClick: () -> Unit) {
    val description = buildString {
        card.kindLabel?.let { append("$it. ") }
        append("${card.frequencyLabel}, ${card.overCount} overs. ")
        append("${card.titleText}. ${card.metaText}")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (card.ambiguous) OrtColors.bgRowAmbiguous else Color.Transparent)
            .heightIn(min = 44.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.md)
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(OrtSpacing.md),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            val kindPrefix = card.kindLabel?.let { "$it · " }.orEmpty()
            Text(
                text = "$kindPrefix${card.frequencyLabel} · ${card.overCount} overs".uppercase(),
                style = OrtType.columnHeader,
                color = OrtColors.textLow,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = card.titleText,
                    style = OrtType.rowTitle,
                    color = OrtColors.textHigh,
                    modifier = Modifier.padding(top = 3.dp),
                )
                if (card.isNew) Badge(text = "new", kind = BadgeKind.NEW)
            }
            Text(
                text = card.metaText,
                style = OrtType.chip,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Icon(imageVector = OrtIcons.chevron, contentDescription = null, tint = OrtColors.textLow)
    }
}

@Composable
private fun UngroupedThreads(
    state: ThreadListViewState.Ungrouped,
    onOpenFrequency: (Long) -> Unit,
    onLearnMoreAboutTier: () -> Unit,
    onImproveAtHome: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm)) {
        Text(text = "Threads", style = OrtType.screenTitle)
        Text(
            text = "${pluralize(state.totalOvers, "over")} · not yet grouped",
            style = OrtType.subtitle,
            color = OrtColors.textDim,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = OrtSpacing.lg)) {
        // R-163: the marker sits inline with the headline it qualifies, not on its own line above it.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AttributionRow(attribution = Attribution.ambiguous(), callsign = null)
            Text(
                text = "Conversations are not built on this phone yet",
                style = OrtType.bodyProse,
                color = OrtColors.textHigh,
            )
        }
        Text(
            text = "Grouping overs into QSOs needs speaker identity, which runs at tier 2 and above. " +
                "This phone is at tier ${state.currentTier}, so every over is here individually and " +
                "nothing has been guessed about who was talking to whom.",
            style = OrtType.cardBody,
            color = OrtColors.textMuted,
            modifier = Modifier.padding(top = 6.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(OrtSpacing.md),
            modifier = Modifier.padding(top = OrtSpacing.xs),
        ) {
            TextAction(text = "What tier ${state.currentTier} can and cannot do", onClick = onLearnMoreAboutTier)
            TextAction(text = "Improve at home", onClick = onImproveAtHome)
        }
    }
    Text(
        text = "By frequency, meanwhile".uppercase(),
        style = OrtType.sectionLabel,
        color = OrtColors.textFaint,
        modifier = Modifier.fillMaxWidth().padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.md),
    )
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(state.byFrequency, key = { it.frequencyHz }) { entry ->
            FrequencyMeanwhileRow(entry = entry, onClick = { onOpenFrequency(entry.frequencyHz) })
        }
    }
}

@Composable
private fun FrequencyMeanwhileRow(entry: FrequencyMeanwhileEntry, onClick: () -> Unit) {
    val overs = pluralize(entry.overCount, "over")
    val stations = pluralize(entry.stationCount, "station")
    val description = "${entry.frequencyLabel}, $overs, $stations heard"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm)
            .semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OrtSpacing.md),
    ) {
        Text(text = entry.frequencyLabel, style = OrtType.callsignRow, color = OrtColors.textHigh)
        Text(
            text = "$overs · $stations heard",
            style = OrtType.transcript,
            color = OrtColors.textTime,
            modifier = Modifier.weight(1f),
        )
        Icon(imageVector = OrtIcons.chevron, contentDescription = null, tint = OrtColors.textLow)
    }
}
