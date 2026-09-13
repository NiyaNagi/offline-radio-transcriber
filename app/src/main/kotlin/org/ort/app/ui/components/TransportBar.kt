package org.ort.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/**
 * C10 (`design/canvas/Transport-Bar.dc.html`): the one bottom bar, replacing the plain [LiveBar]
 * pinned by `OrtNavHost.kt` before this — two modes, never both: [TransportBarViewState.Live]
 * delegates straight to the existing [LiveBar] (its states 1-3 unchanged — nominal/attention/
 * halted are the same [LiveBarViewState] this file's own callers already build), and
 * [TransportBarViewState.Playback] is this file's own addition (states 4-6 of the artboard —
 * playing, playing-while-capturing, paused). [TransportBarViewState.Hidden] renders nothing at
 * all — the artboard's own rule "hidden on the screen it expands to" (IA-2, and the playing
 * transmission's own detail screen for playback mode) is a decision the nav host makes before
 * choosing this state, not something this pure composable decides for itself.
 */
public sealed interface TransportBarViewState {
    public data class Live(val bar: LiveBarViewState) : TransportBarViewState

    /**
     * States 4-6 of the artboard. [isPlaying] selects the pause-bars glyph (playing) or the
     * play-triangle glyph plus the `×` clear control (paused) — the artboard draws `×` only in the
     * paused state, so a paused-but-still-loaded over is the only way to clear the bar; a bar mid-
     * play has no dismiss control of its own (pause first, per the artboard). [capturingDotVisible]
     * is state 5's own small live dot, kept lit while playing during a real capture session so
     * "capture is never out of sight" (constitution IV) even though the bar itself now reads
     * playback. [callsignLabel] is `null` only when the loaded transmission's own metadata has not
     * yet reached the controller (a play just issued) — rendered as "Unknown" rather than blank,
     * never a stand-in the operator could mistake for a real callsign.
     */
    public data class Playback(
        val transmissionId: String,
        val callsignLabel: String?,
        val isPlaying: Boolean,
        val positionFraction: Float,
        val elapsedLabel: String,
        val totalLabel: String,
        val capturingDotVisible: Boolean,
    ) : TransportBarViewState

    /** Nothing to show — no live session and nothing loaded for playback, or this is the one
     * screen this exact state expands to/plays. */
    public data object Hidden : TransportBarViewState
}

/** [TransportBar]'s own actions — bundled the same way every other multi-callback component in
 * this package already is ([org.ort.app.ui.failures.FailureHostActions], etc). */
public data class TransportBarActions(
    val onTapLive: () -> Unit = {},
    val onTapPlaying: (String) -> Unit = {},
    val onPlayPauseToggle: () -> Unit = {},
    val onScrub: (Float) -> Unit = {},
    val onClear: () -> Unit = {},
)

@Composable
public fun TransportBar(state: TransportBarViewState, actions: TransportBarActions, modifier: Modifier = Modifier) {
    when (state) {
        is TransportBarViewState.Live -> LiveBar(state = state.bar, onClick = actions.onTapLive, modifier = modifier)
        is TransportBarViewState.Playback -> PlaybackTransportBar(state = state, actions = actions, modifier = modifier)
        TransportBarViewState.Hidden -> Unit
    }
}

/**
 * R-1021 (register, C10's own note): the live label varies by design and nothing may match on its
 * text — this playback half of the bar carries no such label at all (only a callsign, a fixed
 * "Unknown" fallback, and mono times), so nothing here risks the same defect class.
 *
 * The artboard's "tap → that transmission" is scoped to the callsign itself, not the whole row:
 * the row also hosts the pause/play toggle, the scrub track and (paused) the `×`, each its own
 * 44dp target with its own semantics — a single row-wide `clickable` merged over all three (the
 * shape `LiveBar`'s own single-target row uses) would either swallow their touches or, via
 * `clearAndSetSemantics`, erase their individual descriptions from the accessibility tree
 * entirely. Scoping the open-transmission tap to the callsign keeps every control independently
 * reachable and independently testable.
 */
@Composable
private fun PlaybackTransportBar(
    state: TransportBarViewState.Playback,
    actions: TransportBarActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = Modifier.fillMaxWidth().testTag("transport-bar")) {
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(OrtColors.lineStrong))
        Row(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .background(OrtColors.bgLive)
                .testTag("transport-bar-playback")
                .padding(horizontal = OrtSpacing.lg, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            if (state.capturingDotVisible) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(OrtColors.accentGreen, CircleShape)
                        .testTag("transport-bar-capturing-dot"),
                )
            }
            PlaybackToggleControl(isPlaying = state.isPlaying, onToggle = actions.onPlayPauseToggle)
            Text(
                text = "${state.elapsedLabel} / ${state.totalLabel}",
                style = OrtType.timeFreq,
                color = OrtColors.textMuted,
            )
            ScrubTrack(
                positionFraction = state.positionFraction,
                dimmed = !state.isPlaying,
                onScrub = actions.onScrub,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = state.callsignLabel ?: "Unknown",
                style = OrtType.callsignCard,
                color = if (state.isPlaying) OrtColors.textHigh else OrtColors.textBody,
                modifier = Modifier
                    .clickable(
                        onClickLabel = "Open ${state.callsignLabel ?: "this transmission"}",
                        role = Role.Button,
                    ) { actions.onTapPlaying(state.transmissionId) }
                    .testTag("transport-bar-callsign"),
            )
            // C10: the `×` is drawn only in the paused state — a bar mid-play has no dismiss
            // control (pause first), matching the artboard's own six states exactly.
            if (!state.isPlaying) {
                ClearControl(onClear = actions.onClear)
            }
        }
    }
}

/**
 * The pause/play control — 44dp touch target per the artboard's own "pause and scrub are 44 px
 * targets" note, drawn glyph smaller inside it. Reuses [Inspection.kt]'s own glyph decision
 * ([waveformControlGlyph]) and its 14dp `PauseGlyph`/`OrtIcons.play` pairing so the pause bars this
 * bar draws are pixel-identical to the waveform card's own, rather than a second hand-drawn copy.
 */
@Composable
private fun PlaybackToggleControl(isPlaying: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { contentDescription = if (isPlaying) "Pause" else "Resume" }
            .testTag("transport-bar-toggle"),
        contentAlignment = Alignment.Center,
    ) {
        when (waveformControlGlyph(isPlaying)) {
            WaveformControlGlyph.PAUSE -> BarPauseGlyph(modifier = Modifier.testTag("transport-bar-glyph-pause"))
            WaveformControlGlyph.PLAY -> Icon(
                imageVector = OrtIcons.play,
                contentDescription = null,
                tint = OrtColors.accentGreen,
                modifier = Modifier.size(14.dp).testTag("transport-bar-glyph-play"),
            )
        }
    }
}

/** C10's own pause glyph: two 3.5×14dp accent/green bars — drawn directly, the same reason
 * `Inspection.kt`'s own `PauseGlyph` is (that file's own doc comment: a concurrent package's file,
 * and its glyph is tinted for a filled green circle button, `accent/on-green`, not this bar's plain
 * background — the colour alone means this cannot just call that one). */
@Composable
private fun BarPauseGlyph(modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(modifier = Modifier.width(3.5.dp).height(14.dp).background(OrtColors.accentGreen))
        Box(modifier = Modifier.width(3.5.dp).height(14.dp).background(OrtColors.accentGreen))
    }
}

/** The `×` clear control — 44dp touch target, [OrtIcons.dismiss] at 16dp, matching the artboard's
 * own stroke glyph. */
@Composable
private fun ClearControl(onClear: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clickable(role = Role.Button, onClick = onClear)
            .semantics { contentDescription = "Clear playback" }
            .testTag("transport-bar-clear"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = OrtIcons.dismiss,
            contentDescription = null,
            tint = OrtColors.textIconDim,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * The scrub bar — a 3dp visual track inside a 44dp-tall touch target (the artboard's own "pause
 * and scrub are 44 px targets" note), reusing [scrubFraction] (`Inspection.kt`, `internal` in this
 * same package) rather than a second copy of the same tap/drag-to-fraction arithmetic.
 */
@Composable
private fun ScrubTrack(
    positionFraction: Float,
    dimmed: Boolean,
    onScrub: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fillColor = if (dimmed) OrtColors.accentGreenDim else OrtColors.accentGreen
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .testTag("transport-bar-scrub")
            .semantics { contentDescription = "Scrub playback position" }
            .pointerInput(Unit) {
                detectTapGestures { offset -> onScrub(scrubFraction(offset.x, size.width.toFloat())) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onScrub(scrubFraction(change.position.x, size.width.toFloat()))
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            Box(modifier = Modifier.fillMaxWidth().height(3.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(OrtColors.lineDefault, RoundedCornerShape(2.dp)),
                )
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth(positionFraction.coerceIn(0f, 1f))
                        .height(3.dp)
                        .background(fillColor, RoundedCornerShape(2.dp)),
                )
            }
        }
    }
}

/** `m:ss` — the bar's own elapsed/total format (`Transport-Bar.dc.html`: "0:04 / 0:12"). Never
 * locale-formatted (constitution II: assertions must not depend on a formatted number that varies
 * by locale) — plain integer division, always rendered with an ASCII colon and zero-padded seconds. */
internal fun formatTransportBarTime(totalSeconds: Double): String {
    val whole = totalSeconds.coerceAtLeast(0.0).toInt()
    val minutes = whole / 60
    val seconds = whole % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
