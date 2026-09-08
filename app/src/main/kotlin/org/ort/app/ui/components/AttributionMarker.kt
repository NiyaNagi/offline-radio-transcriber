package org.ort.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.ort.app.transmissions.TransmissionListViewStateMapper
import org.ort.app.transmissions.TransmissionRow
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType
import org.ort.core.Attribution
import org.ort.core.AttributionState

/**
 * The four attribution states, as one reusable component (build-plan P13; AC-62, FR-A11Y-1).
 * Every screen that shows an attribution (P14's live view and detail, P15's log and threads)
 * renders it through this composable rather than building a second marker scheme, so "the four
 * states are distinguishable without colour" is proven once, here, and inherited everywhere.
 *
 * It renders on top of [TransmissionListViewStateMapper], which already carries the tested
 * per-state text marker (`✓`/`~`/`?`/`—`) — this component does not invent a second label, it
 * draws a matching **shape** next to that same text (`States.dc.html`'s design: a filled dot for
 * `CONFIRMED`, an outlined ring for `INFERRED`, a half-filled ring for `AMBIGUOUS`, and a small
 * dim dot for `UNKNOWN` — shape carries the meaning, colour is reinforcement only per the
 * artboard's own annotation).
 *
 * The whole marker is one merged accessibility node (FR-A11Y-2): a screen reader hears the shape,
 * the state name and, where present, the confidence — never just a colour swatch or a bare glyph.
 */
@Composable
public fun AttributionMarker(attribution: Attribution, modifier: Modifier = Modifier) {
    val row = TransmissionListViewStateMapper.from(
        TransmissionRow(id = "", transcript = "", attribution = attribution),
    )
    val description = buildString {
        append(shapeDescription(attribution.state))
        append(", ")
        append(row.attributionLabel)
        attribution.confidence?.let { append(", confidence %.2f".format(it)) }
    }
    Row(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        AttributionShape(attribution.state)
        Spacer(modifier = Modifier.width(OrtSpacing.xs))
        Text(text = row.attributionLabel, style = OrtType.callsign)
    }
}

@Composable
private fun AttributionShape(state: AttributionState) {
    val unknownColor = LocalContentColor.current
    val size = 12.dp
    Canvas(modifier = Modifier.width(size)) {
        val d = size.toPx()
        when (state) {
            AttributionState.CONFIRMED ->
                // A fully filled circle — heard and resolved in this transmission.
                drawCircle(color = OrtColors.accentGreen, radius = d / 2)

            AttributionState.INFERRED ->
                // An outlined (unfilled) ring — present, but not heard in *this* over.
                drawCircle(color = OrtColors.accentGreen, radius = d / 2 - 1.dp.toPx(), style = ringStroke)

            AttributionState.AMBIGUOUS -> {
                // A ring, half of it filled — more than one candidate survived.
                drawCircle(color = OrtColors.accentAmber, radius = d / 2 - 1.dp.toPx(), style = ringStroke)
                clipRect(right = d / 2) {
                    drawCircle(color = OrtColors.accentAmber, radius = d / 2 - 1.dp.toPx())
                }
            }

            AttributionState.UNKNOWN ->
                // A small, dim dot, deliberately much smaller than the other three — nothing is
                // claimed. Colour-independent because it differs in *size*, not just shade.
                drawCircle(color = unknownColor, radius = d / 6)
        }
    }
}

private val DrawScope.ringStroke: Stroke
    get() = Stroke(width = 2.dp.toPx())

private fun shapeDescription(state: AttributionState): String = when (state) {
    AttributionState.CONFIRMED -> "filled circle"
    AttributionState.INFERRED -> "outlined circle"
    AttributionState.AMBIGUOUS -> "half-filled circle"
    AttributionState.UNKNOWN -> "small dot"
}
