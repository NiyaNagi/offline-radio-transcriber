package org.ort.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.ActivityPatternChart
import org.ort.app.ui.components.AttributionMarker
import org.ort.app.ui.components.DayHourCell
import org.ort.app.ui.components.DayOfWeekGrid
import org.ort.app.ui.components.DayOfWeekGridOrientation
import org.ort.app.ui.components.DrillInHeader
import org.ort.app.ui.components.FilterChip
import org.ort.app.ui.components.FilterChipRow
import org.ort.app.ui.data.HourByDayActivityCell
import org.ort.app.ui.data.PatternMode
import org.ort.app.ui.data.StationPatternViewState
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType
import org.ort.core.Attribution

/**
 * `Station-Pattern.dc.html` (R-072, R-075, R-209, FR-UI-11/12): the `By hour` / `Hour × day` /
 * `Change over time` toggle, WP2's shared [DayOfWeekGrid] for the hour x day grid (`orientation =
 * DaysAsRows` — R-209 found this screen's grid transposed from the board; WP2 has since added the
 * matching orientation and legend wording this screen used to hand-roll its own copy of, so this
 * screen went back to the shared component rather than keep maintaining a look-alike — guide's own
 * rule: never hand-roll what a shared component already does), the week-over-week lines under
 * "Change over time", and a "What this says" list that names the unknown days and hours
 * explicitly. Every bucket here is **local time** — R-075: this screen's title carries no "(UTC)",
 * and [StationPatternViewState]'s buckets are already local by the time they reach this pure
 * render (`StationPolling.stationPattern` always calls `ActivityPatternMapper` with `zone =
 * ZoneId.systemDefault()`).
 */
@Composable
public fun StationPatternScreen(state: StationPatternViewState, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var mode by remember { mutableStateOf(PatternMode.BY_HOUR) }

    Column(modifier = modifier.fillMaxSize()) {
        DrillInHeader(parentLabel = state.label, onBack = onBack)
        Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg)) {
            Text(
                text = "When they are around",
                style = OrtType.screenTitle,
                modifier = Modifier.semantics { heading() },
            )
            // R-210 (register, polish, V5 @f8430b8): "Local time" named the zone, not the fact an
            // operator actually wants here — the real night count and the calendar range it spans,
            // matching `Station-Pattern.dc.html`'s own subtitle verbatim.
            Text(
                text = state.nightsSubtitle,
                style = OrtType.subtitle,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.md),
            )
            FilterChipRow {
                PatternMode.entries.forEach { candidate ->
                    FilterChip(
                        label = modeLabel(candidate),
                        selected = candidate == mode,
                        onClick = { mode = candidate },
                        modifier = Modifier.testTag("pattern-mode-${candidate.name}"),
                    )
                }
            }
            Spacer(modifier = Modifier.height(OrtSpacing.md))

            when (mode) {
                PatternMode.BY_HOUR ->
                    // No title here — the chip above already reads "By hour", selected; a second
                    // copy of the same three words would be an ambiguous duplicate, not a label.
                    ActivityPatternChart(pattern = state.hourPattern, title = "")

                PatternMode.HOUR_BY_DAY ->
                    DayOfWeekGrid(
                        cells = state.hourByDay.toDayHourCells(),
                        orientation = DayOfWeekGridOrientation.DaysAsRows,
                    )

                PatternMode.CHANGE_OVER_TIME ->
                    ChangeOverTime(lines = state.weekOverWeekSummary)
            }
        }

        Column(modifier = Modifier.padding(top = OrtSpacing.lg)) {
            Text(
                text = "What this says".uppercase(),
                style = OrtType.sectionLabel,
                color = OrtColors.textFaint,
                modifier = Modifier.padding(horizontal = OrtSpacing.lg),
            )
            LazyColumn(modifier = Modifier.fillMaxSize().padding(top = OrtSpacing.sm)) {
                items(state.whatThisSays) { line -> WhatThisSaysLine(line) }
            }
        }
    }
}

private fun modeLabel(mode: PatternMode): String = when (mode) {
    PatternMode.BY_HOUR -> "By hour"
    PatternMode.HOUR_BY_DAY -> "Hour × day"
    PatternMode.CHANGE_OVER_TIME -> "Change over time"
}

// [HourByDayActivityCell] (this package's own bucket shape, local-hour-of-day) to [DayHourCell]
// (the shared grid's shape, historically UTC-hour-of-day — its own field is still named
// `hourOfDayUtc` for that reason, but the grid itself is timezone-agnostic: it only ever indexes
// cells by whatever hour number a caller gives it, so passing an already-local hour here is
// correct, not a mismatch. R-075: every bucket this screen renders is local by the time it
// reaches here (`StationPolling.stationPattern`), so nothing here re-buckets by any zone.
private fun List<HourByDayActivityCell>.toDayHourCells(): List<DayHourCell> =
    map { DayHourCell(dayOfWeek = it.dayOfWeek, hourOfDayUtc = it.hourOfDay, state = it.state) }

@Composable
private fun ChangeOverTime(lines: List<String>, modifier: Modifier = Modifier) {
    if (lines.isEmpty()) {
        Text(
            text = "Not enough listening yet to say how this has changed week over week.",
            style = OrtType.cardBody,
            color = OrtColors.textMuted,
            modifier = modifier,
        )
        return
    }
    Column(modifier = modifier) {
        lines.forEach { line ->
            Text(
                text = line,
                style = OrtType.cardBody,
                color = OrtColors.textBody,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun WhatThisSaysLine(line: String, modifier: Modifier = Modifier) {
    // The unknown-days/unknown-hours lines carry the AMBIGUOUS-shaped marker, matching
    // `Station-Pattern.dc.html`'s own convention (half-filled amber ring beside those two
    // sentences); the peak sentence carries a plain CONFIRMED-shaped marker. Shape-only
    // (`showConfidence = false`) — this is not an attribution of a transmission, just the
    // marker's shape vocabulary borrowed for this prose list's own bullet.
    val attribution = if (line.contains("unknown")) Attribution.ambiguous() else Attribution.confirmed("x", 1.0)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.xs)
            .semantics(mergeDescendants = true) { contentDescription = line },
        verticalAlignment = Alignment.Top,
    ) {
        AttributionMarker(attribution = attribution, showConfidence = false)
        Text(
            text = line,
            style = OrtType.subtitle,
            color = OrtColors.textBody,
            modifier = Modifier.weight(1f).padding(start = OrtSpacing.sm),
        )
    }
}
