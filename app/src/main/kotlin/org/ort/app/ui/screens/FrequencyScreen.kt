package org.ort.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.ActivityPatternChart
import org.ort.app.ui.components.AttributionRow
import org.ort.app.ui.components.DrillInHeader
import org.ort.app.ui.components.EmptyState
import org.ort.app.ui.components.KeyValueRow
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.components.Sparkline
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.data.FrequencyDetailViewState
import org.ort.app.ui.data.FrequencyListEntryViewState
import org.ort.app.ui.data.FrequencyRegularViewState
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/**
 * The "Frequencies" list (R-074, FR-UI-10): `Frequencies.dc.html`'s column header, what it is
 * (band/mode, never a guessed repeater/simplex claim), tonight's count and station count, the
 * 14-night [Sparkline] with its hatched not-listening nights, and "busier than usual" in amber
 * where [org.ort.app.ui.data.NightlyDeparture.isBusierThanUsual] holds.
 */
@Composable
public fun FrequenciesListScreen(
    frequencies: List<FrequencyListEntryViewState>,
    onOpen: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (frequencies.isEmpty()) {
        EmptyState(
            message = "No frequencies recorded yet",
            modifier = modifier.fillMaxSize().padding(OrtSpacing.lg),
        )
        return
    }
    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = "Frequencies",
            style = OrtType.screenTitle,
            modifier = Modifier.padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm)
                .semantics { heading() },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.xs),
        ) {
            Text(text = "MHz".uppercase(), style = OrtType.columnHeader, color = OrtColors.textDisabled)
            Spacer(modifier = Modifier.width(56.dp))
            Text(
                text = "what it is".uppercase(),
                style = OrtType.columnHeader,
                color = OrtColors.textDisabled,
                modifier = Modifier.weight(1f),
            )
            Text(text = "14 nights".uppercase(), style = OrtType.columnHeader, color = OrtColors.textDisabled)
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(frequencies, key = { it.frequencyHz }) { entry -> FrequencyRow(entry = entry, onOpen = onOpen) }
        }
    }
}

@Composable
private fun FrequencyRow(entry: FrequencyListEntryViewState, onOpen: (Long) -> Unit) {
    val busyNote = if (entry.busierThanUsual) ", busier than usual" else ""
    val description = "${entry.label}, ${entry.whatItIs}, ${entry.tonightCount} overs tonight, " +
        "${entry.tonightStationCount} stations$busyNote"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = { onOpen(entry.frequencyHz) })
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = entry.label.removeSuffix(" MHz"),
            style = OrtType.figure,
            color = OrtColors.textHigh,
            modifier = Modifier.width(72.dp),
        )
        Column(modifier = Modifier.weight(1f).padding(start = OrtSpacing.sm)) {
            if (entry.whatItIs.isNotBlank()) {
                Text(text = entry.whatItIs, style = OrtType.subtitle, color = OrtColors.textBody)
            }
            val busyLabel = if (entry.busierThanUsual) " · busier than usual" else ""
            Text(
                text = "${entry.tonightCount} overs tonight · ${entry.tonightStationCount} stations$busyLabel",
                style = OrtType.subLine,
                color = if (entry.busierThanUsual) OrtColors.accentAmber else OrtColors.textFaint,
            )
        }
        if (entry.nights.isNotEmpty()) {
            Sparkline(nights = entry.nights)
        }
    }
}

// -------------------------------------------------------------------------------------------
// Frequency detail (R-074) — Frequency.dc.html.
// -------------------------------------------------------------------------------------------

/**
 * Everything heard on one frequency, across every session (FR-UI-10), plus its activity pattern
 * (FR-UI-11) — [ActivityPatternChart] renders FR-UI-12's not-heard/not-listening distinction —
 * the R-074 facts table, the typical-night chart and `REGULARS`.
 */
@Composable
public fun FrequencyDetailScreen(
    state: FrequencyDetailViewState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenStation: (String) -> Unit = {},
    onOpenChange: () -> Unit = {},
    onViewAllOvers: () -> Unit = {},
) {
    Column(modifier = modifier.fillMaxSize()) {
        DrillInHeader(parentLabel = "Frequencies", onBack = onBack)
        // A single top-level LazyColumn — see StationDetailScreen's own doc comment for why
        // (a `verticalScroll` Column measured fine here too, but silently swallowed a nested
        // `clickable` Row's own tap in this host).
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                FrequencyHeaderSection(state = state, onOpenChange = onOpenChange, onViewAllOvers = onViewAllOvers)
            }
            item {
                SectionHeader(
                    label = "Regulars",
                    trailingActionLabel = "All ${state.regulars.size}",
                    onTrailingAction = {},
                    modifier = Modifier.padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm),
                )
            }
            if (state.regulars.isEmpty()) {
                item {
                    EmptyState(
                        message = "No regular stations recorded on this frequency yet",
                        modifier = Modifier.padding(horizontal = OrtSpacing.lg),
                    )
                }
            } else {
                items(state.regulars, key = { it.stationId }) { regular ->
                    RegularRow(entry = regular, onOpen = onOpenStation)
                }
            }
        }
    }
}

@Composable
private fun FrequencyHeaderSection(
    state: FrequencyDetailViewState,
    onOpenChange: () -> Unit,
    onViewAllOvers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(horizontal = OrtSpacing.lg)) {
        Text(text = state.label, style = OrtType.callsignTitle, modifier = Modifier.semantics { heading() })
        if (state.whatItIs.isNotBlank()) {
            Text(
                text = state.whatItIs,
                style = OrtType.subtitle,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.md),
            )
        }
        if (state.busierThanUsual) {
            TextAction(text = "Busier than usual — see what changed", onClick = onOpenChange)
        }

        Column {
            KeyValueRow(
                key = "Overs",
                value = "${state.transmissionCount} all time · ${state.transmissionCountTonight} tonight",
                trailingMarker = { TextAction(text = "Log", onClick = onViewAllOvers) },
            )
            KeyValueRow(
                key = "Stations",
                value = "${state.stationCountAllTime} all time · ${state.stationCountTonight} tonight",
            )
        }

        Column(modifier = Modifier.padding(top = OrtSpacing.md)) {
            SectionHeader(label = "Typical night, by hour")
            ActivityPatternChart(
                pattern = state.activityPattern,
                title = "",
                modifier = Modifier.padding(top = OrtSpacing.sm),
            )
            if (state.patternSummarySentence.isNotBlank()) {
                Text(
                    text = state.patternSummarySentence,
                    style = OrtType.cardBody,
                    color = OrtColors.textBody,
                    modifier = Modifier.padding(top = OrtSpacing.sm),
                )
            }
        }
    }
}

@Composable
private fun RegularRow(entry: FrequencyRegularViewState, onOpen: (String) -> Unit) {
    val description = "${entry.label}, ${entry.countContext}"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("regular-${entry.stationId}")
            .clickable(role = Role.Button, onClick = { onOpen(entry.stationId) })
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AttributionRow(attribution = entry.attribution, callsign = entry.label)
        Text(
            text = entry.countContext,
            style = OrtType.subtitle,
            color = OrtColors.textTime,
            modifier = Modifier.weight(1f).padding(start = OrtSpacing.sm),
        )
        entry.lastHeardLabel?.let {
            Text(text = it, style = OrtType.timeFreq, color = OrtColors.textFigure)
        }
    }
}
