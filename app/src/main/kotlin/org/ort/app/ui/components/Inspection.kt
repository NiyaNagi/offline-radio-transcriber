package org.ort.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
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

/** R-054: a tap/drag x position within a [width]-wide waveform, as a `0f..1f` fraction —
 * `internal` (not `private`) so it is directly unit-testable without needing synthetic touch
 * injection to exercise it. A non-positive [width] (not yet laid out) reads as 0f rather than
 * dividing by zero. */
internal fun scrubFraction(x: Float, width: Float): Float = if (width <= 0f) 0f else (x / width).coerceIn(0f, 1f)

/** guide §6.16: `bg/audio`, 10px radius, a 34px round play control, a gapped bar waveform, the
 * duration in mono. Playing turns played bars `accent/green` with a `text/high` cursor. */
@Composable
public fun WaveformCard(
    state: WaveformViewState,
    modifier: Modifier = Modifier,
    onPlayPause: (() -> Unit)? = null,
    // R-054: a fraction 0f..1f from a tap or horizontal drag on the waveform itself, so playback
    // can seek (WP6's player has `seekToFraction`). Null (default) keeps the waveform static —
    // additive, no existing caller is affected.
    onScrub: ((Float) -> Unit)? = null,
) {
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
            onScrub = onScrub,
            modifier = modifier,
        )

        is WaveformViewState.Playing -> WaveformCardContent(
            bars = state.bars,
            cursorFraction = state.cursorFraction,
            positionLabel = state.positionLabel,
            durationLabel = state.durationLabel,
            speedLabel = state.speedLabel,
            onPlayPause = onPlayPause,
            onScrub = onScrub,
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
private fun WaveformPlayControl(playing: Boolean, onPlayPause: (() -> Unit)?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(34.dp)
            .background(OrtColors.accentGreen, CircleShape)
            .then(
                if (onPlayPause != null) {
                    Modifier
                        .clickable(role = Role.Button, onClick = onPlayPause)
                        .semantics { contentDescription = if (playing) "Pause" else "Play retained audio" }
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
}

/** R-054: the waveform's own tap-to-seek and drag-to-scrub gesture handling, a no-op [Modifier]
 * when [onScrub] is null. */
private fun Modifier.waveformScrub(onScrub: ((Float) -> Unit)?): Modifier {
    if (onScrub == null) return this
    return this
        .pointerInput(onScrub) {
            detectTapGestures { offset -> onScrub(scrubFraction(offset.x, size.width.toFloat())) }
        }
        .pointerInput(onScrub) {
            detectDragGestures { change, _ ->
                change.consume()
                onScrub(scrubFraction(change.position.x, size.width.toFloat()))
            }
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
    onScrub: ((Float) -> Unit)?,
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
            WaveformPlayControl(playing = playing, onPlayPause = onPlayPause)
            Canvas(modifier = Modifier.weight(1f).height(26.dp).waveformScrub(onScrub)) {
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
        append(state.unit)
        append(", score ")
        append("%.2f".format(state.score))
        if (state.belowThreshold) append(", below threshold")
        state.alternate?.let {
            append(", alternate ")
            append(it)
        }
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

/** R-323: past this font scale, `PriorBar`'s own 118–122dp label column is narrower than a typical
 * prior name renders at, and the label wrapped mid-word with the bar sitting beside the fragment
 * (D05's own validator finding, `2.0×` scale) — the same threshold `FailStorageWarningBanner.kt`/
 * `LevelScreen.kt`/`ActivityPatternChart.kt` already use for "stack instead of cram". */
private const val LARGE_FONT_SCALE_THRESHOLD = 1.3f

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
    val rowModifier = modifier
        .fillMaxWidth()
        .semantics(mergeDescendants = true) { contentDescription = description }
    // R-323: below the threshold, the label/bar/value share one row exactly as before — above it,
    // the label gets the row's full width on its own line (still never wrapping mid-word) and the
    // bar+value reflow onto a second line beneath it, rather than a cramped label column forcing a
    // mid-word wrap the bar then sits beside.
    if (LocalDensity.current.fontScale >= LARGE_FONT_SCALE_THRESHOLD) {
        Column(modifier = rowModifier) {
            PriorBarLabel(state, modifier = Modifier.fillMaxWidth())
            Row(
                modifier = Modifier.padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PriorBarMeter(state, modifier = Modifier.weight(1f))
                PriorBarValue(state)
            }
        }
    } else {
        Row(
            modifier = rowModifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PriorBarLabel(state, modifier = Modifier.widthIn(min = 118.dp, max = 122.dp))
            PriorBarMeter(state, modifier = Modifier.weight(1f))
            PriorBarValue(state)
        }
    }
}

/** [Text.softWrap] `false`/`maxLines = 1`: a prior's name never wraps mid-word — R-323's own fix,
 * true regardless of font scale (only the surrounding layout reflows). */
@Composable
private fun PriorBarLabel(state: PriorBarViewState, modifier: Modifier = Modifier) {
    Text(
        text = state.name,
        style = OrtType.cardBody,
        color = if (state.arguedAgainst) OrtColors.textDim else OrtColors.textPrior,
        softWrap = false,
        maxLines = 1,
        modifier = modifier,
    )
}

@Composable
private fun PriorBarMeter(state: PriorBarViewState, modifier: Modifier = Modifier) {
    Box(modifier = modifier.height(5.dp).background(OrtColors.bgScore, RoundedCornerShape(3.dp))) {
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
}

@Composable
private fun PriorBarValue(state: PriorBarViewState, modifier: Modifier = Modifier) {
    if (state.valueLabel != null) {
        Text(
            text = state.valueLabel,
            style = OrtType.signal,
            color = if (state.arguedAgainst) OrtColors.accentAmberText else OrtColors.textDim,
            textAlign = TextAlign.End,
            softWrap = false,
            maxLines = 1,
            modifier = modifier.widthIn(min = 30.dp, max = 34.dp),
        )
    } else {
        Text(
            text = "cold start",
            style = OrtType.signal.copy(fontStyle = FontStyle.Italic),
            color = OrtColors.textLow,
            softWrap = false,
            maxLines = 1,
            modifier = modifier,
        )
    }
}
