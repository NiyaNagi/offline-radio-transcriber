package org.ort.app.ui.failures

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.Banner
import org.ort.app.ui.components.BannerTone
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtType

/**
 * F22 — `Fail-Calibration.dc.html`. WP11b: a standalone screen (the model's confidence scores run
 * high against the corrections logged). **No runtime signal today** — a calibration-refit
 * pipeline is unbuilt; see [DebugFailureOverride]'s kdoc. [ReliabilityChart] is a lightweight
 * reading of the board's own scatter — a diagonal reference line plus one dot per point, never a
 * fabricated curve fit.
 */
@Composable
public fun FailCalibrationScreen(state: CalibrationViewState, onInstall: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OrtColors.bgScreen)
            .failureScreenInset()
            .verticalScroll(rememberScrollState())
            .testTag("failure-calibration-screen"),
    ) {
        Text(
            text = "Confidence is miscalibrated",
            style = OrtType.screenTitle,
            color = OrtColors.textHigh,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
        Text(
            text = "Since ${state.sinceLabel} · a calibration asset fixes it, not an app update",
            style = OrtType.subtitle,
            color = OrtColors.textDim,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Banner(
            title = "Scores of ${state.scoreLabel} are right about ${state.accuracyLabel} of the time",
            body = "Your ${state.correctionsCount} corrections say the model's scores run high against the " +
                "installed calibration. The states are still honest — a confirmed over is still confirmed — " +
                "but the number beside an inferred callsign is optimistic.",
            tone = BannerTone.DEGRADED,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
        )
        SectionLabel("Reliability · your corrections vs the score", modifier = Modifier.padding(horizontal = 20.dp))
        ReliabilityChart(
            points = state.points,
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .fillMaxWidth()
                .height(110.dp)
                .testTag("failure-calibration-chart"),
        )
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.End) {
            Text(text = "score →", style = OrtType.axis, color = OrtColors.textLow)
        }
        Text(
            text = "Dots below the diagonal are over-confident. The high-score end is where it drifted.",
            style = OrtType.subLine,
            color = OrtColors.textFaint,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
        )
        SectionLabel("Fix", modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
        Text(
            text = "Install calibration ${state.calibrationVersion} — takes effect next session, scores on " +
                "every existing over are re-mapped, states untouched.",
            style = OrtType.control,
            color = OrtColors.textHigh,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        TextAction(
            text = "Install",
            onClick = onInstall,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp).testTag("failure-calibration-install"),
        )
        Text(
            text = "Refit from your corrections — ${state.correctionsCount} is too few, needs about " +
                "${state.correctionsNeeded}. The app will offer this once it has them.",
            style = OrtType.cardBody,
            color = OrtColors.textDim,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
        Text(
            text = "The four states are thresholds on a calibrated probability, so miscalibration cannot " +
                "make an inferred over read as confirmed. What it can do is make the score chip overstate " +
                "— and this notice exists so you know to trust the shape more than the number until it is " +
                "fixed.",
            style = OrtType.cardBody,
            color = OrtColors.textDim,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp).testTag("failure-calibration-closing"),
        )
    }
}

@Composable
private fun ReliabilityChart(points: List<Pair<Float, Float>>, modifier: Modifier = Modifier) {
    val description = "Reliability chart: ${points.size} points, actually-right against score"
    Box(
        modifier = modifier
            .background(OrtColors.bgScreen, RoundedCornerShape(4.dp))
            .border(1.dp, OrtColors.lineDefault, RoundedCornerShape(4.dp))
            .semantics { contentDescription = description },
    ) {
        Text(
            text = "actually right",
            style = OrtType.axis,
            color = OrtColors.textLow,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 4.dp, top = 2.dp),
        )
        Canvas(modifier = Modifier.fillMaxWidth().height(96.dp).padding(8.dp)) {
            val w = size.width
            val h = size.height
            drawLine(
                color = OrtColors.lineHandle,
                start = Offset(0f, h),
                end = Offset(w, 0f),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)),
            )
            points.forEach { (score, actuallyRight) ->
                val overConfident = actuallyRight < score
                val color = if (overConfident) OrtColors.accentAmber else OrtColors.accentGreen
                drawCircle(
                    color = color,
                    radius = 4.dp.toPx(),
                    center = Offset(x = score.coerceIn(0f, 1f) * w, y = h - actuallyRight.coerceIn(0f, 1f) * h),
                )
            }
        }
    }
}
