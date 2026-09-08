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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.AttributionMarker
import org.ort.app.ui.components.AttributionRow
import org.ort.app.ui.components.ColumnHeaderRow
import org.ort.app.ui.components.DrillInHeader
import org.ort.app.ui.data.ThreadAttributionExplanationLine
import org.ort.app.ui.data.ThreadDetailOverViewState
import org.ort.app.ui.data.ThreadDetailViewState
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/**
 * The `Thread-Detail.dc.html` drill-in (R-044, ui-conformance WP5, FR-UI-2/P2): the "How these
 * were attributed" card (constitution I — every machine conclusion MUST be inspectable) plus
 * every over in the thread with its own reasoning line, whose source-over link
 * ([onOpenSourceOver]) opens the transmission an inherited attribution was carried from.
 *
 * Reachable from [ThreadScreen]'s thread cards via `ThreadContent`'s `onOpenThread`, through WP3's
 * own `ThreadDetailContent` wrapper in `OrtNavHost.kt` (that host polls [ThreadPolling.threadDetail]
 * and renders this screen — see its own doc comment). [backLabel] (R-017: the chevron names where
 * the operator came from) defaults to the common case but the host may override it with the real
 * navigation origin (`ids.openedFrom.label`).
 */
@Composable
public fun ThreadDetailScreen(
    state: ThreadDetailViewState,
    onBack: () -> Unit,
    onOpenOver: (String) -> Unit,
    onOpenSourceOver: (String) -> Unit,
    modifier: Modifier = Modifier,
    backLabel: String = "Threads",
) {
    Column(modifier = modifier.fillMaxSize()) {
        DrillInHeader(parentLabel = backLabel, onBack = onBack)

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm)) {
            val kindPrefix = state.kindLabel?.let { "$it · " }.orEmpty()
            Text(
                text = "$kindPrefix${state.frequencyLabel}".uppercase(),
                style = OrtType.columnHeader,
                color = OrtColors.textLow,
            )
            Text(text = state.titleText, style = OrtType.screenTitle, modifier = Modifier.padding(top = 4.dp))
            Text(
                text = state.metaText,
                style = OrtType.subtitle,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = 3.dp),
            )
        }

        HowAttributedCard(lines = state.howAttributed)

        ColumnHeaderRow(stationLabel = "over", signalLabel = "")

        LazyColumn(modifier = Modifier.fillMaxSize().testTag("thread-detail-overs")) {
            items(state.overs, key = { it.transmissionId }) { over ->
                ThreadDetailOverRow(
                    over = over,
                    onOpen = { onOpenOver(over.transmissionId) },
                    onOpenSource = onOpenSourceOver,
                )
            }
        }
    }
}

@Composable
private fun HowAttributedCard(lines: List<ThreadAttributionExplanationLine>, modifier: Modifier = Modifier) {
    if (lines.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm)
            .background(OrtColors.bgCard, RoundedCornerShape(10.dp))
            .padding(OrtSpacing.md),
    ) {
        Text(
            text = "How these were attributed".uppercase(),
            style = OrtType.sectionLabel,
            color = OrtColors.textFaint,
        )
        Column(modifier = Modifier.padding(top = OrtSpacing.sm)) {
            lines.forEach { line ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                ) {
                    AttributionMarker(attribution = line.attribution, showConfidence = false)
                    Text(text = line.text, style = OrtType.chip, color = OrtColors.textBody)
                }
            }
        }
    }
}

/**
 * Two independently-tappable regions, deliberately not nested under one merged clickable: the
 * over itself (time/marker/transcript — opens the over via [onOpen]) and, when the reasoning
 * names a source over, that reasoning line on its own (opens the source via [onOpenSource]) —
 * a link *inside* the row's own description would otherwise be unreachable as its own target.
 */
@Composable
private fun ThreadDetailOverRow(over: ThreadDetailOverViewState, onOpen: () -> Unit, onOpenSource: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clickable(role = Role.Button, onClick = onOpen)
                .semantics(mergeDescendants = true) { contentDescription = "${over.timeLabel}. ${over.transcript}" },
        ) {
            Text(
                text = over.timeLabel,
                style = OrtType.timeFreq,
                color = OrtColors.textTime,
                modifier = Modifier.padding(top = 1.dp),
            )
            Column(modifier = Modifier.padding(start = OrtSpacing.sm)) {
                AttributionRow(attribution = over.attribution)
                Text(
                    text = over.transcript,
                    style = OrtType.transcript,
                    color = OrtColors.textSecondary,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        val sourceId = over.sourceTransmissionId
        if (sourceId != null) {
            Text(
                text = over.reasoning,
                style = OrtType.subLine,
                color = OrtColors.textFaint,
                modifier = Modifier
                    .padding(start = 62.dp, top = 4.dp)
                    .heightIn(min = 44.dp)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = "Open source over",
                        onClick = { onOpenSource(sourceId) },
                    )
                    .semantics(mergeDescendants = true) {},
            )
        } else {
            Text(
                text = over.reasoning,
                style = OrtType.subLine,
                color = OrtColors.textFaint,
                modifier = Modifier.padding(start = 62.dp, top = 4.dp),
            )
        }
    }
}
