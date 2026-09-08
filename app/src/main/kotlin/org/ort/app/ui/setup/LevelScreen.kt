package org.ort.app.ui.setup

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import org.ort.app.ui.components.FailedState
import org.ort.app.ui.components.PrimaryButton
import org.ort.app.ui.data.LevelViewState
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtType

/**
 * S07 (`Setup-Level.dc.html`, R-082) — the live 60 s meter driven by [LevelCheck]. `Continue`
 * enables only [LevelBand.IN_BAND] (guide §6.10). No level signal at all (open failed, or nothing
 * was ever read) renders an honest "cannot measure" [FailedState] instead of fabricated bars
 * (guide §6.8, constitution I) — never a fake meter.
 */
@Composable
public fun LevelScreen(state: LevelCheckState?, onContinue: () -> Unit) {
    val reading = (state as? LevelCheckState.Reading)?.level
    SetupScaffold(
        step = SetupStep.LEVEL,
        title = "Level",
        subtitle = "Set the radio's volume so speech sits in the band, above the noise",
        onBack = null,
        bottomActions = {
            PrimaryButton(
                text = "Continue",
                onClick = onContinue,
                enabled = reading?.band == LevelBand.IN_BAND,
                modifier = Modifier.fillMaxWidth().testTag("setup-level-continue"),
            )
        },
    ) {
        if (state is LevelCheckState.Unavailable) {
            FailedState(
                title = "Cannot measure the level",
                body = state.reason,
                modifier = Modifier.testTag("setup-level-unavailable"),
            )
            return@SetupScaffold
        }

        LevelMeter(reading = reading, modifier = Modifier.testTag("setup-level-meter"))
        Row(modifier = Modifier.fillMaxWidth().padding(top = 7.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                // "−" (U+2212 minus sign), matching "target −18 to −12"/"clip 0" beside it and
                // `Setup-Level.dc.html`'s own typography -- never a plain hyphen-minus here.
                text = reading?.noiseFloorDbfs?.let { "noise %.0f".format(it).replace('-', '−') } ?: "noise —",
                style = OrtType.axis,
                color = OrtColors.accentGapDim,
            )
            Text(text = "target −18 to −12", style = OrtType.axis, color = OrtColors.accentGreenDim)
            Text(text = "clip 0", style = OrtType.axis, color = OrtColors.haltText)
        }

        LevelRow(
            label = "Speech peaks",
            value = reading?.let { "%.0f dBFS".format(it.peakDbfs) } ?: "—",
            testTag = "setup-level-peaks",
        )
        LevelRow(
            label = "Noise floor",
            value = reading?.noiseFloorDbfs?.let { "%.0f dBFS".format(it) } ?: "—",
            testTag = "setup-level-noise-floor",
        )
        LevelRow(
            label = "Headroom",
            value = reading?.let { "%.0f dB".format(it.headroomDb) } ?: "—",
            testTag = "setup-level-headroom",
        )

        reading?.let {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .background(colorFor(it.band), CircleShape),
                )
                Text(
                    text = messageFor(it.band),
                    style = OrtType.subtitle,
                    color = OrtColors.textBody,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        Text(
            text = "The level is set on the radio, not here — the app only reads it. Too quiet " +
                "loses the weak signals; clipping loses the strong ones.",
            style = OrtType.cardBody,
            color = OrtColors.textDim,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

/**
 * The real 60 s bar meter — target band, clip line and dashed noise-floor line drawn against the
 * exact same `-60..0` dBFS scale [levelBarFraction] now uses (register R-120..R-125 follow-up),
 * mirroring `LevelMeterScreen`'s own `LevelHistoryChart` (`ui/screens/LevelMeterScreen.kt`, WP4 —
 * read, not imported: that composable is `private` to its own file, so this is a second, small
 * Canvas built to the same visual spec rather than a shared one, but the *scale* itself is the one
 * true source, [LevelViewState]'s constants, not a re-derived guess). [reading] is `null` before
 * the first sample and the chart then draws marks-only (target band/clip/noise skipped when there
 * is no noise floor yet) — never a fabricated bar (constitution I).
 */
@Composable
private fun LevelMeter(reading: LevelReading?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(96.dp)
            .background(OrtColors.bgPage, RoundedCornerShape(8.dp))
            .border(1.dp, OrtColors.lineSection, RoundedCornerShape(8.dp)),
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            // Target band — the artboard's fixed green zone (a policy line, not a measurement).
            val bandTopY = yForFraction(levelBarFraction(LevelViewState.TARGET_BAND_TOP_DBFS.toDouble()))
            val bandBottomY = yForFraction(levelBarFraction(LevelViewState.TARGET_BAND_BOTTOM_DBFS.toDouble()))
            drawRect(
                color = OrtColors.accentGreen.copy(alpha = 0.10f),
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

            // Clip line — 0 dBFS. Halt-coloured always (WP4's own rule: never amber, the one
            // thing on this chart that stays red even though the live status dot below is amber).
            val clipY = yForFraction(levelBarFraction(LevelViewState.CHART_CEILING_DBFS.toDouble()))
            drawLine(
                color = OrtColors.haltText.copy(alpha = 0.6f),
                start = Offset(0f, clipY),
                end = Offset(size.width, clipY),
                strokeWidth = 1.dp.toPx(),
            )

            // The real noise floor, dashed, only once one has actually been tracked.
            reading?.noiseFloorDbfs?.let { noiseFloorDbfs ->
                val noiseY = yForFraction(levelBarFraction(noiseFloorDbfs))
                drawLine(
                    color = OrtColors.accentGapDim,
                    start = Offset(0f, noiseY),
                    end = Offset(size.width, noiseY),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                )
            }

            val bars = reading?.bars.orEmpty()
            if (bars.isNotEmpty()) {
                val gapPx = 1.5.dp.toPx()
                val barWidth = (size.width - gapPx * (bars.size - 1)) / bars.size
                val bandTopFraction = levelBarFraction(LevelViewState.TARGET_BAND_TOP_DBFS.toDouble())
                val bandBottomFraction = levelBarFraction(LevelViewState.TARGET_BAND_BOTTOM_DBFS.toDouble())
                bars.forEachIndexed { index, fraction ->
                    val clamped = fraction.coerceIn(0f, 1f)
                    val x = index * (barWidth + gapPx)
                    val barTopY = yForFraction(clamped)
                    val color = when {
                        clamped >= bandTopFraction -> OrtColors.accentGreen
                        clamped >= bandBottomFraction -> OrtColors.chartGreenRamp[1]
                        else -> OrtColors.meterWarn
                    }
                    drawRect(color = color, topLeft = Offset(x, barTopY), size = Size(barWidth, size.height - barTopY))
                }
            }
        }
    }
}

/** `0f..1f` (floor..ceiling, [levelBarFraction]'s own direction) to a top-down canvas Y. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.yForFraction(fraction: Float): Float =
    size.height * (1f - fraction.coerceIn(0f, 1f))

@Composable
private fun LevelRow(label: String, value: String, testTag: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .testTag(testTag),
    ) {
        Text(text = label, style = OrtType.subtitle, color = OrtColors.textDim, modifier = Modifier.weight(1f))
        Text(
            text = value,
            style = OrtType.timeFreq.copy(fontSize = OrtType.control.fontSize),
            color = OrtColors.textBody,
        )
    }
}

/**
 * Validator finding (register R-120..R-125 follow-up, guide §3): "red is for one thing — capture
 * has stopped and the operator must act. Every degradation is amber." Clipping during setup is a
 * degradation (the operator has not started capture yet; turning the radio down fixes it) — it is
 * never [OrtColors.haltFill]. The chart's own clip *line* stays halt-coloured deliberately (see
 * [LevelMeter]'s doc comment); only this live status dot, which reports current state rather than
 * a fixed reference mark, changes.
 */
private fun colorFor(band: LevelBand) = when (band) {
    LevelBand.IN_BAND -> OrtColors.accentGreen
    LevelBand.TOO_QUIET -> OrtColors.accentAmber
    LevelBand.CLIPPING -> OrtColors.accentAmber
}

private fun messageFor(band: LevelBand) = when (band) {
    LevelBand.IN_BAND -> "In the band. Leave the radio's volume where it is."
    LevelBand.TOO_QUIET -> "Too quiet — turn the radio's volume up."
    LevelBand.CLIPPING -> "Clipping — turn the radio's volume down."
}
