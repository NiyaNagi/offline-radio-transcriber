package org.ort.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.ActivityPatternChart
import org.ort.app.ui.components.AttributionMarker
import org.ort.app.ui.components.DrillInHeader
import org.ort.app.ui.components.FilterChip
import org.ort.app.ui.components.FilterChipRow
import org.ort.app.ui.components.drawHatchRegion
import org.ort.app.ui.data.HourActivityState
import org.ort.app.ui.data.HourByDayActivityCell
import org.ort.app.ui.data.PatternMode
import org.ort.app.ui.data.StationPatternViewState
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType
import org.ort.core.Attribution
import java.time.DayOfWeek

/**
 * `Station-Pattern.dc.html` (R-072, R-075, FR-UI-11/12): the `By hour` / `Hour × day` /
 * `Change over time` toggle, [StationHourByDayGrid] for the hour x day grid (days as rows, hours
 * as columns — R-209 found the shared `DayOfWeekGrid` transposed from the board and with no
 * per-column hour labels; that component has no orientation parameter today, so this screen draws
 * its own grid rather than wait on one), the week-over-week lines under "Change over time", and a
 * "What this says" list that names the unknown days and hours explicitly. Every bucket here is
 * **local time** — R-075: this screen's title carries no "(UTC)", and [StationPatternViewState]'s
 * buckets are already local by the time they reach this pure render (`StationPolling.stationPattern`
 * always calls `ActivityPatternMapper` with `zone = ZoneId.systemDefault()`).
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
                    StationHourByDayGrid(cells = state.hourByDay)

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

private val DAY_ORDER: List<DayOfWeek> = listOf(
    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
    DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY,
)
private val HOUR_CELL = 11.dp
private val HOUR_GAP = 2.dp
private val DAY_LABEL_WIDTH = 30.dp

/**
 * `Station-Pattern.dc.html`'s hour x day grid (R-072, R-209): days as rows, labelled down the
 * left ("Mon".."Sun"); hours as columns, labelled along the top every six hours. Own composable —
 * see this file's own top doc comment for why, rather than the shared, transposed `DayOfWeekGrid`.
 */
@Composable
private fun StationHourByDayGrid(cells: List<HourByDayActivityCell>, modifier: Modifier = Modifier) {
    val byDayHour = cells.associateBy { it.dayOfWeek to it.hourOfDay }
    val heard = cells.count { it.state == HourActivityState.HEARD }
    val silent = cells.count { it.state == HourActivityState.SILENT_WHILE_LISTENING }
    val notListening = DAY_ORDER.size * 24 - heard - silent
    val summary = "Activity by day and hour: $heard cells heard, $silent quiet while listening, " +
        "$notListening not listening"

    Column(modifier = modifier.semantics(mergeDescendants = true) { contentDescription = summary }) {
        Row(modifier = Modifier.padding(start = DAY_LABEL_WIDTH), horizontalArrangement = Arrangement.spacedBy(HOUR_GAP)) {
            (0 until 24).forEach { hour ->
                HourLabelCell(hour, modifier = Modifier.width(HOUR_CELL))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(HOUR_GAP), modifier = Modifier.padding(top = 2.dp)) {
            DAY_ORDER.forEach { day ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(HOUR_GAP)) {
                    Text(
                        text = dayAbbreviation(day),
                        style = OrtType.axis,
                        color = OrtColors.textLow,
                        modifier = Modifier.width(DAY_LABEL_WIDTH),
                    )
                    (0 until 24).forEach { hour ->
                        HourByDayCell(state = byDayHour[day to hour]?.state, modifier = Modifier.size(HOUR_CELL))
                    }
                }
            }
        }
        StationHourByDayLegend(modifier = Modifier.padding(top = OrtSpacing.sm))
    }
}

@Composable
private fun HourLabelCell(hour: Int, modifier: Modifier = Modifier) {
    if (hour % 6 == 0) {
        Text(text = "%02d".format(hour), style = OrtType.axis, color = OrtColors.textLow, modifier = modifier)
    } else {
        Spacer(modifier = modifier)
    }
}

@Composable
private fun HourByDayCell(state: HourActivityState?, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val radius = CornerRadius(2.dp.toPx())
        when (state) {
            HourActivityState.HEARD -> drawRoundRect(color = OrtColors.chartGreenRamp[0], cornerRadius = radius)
            HourActivityState.SILENT_WHILE_LISTENING ->
                drawRoundRect(color = OrtColors.chartNeutralListenedSilent, cornerRadius = radius)
            HourActivityState.NOT_LISTENING, null -> {
                drawRoundRect(color = OrtColors.chartNeutralRamp.last(), cornerRadius = radius)
                drawHatchRegion(
                    left = 0f,
                    top = 0f,
                    width = size.width,
                    height = size.height,
                    color = OrtColors.hatchBar,
                    pitchPx = 3.dp.toPx(),
                    strokeWidthPx = 1.dp.toPx(),
                )
            }
        }
    }
}

/** R-209's own legend wording — never `DayOfWeekGrid`'s "heard"/"quiet". */
@Composable
private fun StationHourByDayLegend(modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(OrtSpacing.md)) {
        LegendDot(color = OrtColors.chartNeutralListenedSilent, label = "listened, not heard")
        LegendDot(color = OrtColors.chartGreenRamp[0], label = "heard often")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Canvas(modifier = Modifier.size(10.dp)) {
                drawRoundRect(color = OrtColors.chartNeutralRamp.last(), cornerRadius = CornerRadius(1.5.dp.toPx()))
                drawHatchRegion(
                    left = 0f, top = 0f, width = size.width, height = size.height,
                    color = OrtColors.hatchBar, pitchPx = 3.dp.toPx(), strokeWidthPx = 1.dp.toPx(),
                )
            }
            Text(text = "not listening", style = OrtType.subLine, color = OrtColors.accentAmber)
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Canvas(modifier = Modifier.size(10.dp)) {
            drawRoundRect(color = color, cornerRadius = CornerRadius(1.5.dp.toPx()))
        }
        Text(text = label, style = OrtType.subLine, color = OrtColors.textDim)
    }
}

private fun dayAbbreviation(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "Mon"
    DayOfWeek.TUESDAY -> "Tue"
    DayOfWeek.WEDNESDAY -> "Wed"
    DayOfWeek.THURSDAY -> "Thu"
    DayOfWeek.FRIDAY -> "Fri"
    DayOfWeek.SATURDAY -> "Sat"
    DayOfWeek.SUNDAY -> "Sun"
}

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
