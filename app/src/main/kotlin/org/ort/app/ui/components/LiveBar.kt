package org.ort.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/**
 * R-022 (ui-conformance-plan WP2): the persistent live bar, absent from the reader until now
 * (`Main.dc.html`'s footer, guide §6.6). P4 ("capture state never in doubt") and P5 ("live vs
 * record differ") are one component: it proves capture is running *and* shows the provisional
 * partial text looking provisional (italic, dimmed, no state marker of its own).
 *
 * A screen owns pinning [LiveBar] to its own bottom edge (this component does not know about
 * scaffolding) and supplies a [LiveBarViewState] built from whatever live-session polling it
 * already does — this composable is a pure function of that state, no `Context`, no polling.
 */
@Composable
public fun LiveBar(state: LiveBarViewState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = liveBarPalette(state)
    val description = buildString {
        append(state.label)
        state.partialText?.let { append(": "); append(it) }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(palette.topEdge))
        Row(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .background(palette.background)
                .clickable(onClickLabel = state.label, role = Role.Button, onClick = onClick)
                .semantics(mergeDescendants = true) {
                    contentDescription = description
                    role = Role.Button
                },
            verticalAlignment = Alignment.Bottom,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = OrtSpacing.lg, end = OrtSpacing.lg, top = 10.dp, bottom = 14.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                LevelMeter(level = state.level, color = palette.meterColor)
                Spacer(modifier = Modifier.width(11.dp))
                if (state.partialText != null) {
                    Text(
                        text = state.partialText,
                        style = OrtType.transcript.copy(fontStyle = FontStyle.Italic),
                        color = OrtColors.textLive,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.width(OrtSpacing.sm))
                Text(
                    text = state.label,
                    style = OrtType.textAction.copy(fontWeight = FontWeight.Medium),
                    color = palette.labelColor,
                )
            }
        }
    }
}

@Composable
private fun LevelMeter(level: List<Float>, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.height(14.dp).widthIn(min = 2.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        level.take(4).forEach { value ->
            val barHeight = (14.dp * value.coerceIn(0f, 1f)).coerceAtLeast(2.dp)
            Box(modifier = Modifier.width(2.dp).height(barHeight).background(color))
        }
    }
}

private data class LiveBarPalette(
    val background: Color,
    val topEdge: Color,
    val labelColor: Color,
    val meterColor: Color,
)

@Composable
private fun liveBarPalette(state: LiveBarViewState): LiveBarPalette = when (state.tone) {
    LiveBarTone.NOMINAL -> LiveBarPalette(
        background = OrtColors.bgLive,
        topEdge = OrtColors.lineStrong,
        labelColor = OrtColors.accentGreen,
        meterColor = if (state.level.any { it > 0f }) OrtColors.accentGreen else OrtColors.meterIdle,
    )

    LiveBarTone.DEGRADED -> LiveBarPalette(
        background = OrtColors.bgRowGap,
        topEdge = OrtColors.bannerAmberBorder,
        labelColor = OrtColors.accentAmberDim,
        meterColor = OrtColors.meterWarn,
    )

    LiveBarTone.HALTED -> LiveBarPalette(
        background = OrtColors.haltBg,
        topEdge = OrtColors.haltBorder,
        labelColor = OrtColors.haltText,
        meterColor = OrtColors.haltFill,
    )
}

/** The live bar's three tones (R-022): nominal (running normally), degraded (a tier drop,
 * backlog, thermal or stale-rig warning — always amber, per constitution "red is for one thing"),
 * and halted (capture has stopped and the operator must act — the only red state). */
public enum class LiveBarTone { NOMINAL, DEGRADED, HALTED }

/**
 * [level] is the four-bar meter, one value per bar in `0f..1f` (guide §6.6's 2px bars). Fewer
 * than four entries render fewer bars; more than four is truncated to four. [partialText] is the
 * live Pass A partial (null when nothing has been heard yet — the bar then shows just the meter
 * and label). [label] is "Live" nominal, or the degradation/halt reason ("Tier 2", "Halted — no
 * route") — this component does not invent copy, it renders what it is given.
 */
public data class LiveBarViewState(
    val level: List<Float>,
    val partialText: String?,
    val label: String,
    val tone: LiveBarTone,
)
