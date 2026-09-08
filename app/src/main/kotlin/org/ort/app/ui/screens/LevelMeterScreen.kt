package org.ort.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.DrillInHeader
import org.ort.app.ui.components.FailedState
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.data.LevelViewState
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/**
 * `Level-Meter.dc.html` (ui-conformance-plan WP4, R-039; real data since R-112/WP11c added
 * [org.ort.pipeline.capture.LevelStatus]). [LevelViewState.notMeasuredReason] non-null is the one
 * case this renders [org.ort.app.ui.components.FailedState] instead of the meter — guide §6.8's
 * rule that a screen with nothing to show because a *capability is missing* is failed, not empty;
 * that case is genuinely rare now (only before the first frame of a session has been measured).
 *
 * design-intent N06: reached only as a drill-in from `Capture-Status.dc.html`'s Level row
 * (never a standalone drawer destination), so this always carries [DrillInHeader]'s own back
 * affordance — [onBack] defaults to a no-op only so a caller that has not wired real navigation
 * yet (this screen's own tests) keeps compiling.
 */
@Composable
public fun LevelMeterScreen(state: LevelViewState, modifier: Modifier = Modifier, onBack: () -> Unit = {}) {
    Column(modifier = modifier.fillMaxSize()) {
        DrillInHeader(parentLabel = "Capture", onBack = onBack, modifier = Modifier.testTag("level-meter-back"))
        LevelMeterBody(state = state, modifier = Modifier.padding(OrtSpacing.lg))
    }
}

@Composable
private fun LevelMeterBody(state: LevelViewState, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(text = "Level", style = OrtType.screenTitle, color = OrtColors.textHigh)
        Text(text = state.inputLabel, style = OrtType.subtitle, color = OrtColors.textDim)

        if (state.notMeasuredReason != null) {
            FailedState(
                title = "No level signal yet",
                body = state.notMeasuredReason,
                modifier = Modifier.testTag("level-meter-failed").padding(top = OrtSpacing.lg),
            )
        } else {
            LevelHistoryChart(
                history = state.historyDbfs,
                noiseFloorDbfs = state.noiseFloorDbfsRaw,
                modifier = Modifier
                    .padding(top = OrtSpacing.lg)
                    .fillMaxWidth()
                    .height(150.dp)
                    .testTag("level-meter-chart"),
            )

            SectionHeader(label = "Speech peaks, last 60 s", modifier = Modifier.padding(top = OrtSpacing.lg))
            LevelFact("Speech peaks", state.peakDbfsLabel, testTag = "level-meter-peak")
            LevelFact("Noise floor", state.noiseFloorDbfsLabel, testTag = "level-meter-noise-floor")
            LevelFact("Headroom", state.headroomLabel, testTag = "level-meter-headroom")
            LevelFact(
                "Clipped samples, last second",
                state.clippedLastSecondLabel,
                testTag = "level-meter-clipped",
            )
        }
    }
}

/**
 * The real 60 s peak-history bar chart: the target band (the artboard's fixed green zone between
 * [LevelViewState.TARGET_BAND_TOP_DBFS]/[LevelViewState.TARGET_BAND_BOTTOM_DBFS]), the clip line
 * at [LevelViewState.CHART_CEILING_DBFS] (0 dBFS, in `halt/text` — clipping is the one thing on
 * this chart that is never amber), a dashed noise-floor line when one has been tracked, and one
 * bar per history entry, coloured by where it falls relative to the target band — never a
 * fabricated waveform (constitution I: every bar is [LevelViewState.historyDbfs], nothing else).
 */
@Composable
private fun LevelHistoryChart(history: List<Float>, noiseFloorDbfs: Float?, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(OrtColors.bgAudio, RoundedCornerShape(8.dp))
                .border(1.dp, OrtColors.lineSection, RoundedCornerShape(8.dp)),
        ) {
            Canvas(modifier = Modifier.fillMaxSize().padding(10.dp)) {
                val ceiling = LevelViewState.CHART_CEILING_DBFS
                val floor = LevelViewState.CHART_FLOOR_DBFS
                val range = ceiling - floor
                fun yFor(dbfs: Float): Float = size.height * ((ceiling - dbfs) / range).coerceIn(0f, 1f)

                // Target band — the artboard's fixed green zone, not a measurement.
                val bandTopY = yFor(LevelViewState.TARGET_BAND_TOP_DBFS)
                val bandBottomY = yFor(LevelViewState.TARGET_BAND_BOTTOM_DBFS)
                drawRect(
                    color = OrtColors.accentGreen.copy(alpha = 0.09f),
                    topLeft = Offset(0f, bandTopY),
                    size = Size(size.width, bandBottomY - bandTopY),
                )
                drawLine(
                    color = OrtColors.accentGreen.copy(alpha = 0.5f),
                    start = Offset(0f, bandTopY),
                    end = Offset(size.width, bandTopY),
                    strokeWidth = 1.dp.toPx(),
                )
                drawLine(
                    color = OrtColors.accentGreen.copy(alpha = 0.5f),
                    start = Offset(0f, bandBottomY),
                    end = Offset(size.width, bandBottomY),
                    strokeWidth = 1.dp.toPx(),
                )

                // Clip line — 0 dBFS, halt-coloured (the one thing on this chart that is never amber).
                val clipY = yFor(LevelViewState.CHART_CEILING_DBFS)
                drawLine(
                    color = OrtColors.haltText.copy(alpha = 0.6f),
                    start = Offset(0f, clipY),
                    end = Offset(size.width, clipY),
                    strokeWidth = 1.dp.toPx(),
                )

                // The real noise-floor reading, dashed, only when one has actually been tracked.
                if (noiseFloorDbfs != null) {
                    val noiseY = yFor(noiseFloorDbfs)
                    drawLine(
                        color = OrtColors.accentGapDim,
                        start = Offset(0f, noiseY),
                        end = Offset(size.width, noiseY),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                    )
                }

                if (history.isNotEmpty()) {
                    val gapPx = 1.dp.toPx()
                    val barWidth = (size.width - gapPx * (history.size - 1)) / history.size
                    history.forEachIndexed { index, dbfs ->
                        val x = index * (barWidth + gapPx)
                        val barTopY = yFor(dbfs)
                        val color = when {
                            dbfs >= LevelViewState.TARGET_BAND_TOP_DBFS -> OrtColors.accentGreen
                            dbfs >= LevelViewState.TARGET_BAND_BOTTOM_DBFS -> OrtColors.chartGreenRamp[1]
                            else -> OrtColors.meterWarn
                        }
                        drawRect(
                            color = color,
                            topLeft = Offset(x, barTopY),
                            size = Size(barWidth, size.height - barTopY),
                        )
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = "-60 s", style = OrtType.axis, color = OrtColors.textLow)
            Text(text = "now", style = OrtType.axis, color = OrtColors.textLow)
        }
    }
}

@Composable
private fun LevelFact(label: String, value: String?, testTag: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = OrtSpacing.sm).testTag(testTag),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = OrtType.subtitle, color = OrtColors.textDim)
        Text(text = value ?: "not measured", style = OrtType.timeFreq, color = OrtColors.textBody)
    }
}
