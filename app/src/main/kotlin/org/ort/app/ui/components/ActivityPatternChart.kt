package org.ort.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.ort.app.ui.data.HourActivityBucket
import org.ort.app.ui.data.HourActivityState
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/**
 * FR-UI-11's activity-by-hour pattern (`design/canvas/Timeline.dc.html`'s density strip), reused
 * on the station and frequency views build-plan P17 owns. Every bucket's [HourActivityState] is
 * rendered with both a distinct **colour and shape** (FR-A11Y-1/AC-62's "distinguishable without
 * colour", applied here the same way [org.ort.app.ui.components.AttributionMarker] applies it to
 * attribution): [HourActivityState.HEARD] is a solid bar scaled by how much was heard,
 * [HourActivityState.SILENT_WHILE_LISTENING] a short, dim bar, and — the requirement this
 * component exists to make impossible to miss (FR-UI-12) — [HourActivityState.NOT_LISTENING] is a
 * full-height **hatched** bar, textured rather than merely coloured differently, so it cannot be
 * mistaken for "quiet" at a glance or by a screen reader.
 */
@Composable
public fun ActivityPatternChart(pattern: List<HourActivityBucket>, modifier: Modifier = Modifier) {
    val heardHours = pattern.count { it.state == HourActivityState.HEARD }
    val silentHours = pattern.count { it.state == HourActivityState.SILENT_WHILE_LISTENING }
    val notListeningHours = pattern.count { it.state == HourActivityState.NOT_LISTENING }
    val maxHeardCount = pattern.maxOfOrNull { it.heardCount } ?: 0
    val summary = "Activity by hour of day: $heardHours hours heard, $silentHours hours quiet " +
        "while listening, $notListeningHours hours not listening"

    Column(modifier = modifier.semantics(mergeDescendants = true) { contentDescription = summary }) {
        Text(
            text = "ACTIVITY BY HOUR (UTC)",
            style = OrtType.sectionLabel,
            color = OrtColors.textMuted,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = OrtSpacing.sm)
                .height(34.dp),
        ) {
            pattern.sortedBy { it.hourOfDayUtc }.forEach { bucket ->
                HourBar(bucket = bucket, maxHeardCount = maxHeardCount, modifier = Modifier.weight(1f))
            }
        }
        if (notListeningHours > 0) {
            Text(
                text = "▨ not listening",
                style = OrtType.caption,
                color = OrtColors.accentAmber,
                modifier = Modifier.padding(top = OrtSpacing.xs),
            )
        }
    }
}

@Composable
private fun HourBar(bucket: HourActivityBucket, maxHeardCount: Int, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxWidth().height(34.dp).padding(horizontal = 1.dp)) {
        val width = size.width
        val height = size.height
        when (bucket.state) {
            HourActivityState.HEARD -> {
                val intensity = if (maxHeardCount > 0) bucket.heardCount.toFloat() / maxHeardCount else 1f
                val barHeight = height * (0.25f + 0.75f * intensity)
                drawRect(
                    color = OrtColors.accentGreen,
                    topLeft = Offset(0f, height - barHeight),
                    size = androidx.compose.ui.geometry.Size(width, barHeight),
                )
            }

            HourActivityState.SILENT_WHILE_LISTENING -> {
                val barHeight = height * 0.12f
                drawRect(
                    color = OrtColors.textLow,
                    topLeft = Offset(0f, height - barHeight),
                    size = androidx.compose.ui.geometry.Size(width, barHeight),
                )
            }

            HourActivityState.NOT_LISTENING -> {
                // Full-height, textured (diagonal-stripe) bar — never just a colour swatch, so
                // "not listening" reads as structurally different even without colour (FR-UI-12).
                clipRect {
                    var x = -height
                    val stripeGap = 5.dp.toPx()
                    while (x < width) {
                        drawLine(
                            color = OrtColors.accentAmber,
                            start = Offset(x, height),
                            end = Offset(x + height, 0f),
                            strokeWidth = 2.dp.toPx(),
                        )
                        x += stripeGap
                    }
                }
            }
        }
    }
}
