package org.ort.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.AttributionMarker
import org.ort.app.ui.components.DrillInHeader
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.data.FrequencyChangeCause
import org.ort.app.ui.data.FrequencyChangeViewState
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType
import org.ort.core.Attribution

private val CHART_HEIGHT = 54.dp

/**
 * `Frequency-Change.dc.html` (R-074, FR-UI-11, FR-DIG): tonight's per-hour bars over the usual
 * per-hour average as a line, the causes this package can honestly derive (a first-time station,
 * unidentified weak activity), and the `The N overs` / thread actions. Only reached when
 * [org.ort.app.ui.data.NightlyDeparture.isBusierThanUsual] holds for tonight — a departure is a
 * finding, not an alarm (this screen's own closing sentence).
 */
@Composable
public fun FrequencyChangeScreen(
    state: FrequencyChangeViewState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onViewOvers: () -> Unit = {},
    onViewThread: (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxSize()) {
        DrillInHeader(parentLabel = state.label, onBack = onBack)
        Column(modifier = Modifier.padding(horizontal = OrtSpacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AttributionMarker(attribution = Attribution.ambiguous(), showConfidence = false)
                Text(
                    text = "Busier than usual",
                    style = OrtType.screenTitle,
                    modifier = Modifier.padding(start = OrtSpacing.sm).semantics { heading() },
                )
            }
            Text(
                text = state.subtitleLabel,
                style = OrtType.subtitle,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.md),
            )

            SectionHeader(label = "Tonight against a typical night")
            DepartureChart(
                tonightHourly = state.tonightHourly,
                usualHourly = state.usualHourly,
                modifier = Modifier.padding(top = OrtSpacing.sm),
            )

            if (state.causes.isNotEmpty()) {
                SectionHeader(label = "What made it busy", modifier = Modifier.padding(top = OrtSpacing.lg))
                Column {
                    state.causes.forEach { cause -> CauseRow(cause) }
                }
            }

            Text(
                text = state.explanationSentence,
                style = OrtType.cardBody,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.md),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(OrtSpacing.lg),
            horizontalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
        ) {
            TextAction(text = "The ${state.overCount} overs", onClick = onViewOvers)
            if (onViewThread != null) {
                TextAction(text = "The activation thread", onClick = onViewThread)
            }
        }
    }
}

@Composable
private fun DepartureChart(tonightHourly: List<Int>, usualHourly: List<Double>, modifier: Modifier = Modifier) {
    val maxValue = (tonightHourly.maxOrNull() ?: 0).coerceAtLeast(1)
    val description = "Tonight's hourly overs plotted over the usual average"
    Canvas(
        modifier = modifier.fillMaxWidth().height(CHART_HEIGHT)
            .semantics { contentDescription = description },
    ) {
        if (tonightHourly.isEmpty()) return@Canvas
        val barGap = 1.5.dp.toPx()
        val barWidth = (size.width - barGap * (tonightHourly.size - 1)) / tonightHourly.size
        tonightHourly.forEachIndexed { index, count ->
            val x = index * (barWidth + barGap)
            val barHeight = size.height * (count.toFloat() / maxValue)
            val color = if (count.toFloat() / maxValue > 0.6f) OrtColors.accentAmber else OrtColors.chartNeutralRamp[1]
            drawRect(color = color, topLeft = Offset(x, size.height - barHeight), size = Size(barWidth, barHeight))
        }
        val usualMax = (usualHourly.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
        val points = usualHourly.mapIndexed { index, value ->
            val x = index * (barWidth + barGap) + barWidth / 2
            val y = size.height - (size.height * (value / usualMax)).toFloat()
            Offset(x, y)
        }
        for (i in 0 until points.size - 1) {
            drawLine(
                color = OrtColors.accentGreenDim,
                start = points[i],
                end = points[i + 1],
                strokeWidth = 1.5.dp.toPx(),
            )
        }
    }
}

@Composable
private fun CauseRow(cause: FrequencyChangeCause, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = OrtSpacing.xs)
            .semantics(mergeDescendants = true) { contentDescription = cause.label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val attribution = if (cause.isUnidentified) Attribution.unknown() else Attribution.confirmed("x", 1.0)
        AttributionMarker(attribution = attribution, showConfidence = false)
        Text(
            text = cause.label,
            style = OrtType.subtitle,
            color = if (cause.isUnidentified) OrtColors.textDim else OrtColors.textBody,
            modifier = Modifier.padding(start = OrtSpacing.sm),
        )
    }
}
