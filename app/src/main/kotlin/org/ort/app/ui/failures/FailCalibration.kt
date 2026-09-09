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
import org.ort.app.ui.components.DrillInHeader
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtType

/**
 * F22 — `Fail-Calibration.dc.html`. WP11b: a standalone screen (the model's confidence scores run
 * high against the corrections logged). **No runtime signal today** — a calibration-refit
 * pipeline is unbuilt; see [DebugFailureOverride]'s kdoc. [ReliabilityChart] is a lightweight
 * reading of the board's own scatter — a diagonal reference line plus one dot per point, never a
 * fabricated curve fit.
 *
 * Register R-448: the board's own "‹ Models and lexicon" back header. Unlike F19/F21, this screen
 * had *no* existing dismiss at all before this (only [onInstall]) — [onBack] is new, defaulted to
 * a no-op so every existing caller keeps compiling unchanged, matching the same documented-stub
 * pattern `FailureHost.kt`'s own `TakeoverOrScreen` already uses for [onInstall]. No
 * `FailureHostActions` callback maps to "Models and lexicon" navigation specifically today —
 * reported, not fabricated.
 */
@Composable
public fun FailCalibrationScreen(
    state: CalibrationViewState,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OrtColors.bgScreen)
            .failureScreenInset()
            .verticalScroll(rememberScrollState())
            .testTag("failure-calibration-screen"),
    ) {
        DrillInHeader(
            parentLabel = "Models and lexicon",
            onBack = onBack,
            modifier = Modifier.testTag("failure-calibration-back"),
        )
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
        ReliabilitySection(points = state.points)
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

/** The chart, its "score →" axis label and its "Dots below the diagonal…" caption — pulled out of
 * [FailCalibrationScreen] purely to keep that function under detekt's `LongMethod` (register
 * R-448's new header pushed it over). */
@Composable
private fun ReliabilitySection(points: List<Pair<Float, Float>>, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        SectionLabel("Reliability · your corrections vs the score", modifier = Modifier.padding(horizontal = 20.dp))
        ReliabilityChart(
            points = points,
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
    }
}

/** Register R-447: `Fail-Calibration.dc.html`'s own five example points are *all* numerically
 * below the diagonal (`actuallyRight < score` for every one — even its two green, well-calibrated
 * low-score dots, by 0.08 and 0.12), yet the board colours only the three whose deviation is
 * larger (0.18, 0.20, 0.20) amber — "the high-score end is where it drifted" is a claim about a
 * *real* drift, not about every point sitting a hair off the line. A bare `actuallyRight < score`
 * check coloured every one of the board's own five points amber, which is exactly what the review
 * caught. This is the board's own real threshold, not a rounder guess: strictly between its
 * largest "still green" deviation (0.12) and its smallest "amber" one (0.18). */
private const val MISCALIBRATION_THRESHOLD = 0.15f

/** Pulled out of [ReliabilityChart]'s `Canvas` draw scope so `R_447` can assert the colour
 * decision directly — a `Canvas` draws raw pixels, not semantics nodes a Compose UI test can
 * query per dot, so the decision itself is what gets tested, not the drawn colour. */
internal fun isOverConfident(score: Float, actuallyRight: Float): Boolean =
    (score - actuallyRight) >= MISCALIBRATION_THRESHOLD

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
                val color = if (isOverConfident(score, actuallyRight)) OrtColors.accentAmber else OrtColors.accentGreen
                drawCircle(
                    color = color,
                    radius = 4.dp.toPx(),
                    center = Offset(x = score.coerceIn(0f, 1f) * w, y = h - actuallyRight.coerceIn(0f, 1f) * h),
                )
            }
        }
    }
}
