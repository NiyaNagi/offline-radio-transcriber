package org.ort.app.ui.setup

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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.FailedState
import org.ort.app.ui.components.PrimaryButton
import org.ort.app.ui.components.referenceLineY
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
        LevelFooterFacts(
            noiseText = reading?.noiseFloorDbfs?.let { "noise %.0f".format(it).replace('-', '−') } ?: "noise —",
            modifier = Modifier.testTag("setup-level-footer-facts"),
        )

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
 * R-225 (validator pass 2): at font scale 2.0 the three facts ("noise −72", "target −18 to −12",
 * "clip 0") no longer fit one row and used to collapse into a single concatenated, unreadable run
 * (`Row` never wraps its children — confirmed on `setup/S07-level-pass2@2x.png`). Below
 * [LARGE_FONT_SCALE_THRESHOLD], the original `SpaceBetween` `Row` is unchanged; at or above it,
 * the three facts stack in a `Column` instead — never wrapped mid-run, always fully readable.
 */
@Composable
private fun LevelFooterFacts(noiseText: String, modifier: Modifier = Modifier) {
    val fontScale = LocalDensity.current.fontScale
    if (fontScale >= LARGE_FONT_SCALE_THRESHOLD) {
        Column(
            modifier = modifier.fillMaxWidth().padding(top = 7.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(text = noiseText, style = OrtType.axis, color = OrtColors.accentGapDim)
            Text(text = "target −18 to −12", style = OrtType.axis, color = OrtColors.accentGreenDim)
            Text(text = "clip 0", style = OrtType.axis, color = OrtColors.haltText)
        }
    } else {
        Row(modifier = modifier.fillMaxWidth().padding(top = 7.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = noiseText, style = OrtType.axis, color = OrtColors.accentGapDim)
            Text(text = "target −18 to −12", style = OrtType.axis, color = OrtColors.accentGreenDim)
            Text(text = "clip 0", style = OrtType.axis, color = OrtColors.haltText)
        }
    }
}

/** guide's own "2.0" ceiling (FR-A11Y-3, AC-63) — the smallest scale the footer facts are known
 * not to fit a single row at, confirmed on the validator's own `@2x` screenshot. A lower threshold
 * would stack earlier than necessary; this is not a spec-mandated figure, only where the row is
 * known to actually break. */
private const val LARGE_FONT_SCALE_THRESHOLD = 1.5f

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
            val lineStrokePx = 1.dp.toPx()
            // R-465: half the line's own stroke alone was still not enough clearance -- this
            // Box's own 1dp border is drawn *over* the Canvas's content (so it stays visible even
            // where a child fills the whole Box), and it overpainted a line inset by only half its
            // own stroke width. 1.5dp clears both: this chart's 1dp border plus the reference
            // line's own half-stroke. Confirmed on-device after raising it from 0.5dp to this.
            val lineClearPx = 1.5.dp.toPx()

            // Target band — the artboard's fixed green zone (a policy line, not a measurement).
            val bandTopY = yForReferenceLine(
                levelBarFraction(LevelViewState.TARGET_BAND_TOP_DBFS.toDouble()),
                lineClearPx,
            )
            val bandBottomY = yForReferenceLine(
                levelBarFraction(LevelViewState.TARGET_BAND_BOTTOM_DBFS.toDouble()),
                lineClearPx,
            )
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
            // R-465: CHART_CEILING_DBFS is the scale's own top, so this always lands at fraction
            // 1.0 — [yForReferenceLine] (not the unclamped mapping [levelBarRects] uses for bars)
            // keeps its full stroke inside the canvas rather than exactly on row 0, bisected by/
            // merged into this Box's own top border. See that function's own doc for the on-device
            // evidence.
            val clipY = yForReferenceLine(
                levelBarFraction(LevelViewState.CHART_CEILING_DBFS.toDouble()),
                lineClearPx,
            )
            drawLine(
                color = OrtColors.haltText.copy(alpha = 0.6f),
                start = Offset(0f, clipY),
                end = Offset(size.width, clipY),
                strokeWidth = lineStrokePx,
            )

            // The real noise floor, dashed, only once one has actually been tracked.
            reading?.noiseFloorDbfs?.let { noiseFloorDbfs ->
                val noiseY = yForReferenceLine(levelBarFraction(noiseFloorDbfs), lineClearPx)
                drawLine(
                    color = OrtColors.accentGapDim,
                    start = Offset(0f, noiseY),
                    end = Offset(size.width, noiseY),
                    strokeWidth = lineStrokePx,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                )
            }

            val bars = reading?.bars.orEmpty()
            levelBarRects(bars, size.width, size.height, gapPx = 1.5.dp.toPx()).forEach { rect ->
                drawRect(
                    color = rect.color,
                    topLeft = Offset(rect.x, rect.topY),
                    size = Size(rect.width, size.height - rect.topY),
                )
            }
        }
    }
}

/** One of [LevelMeter]'s bars, already positioned/coloured — [topY] is the top-down canvas Y
 * [levelBarRects] computed from that bar's own real fraction, never a fixed/alternating value. */
internal data class LevelBarRect(
    val x: Float,
    val width: Float,
    val topY: Float,
    val color: androidx.compose.ui.graphics.Color,
)

/**
 * R-944 (register, reviewer A3 run 4a, design): pulled out of [LevelMeter]'s own `Canvas` draw
 * scope so the property the finding names — a genuine proportional envelope from real samples,
 * never a fixed/alternating pattern regardless of what was actually measured — is directly
 * testable without a `DrawScope`/pixel comparison. Each [LevelBarRect.topY] is this bar's own real
 * [LevelReading.bars] fraction mapped onto the canvas height (`0f..1f`, floor..ceiling, unclamped
 * beyond `coerceIn` — a bar's own rectangle is meant to reach the true edge); colour comes from
 * [levelBarColorFor].
 *
 * R-944 reopened (register, reviewer A4 run 5, halt, `results/ui-audit/setup-level/S07-level.png`):
 * the original 3-way banding above (bright green at/above the target band, a fixed mid-green
 * *inside* it, [OrtColors.meterWarn] — an amber-family tone — for literally everything else, which
 * is most of any real envelope, since the target band is only 10 dB wide) rendered nearly every
 * bar amber on a genuine speech envelope, with a green top only right at the very peak. Read
 * `Setup-Level.dc.html` lines 47–64 (its own 16 illustrative bars) directly rather than
 * re-guessing: quiet bars (under roughly a third of the chart) are
 * [OrtColors.chartNeutralRamp]'s own grey, `oklch(0.42 0.04 250)`; every louder bar is a shade of
 * [OrtColors.chartGreenRamp] scaled by how close it sits to the target band (brightest —
 * [OrtColors.accentGreen] — at or above it, dimmest just past the grey floor); nothing on the
 * board is ever amber. Amber is for anomalies (guide §8) — this meter's own live status dot
 * ([colorFor]) already carries that meaning for [LevelBand.TOO_QUIET]/[LevelBand.CLIPPING]; the
 * bars themselves are never that signal. Red ([OrtColors.haltFill]) is reserved for the one real
 * anomaly a bar chart can show on its own: a sample that actually reached
 * [LevelViewState.CHART_CEILING_DBFS] (fraction `1f`, true clipping), never the target band's own
 * upper edge.
 */
internal fun levelBarRects(bars: List<Float>, widthPx: Float, heightPx: Float, gapPx: Float): List<LevelBarRect> {
    if (bars.isEmpty()) return emptyList()
    val barWidth = (widthPx - gapPx * (bars.size - 1)) / bars.size
    val bandTopFraction = levelBarFraction(LevelViewState.TARGET_BAND_TOP_DBFS.toDouble())
    return bars.mapIndexed { index, fraction ->
        val clamped = fraction.coerceIn(0f, 1f)
        LevelBarRect(
            x = index * (barWidth + gapPx),
            width = barWidth,
            topY = heightPx * (1f - clamped),
            color = levelBarColorFor(clamped, bandTopFraction),
        )
    }
}

/**
 * R-944 reopened: quiet bars (below [QUIET_BELOW_FRACTION]) are neutral grey, never amber; every
 * louder bar is a shade of [OrtColors.chartGreenRamp] — the ramp's own dimmest shade
 * ([OrtColors.chartGreenRamp]'s last entry) right at [QUIET_BELOW_FRACTION], its brightest
 * ([OrtColors.accentGreen], the ramp's first entry) at or above [bandTopFraction] — a real
 * proportional ramp by loudness, not a hand-picked step; a bar that actually reaches the chart's
 * own ceiling (`clamped >= 1f`, true 0 dBFS clipping) is the one place this chart ever draws red.
 */
internal fun levelBarColorFor(clamped: Float, bandTopFraction: Float): androidx.compose.ui.graphics.Color = when {
    clamped >= 1f -> OrtColors.haltFill
    clamped < QUIET_BELOW_FRACTION -> OrtColors.chartNeutralRamp[0]
    else -> greenRampColorFor(clamped, bandTopFraction)
}

/** Linearly ranks [clamped] between [QUIET_BELOW_FRACTION] (dimmest ramp shade) and
 * [bandTopFraction] (brightest, and everything louder still) into an index into
 * [OrtColors.chartGreenRamp] — see [levelBarColorFor]'s own doc comment. */
private fun greenRampColorFor(clamped: Float, bandTopFraction: Float): androidx.compose.ui.graphics.Color {
    val ramp = OrtColors.chartGreenRamp
    val span = (bandTopFraction - QUIET_BELOW_FRACTION).coerceAtLeast(MIN_RAMP_SPAN)
    val progress = ((clamped - QUIET_BELOW_FRACTION) / span).coerceIn(0f, 1f)
    val index = ((1f - progress) * (ramp.size - 1)).toInt().coerceIn(0, ramp.size - 1)
    return ramp[index]
}

/** Policy, not a spec-mandated figure (this file's own established style for such constants —
 * see [RealLevelCheck.TOO_QUIET_BELOW_DBFS]'s identical caveat): the fraction below which a bar
 * reads as ordinary quiet/noise-floor activity rather than speech, chosen to land between
 * `Setup-Level.dc.html`'s own illustrative grey bars (topping out around 0.24) and its green ones
 * (starting around 0.44) — report to the lead if a measured target ships later. */
private const val QUIET_BELOW_FRACTION: Float = 0.30f

/** Guards [greenRampColorFor]'s division whenever [QUIET_BELOW_FRACTION] and the target band's top
 * fraction ever end up equal or inverted (not reachable with today's real constants, but never a
 * divide-by-zero regardless). */
private const val MIN_RAMP_SPAN: Float = 0.0001f

/**
 * R-465 (device-verified, `setup-level/S07-level.png`): [levelBarRects]'s own unclamped mapping
 * alone is correct for the *scale*, but the clip line always sits at `CHART_CEILING_DBFS` —
 * fraction `1.0`, i.e. exactly
 * canvas row `0`, where it was device-confirmed genuinely invisible, not merely faint. The actual
 * inset math is [org.ort.app.ui.components.referenceLineY] — WP4 (register R-542) found the
 * byte-identical defect in its own `LevelMeterScreen.kt`, so this now calls the one shared copy
 * (`ui/components/ChartGeometry.kt`, that file's own doc has the full account) rather than a
 * second private one; `R_465` (`LevelScreenTest`) still exercises it directly at this call site.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.yForReferenceLine(
    fraction: Float,
    edgeClearancePx: Float,
): Float = referenceLineY(fraction, size.height, edgeClearancePx)

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
