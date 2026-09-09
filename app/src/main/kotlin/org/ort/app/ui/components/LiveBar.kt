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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
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
    val palette = liveBarPalette(state.tone)
    // R-128 (`Fail-Usb.dc.html`): the meter can disagree with the label's tone — audio is fine
    // (green bars) even while the label itself calls for action in halt-red, because the failure
    // is "the radio needs a permission", not "capture stopped hearing audio". `meterTone` is the
    // override for exactly that case; `null` (every caller before this existed) means "follow
    // `tone`", so this is additive.
    val meterColor = meterColorFor(state.meterTone ?: state.tone, state.level)
    // guide: "Act" is the live bar's own halt-red call-to-action word, distinct from whatever
    // colour the rest of the bar is in (amber for a degraded tone, in Fail-Usb's case) — it reads
    // halt-red whenever the label itself says so, not only when the whole bar's tone is HALTED.
    val labelColor = liveBarLabelColor(state.label, palette.labelColor)
    val description = buildString {
        append(state.label)
        state.partialText?.let {
            append(": ")
            append(it)
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(palette.topEdge))
        Row(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .background(palette.background)
                .clickable(onClickLabel = state.label, role = Role.Button, onClick = onClick)
                .clearAndSetSemantics {
                    contentDescription = description
                    role = Role.Button
                    onClick(label = state.label) {
                        onClick()
                        true
                    }
                },
            verticalAlignment = Alignment.Bottom,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = OrtSpacing.lg, end = OrtSpacing.lg, top = 10.dp, bottom = 14.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                LevelMeter(level = state.level, color = meterColor)
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
                    color = labelColor,
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

private data class LiveBarPalette(val background: Color, val topEdge: Color, val labelColor: Color)

// `OrtColors` is a plain object of constant `Color` values, not `CompositionLocal`-backed, so
// none of `liveBarPalette`/`meterColorFor`/`liveBarLabelColor` need `@Composable` — deliberately
// plain functions so R-128's tone-resolution logic is directly unit-testable (the pattern R-054's
// `scrubFraction` established: a rendered `Color`/pixel isn't something Robolectric can verify
// reliably here, but the pure decision that produces it is exactly, and cheaply, testable).
private fun liveBarPalette(tone: LiveBarTone): LiveBarPalette = when (tone) {
    LiveBarTone.NOMINAL -> LiveBarPalette(
        background = OrtColors.bgLive,
        topEdge = OrtColors.lineStrong,
        labelColor = OrtColors.accentGreen,
    )

    LiveBarTone.DEGRADED -> LiveBarPalette(
        background = OrtColors.bgRowGap,
        topEdge = OrtColors.bannerAmberBorder,
        labelColor = OrtColors.accentAmberDim,
    )

    LiveBarTone.HALTED -> LiveBarPalette(
        background = OrtColors.haltBg,
        topEdge = OrtColors.haltBorder,
        labelColor = OrtColors.haltText,
    )
}

/** The meter's own colour for [tone] — split out from [liveBarPalette] because R-128
 * (`Fail-Usb.dc.html`) needs the meter to answer a different question ("is audio itself fine?")
 * than the label/background do ("what does the operator need to know?"); `LiveBarViewState`'s
 * `meterTone` lets a caller drive this with a tone distinct from the bar's overall one. */
internal fun meterColorFor(tone: LiveBarTone, level: List<Float>): Color = when (tone) {
    LiveBarTone.NOMINAL -> if (level.any { it > 0f }) OrtColors.accentGreen else OrtColors.meterIdle
    LiveBarTone.DEGRADED -> OrtColors.meterWarn
    LiveBarTone.HALTED -> OrtColors.haltFill
}

/** R-128 (`Fail-Usb.dc.html`): the live bar's own halt-red call-to-action word — "Act" reads
 * `haltText` regardless of [fallback] (the rest-of-bar tone's own label colour, which is what
 * this would otherwise be), because the failure this word names is urgent even when the bar
 * around it is only degraded amber, not fully halted. Any other [label] keeps [fallback]
 * unchanged — additive, not a new rule for the labels every caller already had. */
internal fun liveBarLabelColor(label: String, fallback: Color): Color =
    if (label == "Act") OrtColors.haltText else fallback

/** The live bar's three tones (R-022): nominal (running normally), degraded (a tier drop,
 * backlog, thermal or stale-rig warning — always amber, per constitution "red is for one thing"),
 * and halted (capture has stopped and the operator must act — the only red state). */
public enum class LiveBarTone { NOMINAL, DEGRADED, HALTED }

/**
 * [level] is the four-bar meter, one value per bar in `0f..1f` (guide §6.6's 2px bars). Fewer
 * than four entries render fewer bars; more than four is truncated to four. [partialText] is the
 * live Pass A partial (null when nothing has been heard yet — the bar then shows just the meter
 * and label). [label] is "Live" nominal, or the degradation/halt reason ("Tier 2", "Halted — no
 * route", "Act") — this component does not invent copy, it renders what it is given.
 *
 * [meterTone], when non-null, overrides [tone] for the meter bars' colour only — the background,
 * top edge and label keep following [tone]. R-128 (`Fail-Usb.dc.html`): a USB-permission failure
 * is a rig problem, not an audio one, so the bar as a whole reads degraded/halt (the label calls
 * for action) while the meter itself stays green ("audio fine"). `null` (every caller before this
 * existed) means "follow [tone]" — additive, no existing caller's rendering changes.
 */
public data class LiveBarViewState(
    val level: List<Float>,
    val partialText: String?,
    val label: String,
    val tone: LiveBarTone,
    val meterTone: LiveBarTone? = null,
)
