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
import androidx.compose.foundation.layout.widthIn
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

/** R-209 (`Station-Pattern.dc.html`'s `.dl` class): the day-label column's floor — a mono 3-letter
 * abbreviation ("Mon".."Sun") in [DayOfWeekGridOrientation.DaysAsRows], never a hard `width()`
 * that content could someday outgrow (the exact class of bug R-152/R-205 already found and fixed
 * elsewhere in this package's row family). */
private val DAY_LABEL_MIN_WIDTH = 28.dp

/** R-209: the board labels the top hour axis sparsely (every sixth hour — "12", "18", "00" for
 * the default 24-hour range), not one label per column, which twenty-four 16dp-ish columns has no
 * room for. */
private const val HOUR_LABEL_STRIDE = 6
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
    // R-072: null draws no title row at all (rather than every caller with nothing to say having
    // to pass ""). The default keeps every existing caller's title unchanged.
    title: String? = "ACTIVITY BY HOUR (UTC)",
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
        title?.let { Text(text = it, style = OrtType.sectionLabel, color = OrtColors.textFaint) }
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

/** [DayOfWeekGrid]'s layout — R-209: the board (`Station-Pattern.dc.html`) lays days out as
 * rows, Mon..Sun top to bottom, a mono day abbreviation on the left of each, with an hour axis
 * along the top; the grid this component drew before that finding was transposed (hours as rows,
 * days as columns, no hour axis at all — [HoursAsRows], below). [DaysAsRows] is now the default,
 * matching the board; [HoursAsRows] is kept, unrenamed in its own behaviour, for any caller that
 * still needs the previous layout — this repository has exactly one caller today
 * ([org.ort.app.ui.screens.StationPatternScreen], outside this package) and it takes the default,
 * so this alone corrects it with no call-site change. */
public enum class DayOfWeekGridOrientation { DaysAsRows, HoursAsRows }

/**
 * `Station-Pattern.dc.html`'s hour x day grid: 16dp cells on a 3dp gap, the same three-way
 * distinction [ActivityPatternChart] draws (heard / listened-but-silent / not-listening, the
 * last always hatched — FR-UI-12), and [orientation] deciding which axis is which (see its own
 * doc). An (day, hour) pair absent from [cells] renders as not-listening, exactly as an untouched
 * hour does in [org.ort.app.ui.data.ActivityPatternMapper] — absence of data and absence of
 * listening read identically on purpose (constitution I).
 */
@Composable
public fun DayOfWeekGrid(
    cells: List<DayHourCell>,
    modifier: Modifier = Modifier,
    hours: List<Int> = (0 until 24).toList(),
    orientation: DayOfWeekGridOrientation = DayOfWeekGridOrientation.DaysAsRows,
) {
    val byDayHour = cells.associateBy { it.dayOfWeek to it.hourOfDayUtc }
    val heard = cells.count { it.state == HourActivityState.HEARD }
    val silent = cells.count { it.state == HourActivityState.SILENT_WHILE_LISTENING }
    val notListening = cells.size.let { hours.size * DayOfWeek.entries.size - heard - silent }
    val summary = "Activity by hour and day of week: $heard cells heard, $silent quiet while listening, " +
        "$notListening not listening"

    Column(modifier = modifier.semantics(mergeDescendants = true) { contentDescription = summary }) {
        when (orientation) {
            DayOfWeekGridOrientation.DaysAsRows -> DaysAsRowsGrid(hours = hours, byDayHour = byDayHour)
            DayOfWeekGridOrientation.HoursAsRows -> HoursAsRowsGrid(hours = hours, byDayHour = byDayHour)
        }
        DayOfWeekGridLegend(modifier = Modifier.padding(top = OrtSpacing.sm))
    }
}

@Composable
private fun DaysAsRowsGrid(hours: List<Int>, byDayHour: Map<Pair<DayOfWeek, Int>, DayHourCell>) {
    // Aligns the hour axis past the day-label column below (`Station-Pattern.dc.html`'s own
    // `padding-left` on its axis row, for the same reason).
    Row(
        modifier = Modifier.padding(start = DAY_LABEL_MIN_WIDTH + GRID_GAP),
        horizontalArrangement = Arrangement.spacedBy(GRID_GAP),
    ) {
        hours.forEachIndexed { index, hour ->
            Box(modifier = Modifier.weight(1f)) {
                if (index % HOUR_LABEL_STRIDE == 0) {
                    Text(text = hourAxisLabel(hour), style = OrtType.axis, color = OrtColors.textLow)
                }
            }
        }
    }
    Column(
        modifier = Modifier.padding(top = OrtSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(GRID_GAP),
    ) {
        DayOfWeek.entries.forEach { day ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GRID_GAP)) {
                Text(
                    text = dayAbbreviation(day),
                    style = OrtType.axis,
                    color = OrtColors.textLow,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.widthIn(min = DAY_LABEL_MIN_WIDTH),
                )
                hours.forEach { hour ->
                    GridCell(state = byDayHour[day to hour]?.state, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun HoursAsRowsGrid(hours: List<Int>, byDayHour: Map<Pair<DayOfWeek, Int>, DayHourCell>) {
    Row(horizontalArrangement = Arrangement.spacedBy(GRID_GAP)) {
        DayOfWeek.entries.forEach { day ->
            Column(verticalArrangement = Arrangement.spacedBy(GRID_GAP)) {
                hours.forEach { hour ->
                    GridCell(state = byDayHour[day to hour]?.state, modifier = Modifier.width(GRID_CELL))
                }
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
}

@Composable
private fun GridCell(state: HourActivityState?, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.height(GRID_CELL)) {
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

/** R-209 (`Station-Pattern.dc.html`'s exact legend copy): the low/neutral swatch reads "listened,
 * not heard" (was "quiet" — a word the board never uses here), and the green swatch reads "heard
 * often" (was "heard" — this grid's green is the *frequent*-contact end of the ramp
 * [ActivityPatternChart] itself draws with several steps, not a plain yes/no). The hatch stays
 * "not listening", unchanged — that wording was already right. */
@Composable
private fun DayOfWeekGridLegend(modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(OrtSpacing.md)) {
        LegendSwatch(color = OrtColors.chartNeutralListenedSilent, label = "listened, not heard")
        LegendSwatch(color = OrtColors.chartGreenRamp[0], label = "heard often")
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

/** R-209 (`Station-Pattern.dc.html`'s `.dl` class, "Mon".."Sun"): [DayOfWeekGridOrientation
 * .DaysAsRows]'s row label — a 3-letter abbreviation, not [dayInitial]'s single letter, because a
 * whole column of "M"/"T"/"W"/"T"/"F"/"S"/"S" running down the left edge is ambiguous in exactly
 * the way a row of single-letter day labels always is. */
private fun dayAbbreviation(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "Mon"
    DayOfWeek.TUESDAY -> "Tue"
    DayOfWeek.WEDNESDAY -> "Wed"
    DayOfWeek.THURSDAY -> "Thu"
    DayOfWeek.FRIDAY -> "Fri"
    DayOfWeek.SATURDAY -> "Sat"
    DayOfWeek.SUNDAY -> "Sun"
}

/** R-209: the top hour-axis label for one hour value, `Station-Pattern.dc.html`'s "00"/"12"/"18"
 * two-digit 24-hour form. */
private fun hourAxisLabel(hour: Int): String = hour.toString().padStart(2, '0')

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
