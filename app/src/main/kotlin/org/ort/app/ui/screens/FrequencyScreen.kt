package org.ort.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.ort.app.ui.components.ActivityPatternChart
import org.ort.app.ui.data.FrequencyDetailViewState
import org.ort.app.ui.data.FrequencyListEntryViewState
import org.ort.app.ui.data.dayOfWeekShortLabel
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/**
 * The "Frequencies" list (build-plan P17, FR-UI-10): every frequency ever recorded on a
 * transmission. `TransmissionEntity.frequencyHz` is nullable and unpopulated until the rig module
 * (M7) writes it — the list is genuinely empty until then, shown honestly rather than with
 * placeholder rows or a fabricated "unknown frequency" bucket (`ActivityDao.listDistinctFrequencies`
 * already excludes null).
 */
@Composable
public fun FrequenciesListScreen(
    frequencies: List<FrequencyListEntryViewState>,
    onOpen: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (frequencies.isEmpty()) {
        Text(
            text = "No frequencies recorded yet",
            style = MaterialTheme.typography.bodyLarge,
            modifier = modifier
                .fillMaxSize()
                .padding(OrtSpacing.lg)
                .semantics { contentDescription = "No frequencies recorded yet" },
        )
        return
    }
    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(frequencies, key = { it.frequencyHz }) { entry -> FrequencyRow(entry = entry, onOpen = onOpen) }
    }
}

@Composable
private fun FrequencyRow(entry: FrequencyListEntryViewState, onOpen: (Long) -> Unit) {
    val description = "${entry.label}, ${entry.transmissionCount} transmissions"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(entry.frequencyHz) }
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm),
    ) {
        Column {
            Text(text = entry.label, style = OrtType.callsign)
            Text(text = "${entry.transmissionCount} transmissions", style = OrtType.caption)
        }
    }
}

@Composable
private fun BackRow(onBack: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(OrtSpacing.lg)) {
        Text(
            text = "‹ Back",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.clickable(onClick = onBack).semantics { contentDescription = "Back" },
        )
    }
}

/**
 * Everything heard on one frequency, across every session (FR-UI-10), plus its activity pattern
 * (FR-UI-11) — [ActivityPatternChart] renders FR-UI-12's not-heard/not-listening distinction.
 */
@Composable
public fun FrequencyDetailScreen(state: FrequencyDetailViewState, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        BackRow(onBack)
        Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg)) {
            Text(text = state.label, style = OrtType.titleLarge)
            Text(
                text = "${state.transmissionCount} transmissions",
                style = OrtType.caption,
                modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.md),
            )
            ActivityPatternChart(pattern = state.activityPattern)
            if (state.dayOfWeekPattern.isNotEmpty()) {
                ActivityPatternChart(
                    pattern = state.dayOfWeekPattern,
                    modifier = Modifier.padding(top = OrtSpacing.md),
                    title = "ACTIVITY BY DAY OF WEEK",
                    summaryLabel = "Activity by day of week",
                    barLabels = state.dayOfWeekPattern.map { dayOfWeekShortLabel(it.dayOfWeek) },
                )
            }
            if (state.weekOverWeekSummary.isNotEmpty()) {
                Text(
                    text = "THIS WEEK VS LAST",
                    style = OrtType.sectionLabel,
                    modifier = Modifier.padding(top = OrtSpacing.md),
                )
                state.weekOverWeekSummary.forEach { line ->
                    Text(text = line, style = OrtType.caption)
                }
            }
        }
        if (state.transmissions.isEmpty()) {
            Text(
                text = "No transmissions recorded on this frequency",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(OrtSpacing.lg),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(top = OrtSpacing.md)) {
                items(state.transmissions, key = { it.id }) { entry ->
                    val rowModifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm)
                    Column(modifier = rowModifier) {
                        Text(text = entry.timeLabel, style = OrtType.caption)
                        Text(text = entry.transcriptText, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
