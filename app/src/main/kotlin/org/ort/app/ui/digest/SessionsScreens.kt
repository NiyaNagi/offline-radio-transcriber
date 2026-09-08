package org.ort.app.ui.digest

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.ActivityPatternChart
import org.ort.app.ui.components.Badge
import org.ort.app.ui.components.BadgeKind
import org.ort.app.ui.components.DrillInHeader
import org.ort.app.ui.components.EmptyState
import org.ort.app.ui.components.KeyValueRow
import org.ort.app.ui.components.OrtIcons
import org.ort.app.ui.components.ScreenHeader
import org.ort.app.ui.components.SecondaryButton
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/** `Sessions.dc.html` (R-092, R-107, FR-UI-1, FR-RUN-12): earlier nights, each with span, counts,
 * gaps — the `TIER 1` chip and "can be improved" when [SessionRowViewState.canBeImproved]. */
@Composable
public fun SessionsScreen(
    state: SessionsViewState,
    onDrawer: () -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(onDrawer = onDrawer)
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = OrtSpacing.lg),
        ) {
            Text(
                text = "Earlier nights",
                style = OrtType.screenTitle,
                color = OrtColors.textHigh,
                modifier = Modifier.padding(top = OrtSpacing.sm),
            )
            Text(
                text = state.headline,
                style = OrtType.subtitle,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.sm),
            )

            if (state.sessions.isEmpty()) {
                EmptyState(
                    message = "No sessions recorded yet.",
                    subMessage = "A night's capture appears here once it starts.",
                )
            } else {
                state.sessions.forEach { session -> SessionRow(session = session, onClick = { onOpen(session.id) }) }
            }
        }
    }
}

@Composable
private fun SessionRow(session: SessionRowViewState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = buildString {
        append(session.label)
        append(". ")
        append(session.timeRangeLabel)
        append(" · ${session.overCount} overs · ${session.stationCount} stations")
        if (session.gapCount > 0) append(" · ${session.gapCount} gap(s)")
        session.uncleanEndLabel?.let { append(" · $it") }
        if (session.canBeImproved) append(" · can be improved")
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = OrtSpacing.sm)
            .semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
    ) {
        Row(modifier = Modifier.weight(1f).padding(end = OrtSpacing.sm)) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(OrtSpacing.xs),
                ) {
                    Text(text = session.label, style = OrtType.rowTitle, color = OrtColors.textHigh)
                    session.tierChipLabel?.let { Badge(text = "TIER ${it.removePrefix("T")}", kind = BadgeKind.TIER) }
                }
                Text(
                    text = buildString {
                        append(session.timeRangeLabel)
                        append(" · ${session.overCount} overs · ${session.stationCount} stations")
                        if (session.gapCount > 0) append(" · ${session.gapCount} gap(s)")
                        session.uncleanEndLabel?.let { append(" · $it") }
                        if (session.canBeImproved) append(" · can be improved")
                    },
                    style = OrtType.subLine,
                    color = if (session.canBeImproved) OrtColors.accentGreen else OrtColors.textDim,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Icon(imageVector = OrtIcons.chevron, contentDescription = null, tint = OrtColors.textSignal)
    }
}

/** `Session.dc.html` (FR-RUN-12, FR-RUN-16): span, coverage (WP2's [ActivityPatternChart] with
 * gaps as not-listening), the gap list with each [org.ort.data.entity.CaptureGapCause] as prose,
 * and session facts. `Log` opens the LOG destination filtered to this exact session. */
@Composable
public fun SessionDetailScreen(
    state: SessionDetailViewState,
    onBack: () -> Unit,
    onOpenLog: () -> Unit,
    onOpenDigest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DrillInHeader(parentLabel = "Earlier nights", onBack = onBack)
        Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg)) {
            Text(text = state.label, style = OrtType.screenTitle, color = OrtColors.textHigh)
            Text(
                text = "${state.timeRangeLabel} · ${state.durationLabel} · " +
                    (state.uncleanEndLabel ?: "ended cleanly"),
                style = OrtType.subtitle,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.sm),
            )

            SectionHeader(label = "Coverage", modifier = Modifier.padding(top = OrtSpacing.md))
            ActivityPatternChart(
                pattern = state.coverage,
                title = "COVERAGE",
                summaryLabel = "Session coverage",
                notListeningLabel = state.notListeningLabel,
                modifier = Modifier.padding(top = OrtSpacing.sm),
            )

            if (state.gaps.isNotEmpty()) {
                SectionHeader(label = "Gaps", modifier = Modifier.padding(top = OrtSpacing.md))
                state.gaps.forEach { gap ->
                    KeyValueRow(
                        key = gap.timeLabel,
                        value = "${gap.durationLabel} · ${gap.causeLabel}",
                        subLine = gap.resumedLabel,
                    )
                }
            }

            SectionHeader(label = "Session", modifier = Modifier.padding(top = OrtSpacing.md))
            KeyValueRow(
                key = "Overs",
                value = "${state.overCount}",
                subLine = "${state.rejectedCount} rejected · ${state.failedCount} failed",
            )
            KeyValueRow(key = "Stations", value = "${state.stationCount}")
            KeyValueRow(key = "Frequencies", value = state.frequencyLabels.joinToString(" · ").ifEmpty { "none" })
            KeyValueRow(key = "Input", value = state.inputLabel)
            KeyValueRow(key = "Tier", value = state.tierLabel)
            KeyValueRow(key = "Audio", value = "${state.audioSizeLabel} retained · lossless")

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = OrtSpacing.lg, bottom = OrtSpacing.lg),
                horizontalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
            ) {
                SecondaryButton(text = "Digest", onClick = onOpenDigest, modifier = Modifier.weight(1f))
                SecondaryButton(text = "Log", onClick = onOpenLog, modifier = Modifier.weight(1f))
            }
        }
    }
}
