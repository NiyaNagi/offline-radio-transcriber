package org.ort.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.ort.app.transmissions.TransmissionListViewStateMapper
import org.ort.app.transmissions.TransmissionRow
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType
import org.ort.core.Attribution
import org.ort.core.AttributionState

/**
 * R-020 (ui-conformance-plan WP2): the four attribution states, re-rendered to
 * `design/design-guide.md` §6.1-6.2 and `States.dc.html` — the most important component in the
 * product (AC-62, FR-A11Y-1, FR-UI-4).
 *
 * There are two forms, on purpose:
 *
 * - [AttributionMarker] (this function) is the **shape**, plus a **migration shim**. The
 *   artboard (`States.dc.html`) is shape + the caller's own mono callsign, with a score chip only
 *   on INFERRED — never a number on CONFIRMED — but every caller today (`LogScreen`,
 *   `SearchScreen`, `ThreadScreen`, `TransmissionDetailScreen`) and their own tests
 *   (`LogScreenTest`, `TransmissionDetailScreenTest`, owned by WP5/WP6/WP7, not this package)
 *   still expect the pre-R-020 behaviour of a visible confidence number next to the shape for
 *   *any* state that carries one, including CONFIRMED. [showConfidence] (default `true`, so every
 *   existing call site keeps its current behaviour unchanged) renders that confidence — in
 *   [ScoreChip] style per guide §6.2, not the old plain `Text` — right after the shape when the
 *   attribution carries one. **This parameter is a deprecated migration shim, not the spec**: the
 *   spec form is guide §6.2 (a score chip only ever beside INFERRED), which [AttributionRow]
 *   already implements correctly. Pass `showConfidence = false` for true shape-only rendering.
 *   Callers should switch to [AttributionRow] and this parameter should come out once they do —
 *   see this package's `CHANGELOG.md` entry for exactly which screens still need to switch, since
 *   none of them may be edited from this package (ui-conformance-plan WP2 owns the
 *   `ui/components` package only).
 * - [AttributionRow] is the **full form**: shape, callsign (styled per state), and — only where
 *   the guide says one belongs — a [ScoreChip] (INFERRED) or an `or QRF` alternate (AMBIGUOUS).
 *   It does not call [AttributionMarker] or its `showConfidence` shim at all — it owns the
 *   INFERRED-only chip natively, which is equivalent to the shim's `showConfidence = false` case
 *   plus the callsign/colour/alternate behaviour the shim never had. New call sites (WP4-WP10)
 *   should prefer this one.
 *
 * Both merge their accessibility semantics into one node (FR-A11Y-2): a screen reader hears the
 * shape, the state and, where present, the confidence or alternate — never a bare colour swatch.
 */
@Composable
public fun AttributionMarker(
    attribution: Attribution,
    modifier: Modifier = Modifier,
    // Deprecated migration shim (KDoc above) — not the spec form (guide §6.2: a score chip only
    // on INFERRED). Defaults true only so today's callers and their own tests keep today's
    // behaviour unchanged until they migrate to AttributionRow; prefer that for any new call site.
    showConfidence: Boolean = true,
) {
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = legacyMarkerDescription(attribution)
        },
    ) {
        AttributionShape(state = attribution.state, size = MARKER_ROW_SIZE)
        if (showConfidence) {
            attribution.confidence?.let { confidence ->
                Spacer(modifier = Modifier.width(OrtSpacing.xs))
                ScoreChip(confidence = confidence)
            }
        }
    }
}

/**
 * R-020's full form: shape + callsign (+ confidence / alternate), one merged semantics node.
 *
 * [callsign] defaults to [Attribution.stationId] (present for CONFIRMED/INFERRED; null for
 * AMBIGUOUS/UNKNOWN, where a caller may still pass one in if it has an out-of-band candidate to
 * show). [alternate] is the AMBIGUOUS "or QRF" runner-up, supplied by the caller — [Attribution]
 * itself carries no second candidate. [size] is 9dp on a row (default) or 12dp on a card
 * (`States.dc.html`); the ring weight scales from 1.5dp to 2dp at the card size, and UNKNOWN's
 * dot scales with it (5dp at 9dp, ~6dp at 12dp) rather than staying fixed while everything else
 * grows.
 */
@Composable
public fun AttributionRow(
    attribution: Attribution,
    callsign: String? = attribution.stationId,
    alternate: String? = null,
    modifier: Modifier = Modifier,
    size: Dp = MARKER_ROW_SIZE,
) {
    val state = attribution.state
    val description = attributionRowDescription(attribution, callsign, alternate)

    Row(modifier = modifier.semantics(mergeDescendants = true) { contentDescription = description }) {
        AttributionShape(state = state, size = size)
        Spacer(modifier = Modifier.width(OrtSpacing.xs))
        when (state) {
            AttributionState.CONFIRMED ->
                Text(text = callsign.orEmpty(), style = OrtType.callsignRow, color = OrtColors.textHigh)

            AttributionState.INFERRED -> {
                Text(text = callsign.orEmpty(), style = OrtType.callsignRow, color = OrtColors.textBody)
                attribution.confidence?.let { confidence ->
                    Spacer(modifier = Modifier.width(OrtSpacing.xs))
                    ScoreChip(confidence = confidence)
                }
            }

            AttributionState.AMBIGUOUS -> {
                Text(text = callsign.orEmpty(), style = OrtType.callsignRow, color = OrtColors.textAmbiguous)
                if (alternate != null) {
                    Spacer(modifier = Modifier.width(OrtSpacing.xs))
                    // guide §6.1: "or QRF" alternate at 11sp — OrtType has no exact 11sp non-mono
                    // token (R-020 report: closest named row is `subLine` at 11.5sp); using that
                    // rather than a literal .sp per this package's no-raw-style rule.
                    Text(text = "or $alternate", style = OrtType.subLine, color = OrtColors.accentAmber)
                }
            }

            AttributionState.UNKNOWN ->
                // guide: 13px italic text/low, sans — `textAction`/`transcript` is the guide's
                // nearest named 13sp sans row (both rows share the same size/weight/line-height).
                Text(
                    text = "unknown station",
                    style = OrtType.textAction.copy(fontStyle = FontStyle.Italic),
                    color = OrtColors.textLow,
                )
        }
    }
}

/** guide §6.2: mono 10.5px on `bg/score`, 3px radius, `1px 5px` padding — only ever beside an
 * INFERRED callsign or a candidate, never on CONFIRMED. */
@Composable
public fun ScoreChip(confidence: Double, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(OrtColors.bgScore, RoundedCornerShape(3.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(text = "%.2f".format(confidence), style = OrtType.scoreChip, color = OrtColors.textFaint)
    }
}

/** 9dp default marker size — `States.dc.html`'s row size; cards use 12dp. */
public val MARKER_ROW_SIZE: Dp = 9.dp

/** `States.dc.html`'s card size, where the ring weight goes to 2px. */
public val MARKER_CARD_SIZE: Dp = 12.dp

@Composable
private fun AttributionShape(state: AttributionState, size: Dp, modifier: Modifier = Modifier) {
    val ringWidth = if (size >= MARKER_CARD_SIZE) 2.dp else 1.5.dp
    Canvas(modifier = modifier.width(size).height(size)) {
        val d = size.toPx()
        when (state) {
            AttributionState.CONFIRMED ->
                // A fully filled circle — heard and resolved in this transmission.
                drawCircle(color = OrtColors.accentGreen, radius = d / 2)

            AttributionState.INFERRED ->
                // An outlined (unfilled) ring — present, but not heard in *this* over.
                drawCircle(
                    color = OrtColors.accentGreen,
                    radius = d / 2 - ringWidth.toPx() / 2,
                    style = Stroke(width = ringWidth.toPx()),
                )

            AttributionState.AMBIGUOUS -> {
                // A ring, half of it filled — more than one candidate survived.
                drawCircle(
                    color = OrtColors.accentAmber,
                    radius = d / 2 - ringWidth.toPx() / 2,
                    style = Stroke(width = ringWidth.toPx()),
                )
                clipRect(right = d / 2) {
                    drawCircle(color = OrtColors.accentAmber, radius = d / 2 - ringWidth.toPx() / 2)
                }
            }

            AttributionState.UNKNOWN ->
                // A small, dim dot — 5/9 of the main size, so it stays deliberately much smaller
                // than the other three (not merely dimmer) at every size the marker renders at.
                // Colour-independent because it differs in *size*, not just shade.
                drawCircle(color = OrtColors.markerUnknown, radius = (d * 5f / 9f) / 2)
        }
    }
}

/** [AttributionMarker]'s content description — unchanged from before R-020 so every existing
 * caller's content-description assertions keep passing even though the *visible* render is now
 * shape-only (see this file's top doc comment). */
private fun legacyMarkerDescription(attribution: Attribution): String {
    val row = TransmissionListViewStateMapper.from(
        TransmissionRow(id = "", transcript = "", attribution = attribution),
    )
    return buildString {
        append(shapeDescription(attribution.state))
        append(", ")
        append(row.attributionLabel)
        attribution.confidence?.let { append(", confidence %.2f".format(it)) }
    }
}

private fun attributionRowDescription(attribution: Attribution, callsign: String?, alternate: String?): String =
    buildString {
        append(shapeDescription(attribution.state))
        append(", ")
        append(stateProse(attribution.state))
        val identity = callsign ?: if (attribution.state == AttributionState.UNKNOWN) "unknown station" else null
        identity?.let {
            append(", ")
            append(it)
        }
        if (attribution.state == AttributionState.INFERRED) {
            attribution.confidence?.let { append(", confidence %.2f".format(it)) }
        }
        if (attribution.state == AttributionState.AMBIGUOUS && alternate != null) {
            append(", or ")
            append(alternate)
        }
    }

private fun shapeDescription(state: AttributionState): String = when (state) {
    AttributionState.CONFIRMED -> "filled circle"
    AttributionState.INFERRED -> "outlined circle"
    AttributionState.AMBIGUOUS -> "half-filled circle"
    AttributionState.UNKNOWN -> "small dot"
}

private fun stateProse(state: AttributionState): String = when (state) {
    AttributionState.CONFIRMED -> "Confirmed"
    AttributionState.INFERRED -> "Inferred"
    AttributionState.AMBIGUOUS -> "Ambiguous"
    AttributionState.UNKNOWN -> "Unknown"
}
