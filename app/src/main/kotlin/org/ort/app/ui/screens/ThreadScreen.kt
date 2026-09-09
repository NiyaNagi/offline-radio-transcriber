package org.ort.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import org.ort.app.ui.components.rememberCallsignColumnWidth
import org.ort.app.ui.components.rememberTimeColumnWidth
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

/**
 * R-511 (`overnight/T01-threads@2x.png`, both scales): `Threads.dc.html`'s own leading `.t`
 * column — the thread's first-over time, mono grey, 52dp/`rememberTimeColumnWidth` wide — was
 * never rendered at all; [card]'s own `timeLabel` (already computed, already what the list sorts
 * by) simply had no `Text` in this composable. One-line layout puts it beside the kind/title/meta
 * block, matching the board; at a font scale too narrow for time + the title block's own callsign
 * floor + the trailing chevron, the whole row stacks — the title block moves to its own full-width
 * line on top, time (with the chevron) beneath it — the identical "weighted column on its own
 * line, fixed columns bundled beneath it" rule [LogRow]/[ColumnHeaderRow] already established for
 * R-373/R-420, applied here rather than a second, competing breakpoint rule invented from scratch.
 *
 * `internal`, not `private` (R-511): [org.ort.app.ui.screens.ThreadScreenTest]'s own stacking test
 * needs to constrain and measure this one row directly, the same way [org.ort.app.ui.components.LogRow]
 * (a genuinely shared, `public` component) already lets `LogRowResponsiveTest` do — this composable
 * stays screen-private in every other sense (not exported for reuse elsewhere), module-visible only
 * so its own test can reach it.
 */
@Composable
internal fun ThreadCard(card: ThreadCardViewState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = buildString {
        append("${card.timeLabel}. ")
        card.kindLabel?.let { append("$it. ") }
        append("${card.frequencyLabel}, ${card.overCount} overs. ")
        append("${card.titleText}. ${card.metaText}")
    }
    val timeWidth = rememberTimeColumnWidth()
    val titleFloor = rememberCallsignColumnWidth()
    val gap = OrtSpacing.md
    val chevronSlot = 24.dp + gap
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(if (card.ambiguous) OrtColors.bgRowAmbiguous else Color.Transparent)
            .heightIn(min = 44.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.md),
        ) {
            val oneLineWidth = timeWidth + gap + titleFloor + chevronSlot
            if (maxWidth >= oneLineWidth) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    ThreadCardTimeText(card.timeLabel, Modifier.widthIn(min = timeWidth))
                    ThreadCardBody(card, Modifier.weight(1f))
                    Icon(imageVector = OrtIcons.chevron, contentDescription = null, tint = OrtColors.textLow)
                }
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ThreadCardBody(card, Modifier.fillMaxWidth())
                    Row(
                        modifier = Modifier.padding(top = 3.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        ThreadCardTimeText(card.timeLabel, Modifier.widthIn(min = timeWidth))
                        Box(modifier = Modifier.weight(1f))
                        Icon(imageVector = OrtIcons.chevron, contentDescription = null, tint = OrtColors.textLow)
                    }
                }
            }
        }
    }
}

/** [ThreadCard]'s own leading time column — see that composable's R-511 doc comment. */
@Composable
private fun ThreadCardTimeText(timeLabel: String, modifier: Modifier = Modifier) {
    Text(
        text = timeLabel,
        style = OrtType.timeFreq,
        color = OrtColors.textTime,
        maxLines = 1,
        softWrap = false,
        modifier = modifier.padding(top = 1.dp),
    )
}

/** [ThreadCard]'s kind/title/meta block, factored out so both the one-line and stacked layouts
 * render the identical content rather than two copies that could quietly drift apart. */
@Composable
private fun ThreadCardBody(card: ThreadCardViewState, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
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
