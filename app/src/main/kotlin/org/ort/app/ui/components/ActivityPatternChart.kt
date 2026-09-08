package org.ort.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.ort.app.ui.data.ActivityBucket
import org.ort.app.ui.data.HourActivityState
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType
import java.time.DayOfWeek
import kotlin.math.roundToInt

private val CHART_HEIGHT = 38.dp
private val BAR_GAP = 1.5.dp
private val GRID_CELL = 16.dp
private val GRID_GAP = 3.dp
private val SPARK_WIDTH = 56.dp
private val SPARK_HEIGHT = 18.dp
private val SPARK_GAP = 1.dp

/**
 * R-021 (ui-conformance-plan WP2): FR-UI-11's activity pattern (`design/canvas/Charts.dc.html`,
 * guide §8), re-rendered so the bars carry the guide's five-step [OrtColors.chartGreenRamp] by
 * intensity rather than a single flat green, [HourActivityState.SILENT_WHILE_LISTENING] reads on
 * the neutral ramp, and — the one rule this whole component exists to make impossible to miss
 * (FR-UI-12) — [HourActivityState.NOT_LISTENING] is a **full-height** 45-degree hatch in
 * [OrtColors.hatchBar], never a short bar and never the `▨` font glyph the pre-R-021 legend used
 * (guide §7: "never a font glyph").
 *
 * [pattern] must already be in the order it should render — this component does not know how to
 * sort a generic [ActivityBucket]. [barLabels], when given, is one short label per [pattern]
 * element (the day-of-week chart's "Mon".."Sun"). [axisStart]/[axisEnd] are the guide's mono axis
 * labels at each end (e.g. "22:00"/"06:00") — omitted (default) renders no axis row unless a
 * not-listening legend is also needed. [notListeningLabel] is the caller-supplied "38 s" half of
 * "not listening · 38 s" (constitution I: never fabricate a duration this component was not
 * given); when null the legend still renders, just without a duration.
 */
@Composable
public fun ActivityPatternChart(
    pattern: List<ActivityBucket>,
    modifier: Modifier = Modifier,
    title: String = "ACTIVITY BY HOUR (UTC)",
    summaryLabel: String = "Activity by hour of day",
    barLabels: List<String>? = null,
    axisStart: String? = null,
    axisEnd: String? = null,
    notListeningLabel: String? = null,
) {
    val heardHours = pattern.count { it.state == HourActivityState.HEARD }
    val silentHours = pattern.count { it.state == HourActivityState.SILENT_WHILE_LISTENING }
    val notListeningHours = pattern.count { it.state == HourActivityState.NOT_LISTENING }
    val maxHeardCount = pattern.maxOfOrNull { it.heardCount } ?: 0
    val summary = "$summaryLabel: $heardHours hours heard, $silentHours hours quiet " +
        "while listening, $notListeningHours hours not listening"

    Column(modifier = modifier.semantics(mergeDescendants = true) { contentDescription = summary }) {
        Text(text = title, style = OrtType.sectionLabel, color = OrtColors.textFaint)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = OrtSpacing.sm).height(CHART_HEIGHT),
            horizontalArrangement = Arrangement.spacedBy(BAR_GAP),
        ) {
            pattern.forEach { bucket ->
                HourBar(bucket = bucket, maxHeardCount = maxHeardCount, modifier = Modifier.weight(1f).fillMaxHeight())
            }
        }
        if (barLabels != null) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BAR_GAP)) {
                barLabels.forEach { label ->
                    Text(text = label, style = OrtType.axis, color = OrtColors.textLow, modifier = Modifier.weight(1f))
                }
            }
        }
        if (axisStart != null || axisEnd != null || notListeningHours > 0) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = OrtSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = axisStart.orEmpty(), style = OrtType.axis, color = OrtColors.textLow)
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (notListeningHours > 0) {
                        NotListeningLegend(notListeningLabel)
                    }
                }
                Text(text = axisEnd.orEmpty(), style = OrtType.axis, color = OrtColors.textLow)
            }
        }
    }
}

@Composable
private fun NotListeningLegend(notListeningLabel: String?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Canvas(modifier = Modifier.width(9.dp).height(7.dp)) {
            drawHatchRegion(
                left = 0f,
                top = 0f,
                width = size.width,
                height = size.height,
                color = OrtColors.accentGapDim,
                pitchPx = 4.dp.toPx(),
                strokeWidthPx = 2.dp.toPx(),
            )
        }
        Text(
            text = if (notListeningLabel != null) "not listening · $notListeningLabel" else "not listening",
            style = OrtType.subLine,
            color = OrtColors.accentAmberText,
        )
    }
}

@Composable
private fun HourBar(bucket: ActivityBucket, maxHeardCount: Int, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        when (bucket.state) {
            HourActivityState.HEARD -> {
                val intensity = if (maxHeardCount > 0) bucket.heardCount.toFloat() / maxHeardCount else 1f
                val barHeight = height * (0.15f + 0.85f * intensity)
                val ramp = OrtColors.chartGreenRamp
                val index = ((1f - intensity) * (ramp.size - 1)).roundToInt().coerceIn(0, ramp.lastIndex)
                drawRect(
                    color = ramp[index],
                    topLeft = Offset(0f, height - barHeight),
                    size = Size(width, barHeight),
                )
            }

            HourActivityState.SILENT_WHILE_LISTENING -> {
                val barHeight = height * 0.12f
                drawRect(
                    color = OrtColors.chartNeutralRamp[2],
                    topLeft = Offset(0f, height - barHeight),
                    size = Size(width, barHeight),
                )
            }

            HourActivityState.NOT_LISTENING ->
                // Full-height, textured (diagonal-stripe) bar — never a short one — so
                // "not listening" reads as structurally different even without colour (FR-UI-12).
                drawHatchRegion(
                    left = 0f,
                    top = 0f,
                    width = width,
                    height = height,
                    color = OrtColors.hatchBar,
                    pitchPx = 5.dp.toPx(),
                    strokeWidthPx = 2.dp.toPx(),
                )
        }
    }
}

/** Diagonal 45-degree hatch stripes clipped to one region of the current [DrawScope] — shared by
 * the full-height not-listening bar, the day-of-week grid's not-listening cell and the legend
 * swatches, so every "not listening" texture in the product is drawn identically (FR-UI-12). */
internal fun DrawScope.drawHatchRegion(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    color: Color,
    pitchPx: Float,
    strokeWidthPx: Float,
) {
    clipRect(left = left, top = top, right = left + width, bottom = top + height) {
        val end = left + width
        var x = left - height
        while (x < end) {
            drawLine(
                color = color,
                start = Offset(x, top + height),
                end = Offset(x + height, top),
                strokeWidth = strokeWidthPx,
            )
            x += pitchPx
        }
    }
}

// ---------------------------------------------------------------------------------------------
// DayOfWeekGrid — Station-Pattern.dc.html's hour x day grid.
// ---------------------------------------------------------------------------------------------

/** One hour-of-day/day-of-week cell of the [DayOfWeekGrid] (FR-UI-11's day-of-week detail). */
public data class DayHourCell(val dayOfWeek: DayOfWeek, val hourOfDayUtc: Int, val state: HourActivityState)

/**
 * `Station-Pattern.dc.html`'s hour x day grid: 16dp cells on a 3dp gap, one column per
 * [DayOfWeek] with a mono day-initial label beneath, and the same three-way distinction
 * [ActivityPatternChart] draws (heard / listened-but-silent / not-listening, the last always
 * hatched — FR-UI-12). An (day, hour) pair absent from [cells] renders as not-listening, exactly
 * as an untouched hour does in [org.ort.app.ui.data.ActivityPatternMapper] — absence of data and
 * absence of listening read identically on purpose (constitution I).
 */
@Composable
public fun DayOfWeekGrid(
    cells: List<DayHourCell>,
    modifier: Modifier = Modifier,
    hours: List<Int> = (0 until 24).toList(),
) {
    val byDayHour = cells.associateBy { it.dayOfWeek to it.hourOfDayUtc }
    val heard = cells.count { it.state == HourActivityState.HEARD }
    val silent = cells.count { it.state == HourActivityState.SILENT_WHILE_LISTENING }
    val notListening = cells.size.let { hours.size * DayOfWeek.entries.size - heard - silent }
    val summary = "Activity by hour and day of week: $heard cells heard, $silent quiet while listening, " +
        "$notListening not listening"

    Column(modifier = modifier.semantics(mergeDescendants = true) { contentDescription = summary }) {
        Row(horizontalArrangement = Arrangement.spacedBy(GRID_GAP)) {
            DayOfWeek.entries.forEach { day ->
                Column(verticalArrangement = Arrangement.spacedBy(GRID_GAP)) {
                    hours.forEach { hour -> GridCell(state = byDayHour[day to hour]?.state) }
                }
            }
        }
        Row(modifier = Modifier.padding(top = OrtSpacing.xs), horizontalArrangement = Arrangement.spacedBy(GRID_GAP)) {
            DayOfWeek.entries.forEach { day ->
                Text(
                    text = dayInitial(day),
                    style = OrtType.axis,
                    color = OrtColors.textLow,
                    modifier = Modifier.width(GRID_CELL),
                )
            }
        }
        DayOfWeekGridLegend(modifier = Modifier.padding(top = OrtSpacing.sm))
    }
}

@Composable
private fun GridCell(state: HourActivityState?, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(GRID_CELL)) {
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
                    pitchPx = 4.dp.toPx(),
                    strokeWidthPx = 1.5.dp.toPx(),
                )
            }
        }
    }
}

@Composable
private fun DayOfWeekGridLegend(modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(OrtSpacing.md)) {
        LegendSwatch(color = OrtColors.chartGreenRamp[0], label = "heard")
        LegendSwatch(color = OrtColors.chartNeutralListenedSilent, label = "quiet")
        NotListeningLegend(notListeningLabel = null)
    }
}

@Composable
private fun LegendSwatch(color: Color, label: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Canvas(modifier = Modifier.size(7.dp)) {
            drawRoundRect(color = color, cornerRadius = CornerRadius(1.5.dp.toPx()))
        }
        Text(text = label, style = OrtType.subLine, color = OrtColors.textDim)
    }
}

private fun dayInitial(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "M"
    DayOfWeek.TUESDAY -> "T"
    DayOfWeek.WEDNESDAY -> "W"
    DayOfWeek.THURSDAY -> "T"
    DayOfWeek.FRIDAY -> "F"
    DayOfWeek.SATURDAY -> "S"
    DayOfWeek.SUNDAY -> "S"
}

// ---------------------------------------------------------------------------------------------
// Sparkline — Frequencies.dc.html's per-frequency 14-night strip.
// ---------------------------------------------------------------------------------------------

/**
 * `Frequencies.dc.html`'s 56x18dp per-row sparkline: one bar per night in [nights], hatched for a
 * night the app was not listening (FR-UI-12 applied at this scale too — a not-listening night is
 * never conflated with a quiet one).
 */
@Composable
public fun Sparkline(nights: List<HourActivityState>, modifier: Modifier = Modifier) {
    val heard = nights.count { it == HourActivityState.HEARD }
    val notListening = nights.count { it == HourActivityState.NOT_LISTENING }
    val summary = "${nights.size} nights: $heard heard, $notListening not listening"
    Canvas(
        modifier = modifier.width(SPARK_WIDTH).height(SPARK_HEIGHT)
            .semantics { contentDescription = summary },
    ) {
        if (nights.isEmpty()) return@Canvas
        val gapPx = SPARK_GAP.toPx()
        val barWidth = (size.width - gapPx * (nights.size - 1)) / nights.size
        nights.forEachIndexed { index, state ->
            val x = index * (barWidth + gapPx)
            when (state) {
                HourActivityState.HEARD ->
                    drawRect(
                        color = OrtColors.chartGreenRamp[1],
                        topLeft = Offset(x, 0f),
                        size = Size(barWidth, size.height),
                    )

                HourActivityState.SILENT_WHILE_LISTENING -> {
                    val barHeight = size.height * 0.3f
                    drawRect(
                        color = OrtColors.chartNeutralRamp[2],
                        topLeft = Offset(x, size.height - barHeight),
                        size = Size(barWidth, barHeight),
                    )
                }

                HourActivityState.NOT_LISTENING ->
                    drawHatchRegion(
                        left = x,
                        top = 0f,
                        width = barWidth,
                        height = size.height,
                        color = OrtColors.hatchBar,
                        pitchPx = 3.dp.toPx(),
                        strokeWidthPx = 1.dp.toPx(),
                    )
            }
        }
    }
}
