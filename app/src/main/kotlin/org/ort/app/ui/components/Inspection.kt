package org.ort.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/**
 * R-023 (ui-conformance-plan WP2): `Detail.dc.html`'s "why this callsign" surface — the waveform
 * card (§6.16), the phonetic lattice slot and the per-prior contribution bar (§6.17), none of
 * which existed as reusable components before this landed.
 */

// ---------------------------------------------------------------------------------------------
// 6.16 Waveform card.
// ---------------------------------------------------------------------------------------------

/** One waveform bar: [heightFraction] in `0f..1f`, [isSpeech] selects `wave/speech` vs a quiet
 * shade when not yet played. */
public data class WaveformBar(val heightFraction: Float, val isSpeech: Boolean)

/** The four states `WaveformCard` can be in — idle, playing (with a cursor and a position),
 * no retained audio, or a playback failure. */
public sealed interface WaveformViewState {
    public data object NoAudio : WaveformViewState
    public data class Unavailable(val reason: String) : WaveformViewState
    public data class Idle(val bars: List<WaveformBar>, val durationLabel: String) : WaveformViewState
    public data class Playing(
        val bars: List<WaveformBar>,
        val cursorFraction: Float,
        val positionLabel: String,
        val durationLabel: String,
        val speedLabel: String? = null,
    ) : WaveformViewState
}

/** guide §6.16: `bg/audio`, 10px radius, a 34px round play control, a gapped bar waveform, the
 * duration in mono. Playing turns played bars `accent/green` with a `text/high` cursor. */
@Composable
public fun WaveformCard(state: WaveformViewState, modifier: Modifier = Modifier, onPlayPause: (() -> Unit)? = null) {
    when (state) {
        is WaveformViewState.NoAudio -> WaveformMessageCard(
            message = "No retained audio for this transmission",
            color = OrtColors.textMuted,
            modifier = modifier,
        )

        is WaveformViewState.Unavailable -> WaveformMessageCard(
            message = state.reason,
            color = OrtColors.accentAmberText,
            modifier = modifier,
        )

        is WaveformViewState.Idle -> WaveformCardContent(
            bars = state.bars,
            cursorFraction = null,
            positionLabel = null,
            durationLabel = state.durationLabel,
            speedLabel = null,
            onPlayPause = onPlayPause,
            modifier = modifier,
        )

        is WaveformViewState.Playing -> WaveformCardContent(
            bars = state.bars,
            cursorFraction = state.cursorFraction,
            positionLabel = state.positionLabel,
            durationLabel = state.durationLabel,
            speedLabel = state.speedLabel,
            onPlayPause = onPlayPause,
            modifier = modifier,
        )
    }
}

@Composable
private fun WaveformMessageCard(
    message: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(OrtColors.bgAudio, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Text(text = message, style = OrtType.control, color = color)
    }
}

@Composable
private fun WaveformCardContent(
    bars: List<WaveformBar>,
    cursorFraction: Float?,
    positionLabel: String?,
    durationLabel: String,
    speedLabel: String?,
    onPlayPause: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val playing = cursorFraction != null
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(OrtColors.bgAudio, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(OrtColors.accentGreen, CircleShape)
                    .then(
                        if (onPlayPause != null) {
                            Modifier
                                .clickable(role = Role.Button, onClick = onPlayPause)
                                .semantics {
                                    contentDescription = if (playing) "Pause" else "Play retained audio"
                                }
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = OrtIcons.play,
                    contentDescription = null,
                    tint = OrtColors.accentOnGreen,
                    modifier = Modifier.size(14.dp),
                )
            }
            Canvas(modifier = Modifier.weight(1f).height(26.dp)) {
                if (bars.isEmpty()) return@Canvas
                val gap = 1.5.dp.toPx()
                val barWidth = (size.width - gap * (bars.size - 1)) / bars.size
                bars.forEachIndexed { index, bar ->
                    val playedFraction = index.toFloat() / bars.size
                    val played = cursorFraction != null && playedFraction < cursorFraction
                    val color = when {
                        played -> OrtColors.accentGreen
                        bar.isSpeech -> OrtColors.waveSpeech
                        index % 2 == 0 -> OrtColors.waveformQuiet1
                        else -> OrtColors.waveformQuiet2
                    }
                    val barHeight = size.height * bar.heightFraction.coerceIn(0.08f, 1f)
                    drawRect(
                        color = color,
                        topLeft = Offset(index * (barWidth + gap), size.height - barHeight),
                        size = Size(barWidth, barHeight),
                    )
                }
                if (cursorFraction != null) {
                    val x = size.width * cursorFraction.coerceIn(0f, 1f)
                    drawLine(
                        color = OrtColors.textHigh,
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 2.dp.toPx(),
                    )
                }
            }
            Text(
                text = if (positionLabel != null) "$positionLabel / $durationLabel" else durationLabel,
                style = OrtType.subLine,
                color = OrtColors.textFaint,
            )
        }
        speedLabel?.let {
            Text(
                text = it,
                style = OrtType.subLine,
                color = OrtColors.textDim,
                modifier = Modifier.padding(top = OrtSpacing.sm),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 6.17 Lattice slot and prior bar.
// ---------------------------------------------------------------------------------------------

/** One phonetic-lattice slot: [unit] (a letter/digit), [score] in `0..1`, an optional kept
 * [alternate], and [belowThreshold] for the `lattice/uncertain` border + amber score treatment. */
public data class LatticeSlotViewState(
    val unit: String,
    val score: Double,
    val alternate: String? = null,
    val belowThreshold: Boolean = false,
)

@Composable
public fun LatticeSlot(state: LatticeSlotViewState, modifier: Modifier = Modifier) {
    val scoreColor = if (state.belowThreshold) OrtColors.accentAmber else OrtColors.scoreGood
    val description = buildString {
        append(state.unit); append(", score "); append("%.2f".format(state.score))
        if (state.belowThreshold) append(", below threshold")
        state.alternate?.let { append(", alternate "); append(it) }
    }
    Column(
        modifier = modifier
            .background(OrtColors.lineFaint, RoundedCornerShape(5.dp))
            .then(
                if (state.belowThreshold) {
                    Modifier.border(1.dp, OrtColors.latticeUncertain, RoundedCornerShape(5.dp))
                } else {
                    Modifier
                },
            )
            .padding(vertical = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = state.unit, style = OrtType.callsignCard, color = OrtColors.textHigh)
        Text(
            text = "%.2f".format(state.score).removePrefix("0"),
            style = OrtType.scoreChip,
            color = scoreColor,
            modifier = Modifier.padding(top = 2.dp),
        )
        state.alternate?.let { Text(text = it, style = OrtType.scoreChip, color = OrtColors.textLow) }
    }
}

/**
 * One prior's contribution row. [fillFraction] is null exactly for a prior that abstained (cold
 * start — no bar is drawn at all, and [valueLabel] reads "cold start", never a fabricated `0.00`,
 * constitution I). [arguedAgainst] fills from the opposite direction in `meter/warn`, distinct in
 * shape and colour from a supporting prior — never conflated with one that simply argued less.
 */
public data class PriorBarViewState(
    val name: String,
    val fillFraction: Float?,
    val valueLabel: String?,
    val arguedAgainst: Boolean = false,
)

@Composable
public fun PriorBar(state: PriorBarViewState, modifier: Modifier = Modifier) {
    val description = buildString {
        append(state.name)
        if (state.fillFraction == null) {
            append(": cold start — no prior data")
        } else {
            append(if (state.arguedAgainst) ": argued against, " else ": ")
            state.valueLabel?.let { append(it) }
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = state.name,
            style = OrtType.cardBody,
            color = if (state.arguedAgainst) OrtColors.textDim else OrtColors.textPrior,
            modifier = Modifier.widthIn(min = 118.dp, max = 122.dp),
        )
        Box(modifier = Modifier.weight(1f).height(5.dp).background(OrtColors.bgScore, RoundedCornerShape(3.dp))) {
            if (state.fillFraction != null) {
                val fraction = state.fillFraction.coerceIn(0f, 1f)
                val color = if (state.arguedAgainst) OrtColors.meterWarn else OrtColors.accentGreen
                val alignment = if (state.arguedAgainst) Alignment.CenterEnd else Alignment.CenterStart
                Box(
                    modifier = Modifier
                        .align(alignment)
                        .fillMaxHeight()
                        .fillMaxWidth(fraction)
                        .background(color, RoundedCornerShape(3.dp)),
                )
            }
        }
        if (state.valueLabel != null) {
            Text(
                text = state.valueLabel,
                style = OrtType.signal,
                color = if (state.arguedAgainst) OrtColors.accentAmberText else OrtColors.textDim,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(min = 30.dp, max = 34.dp),
            )
        } else {
            Text(
                text = "cold start",
                style = OrtType.signal.copy(fontStyle = FontStyle.Italic),
                color = OrtColors.textLow,
            )
        }
    }
}
