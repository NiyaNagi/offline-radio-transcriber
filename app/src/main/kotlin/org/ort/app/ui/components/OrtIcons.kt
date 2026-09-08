package org.ort.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.ort.app.ui.theme.OrtColors

/**
 * R-025 (ui-conformance-plan WP2): the stroke icon set from `Icons.dc.html`/guide §7, built as
 * [ImageVector]s from the artboard's own SVG `d` path data (`<rect>`/`<circle>` elements are
 * converted to their exact path equivalents by the standard formula so every icon here traces
 * the same geometry the board draws — none of it is redrawn by eye). Every icon renders on a
 * 24-unit viewBox with round caps/joins, matching the board's `stroke-linecap="round"
 * stroke-linejoin="round"`. Colour is `Color.Black` as a build-time placeholder only — a caller
 * always supplies the real colour via `Icon(..., tint = ...)`, which overrides every path's own
 * paint (guide §7: "tinted by the caller").
 *
 * Before R-025 no screen used any of these — the reader drew `=`, `▶`, `‹`, `▸`/`▾`, `▨` as text
 * glyphs, which guide §7 rules out explicitly ("never a font glyph").
 */
public object OrtIcons {

    // --- Header / navigation (18-21px at stroke 1.8-1.9) --------------------------------------

    public val drawer: ImageVector = buildIcon("drawer") { strokePath("M4 7h16M4 12h16M4 17h10", 1.9f) }
    public val back: ImageVector = buildIcon("back") { strokePath("M15 5l-7 7 7 7", 1.9f) }
    public val search: ImageVector = buildIcon("search") {
        strokePath("M4,11 A7,7 0 1,0 18,11 A7,7 0 1,0 4,11 M20 20l-3.5-3.5", 1.8f)
    }
    public val more: ImageVector = buildIcon("more") {
        fillPath(
            "M10.6,5 A1.4,1.4 0 1,0 13.4,5 A1.4,1.4 0 1,0 10.6,5 " +
                "M10.6,12 A1.4,1.4 0 1,0 13.4,12 A1.4,1.4 0 1,0 10.6,12 " +
                "M10.6,19 A1.4,1.4 0 1,0 13.4,19 A1.4,1.4 0 1,0 10.6,19",
        )
    }
    public val chevron: ImageVector = buildIcon("chevron") { strokePath("M9 18l6-6-6-6", 2f) }
    public val dismiss: ImageVector = buildIcon("dismiss") { strokePath("M6 6l12 12M18 6L6 18", 2.4f) }
    public val expand: ImageVector = buildIcon("expand") { strokePath("M6 9l6 6 6-6", 2f) }
    public val filters: ImageVector = buildIcon("filters") { strokePath("M4 6h16M7 12h10M10 18h4", 2.2f) }

    // --- The nine drawer destination icons (18px at stroke 1.9) --------------------------------

    public val now: ImageVector = buildIcon("now") { strokePath("M3 12l9-8 9 8M6 10v9h12v-9", 1.9f) }
    public val log: ImageVector = buildIcon("log") { strokePath("M4 7h16M4 12h16M4 17h16", 1.9f) }
    public val threads: ImageVector = buildIcon("threads") {
        strokePath(
            "M6,4 H18 A3,3 0 0 1 21,7 V17 A3,3 0 0 1 18,20 H6 A3,3 0 0 1 3,17 V7 A3,3 0 0 1 6,4 Z M8 10h8M8 14h5",
            1.9f,
        )
    }
    public val stations: ImageVector = buildIcon("stations") {
        strokePath(
            "M8.5,8 A3.5,3.5 0 1,0 15.5,8 A3.5,3.5 0 1,0 8.5,8 M5.5 19c0-3.3 2.9-5.5 6.5-5.5s6.5 2.2 6.5 5.5",
            1.9f,
        )
    }
    public val frequencies: ImageVector = buildIcon("frequencies") {
        strokePath("M12 4v16M7 8v8M17 8v8M3 11v2M21 11v2", 1.9f)
    }
    public val earlierNights: ImageVector = buildIcon("earlierNights") {
        strokePath(
            "M5.5,5 H18.5 A2.5,2.5 0 0 1 21,7.5 V18.5 A2.5,2.5 0 0 1 18.5,21 H5.5 A2.5,2.5 0 0 1 3,18.5 " +
                "V7.5 A2.5,2.5 0 0 1 5.5,5 Z M3 10h18M8 3v4M16 3v4",
            1.9f,
        )
    }
    public val capture: ImageVector = buildIcon("capture") {
        strokePath(
            "M12 3v4M12 17v4M5.6 5.6l2.8 2.8M15.6 15.6l2.8 2.8M3 12h4M17 12h4M5.6 18.4l2.8-2.8M15.6 8.4l2.8-2.8",
            1.9f,
        )
    }
    public val improve: ImageVector = buildIcon("improve") { strokePath("M20 12a8 8 0 1 1-2.6-5.9M20 4v4h-4", 1.9f) }
    public val settings: ImageVector = buildIcon("settings") {
        strokePath(
            "M19.4 15a1.6 1.6 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.6 1.6 0 0 0-2.7 1.1V21a2 2 0 1 1-4 0v-.1" +
                "A1.6 1.6 0 0 0 7 19.4a1.6 1.6 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.6 1.6 0 0 0-1.1-2.7H1a2" +
                " 2 0 1 1 0-4h.1A1.6 1.6 0 0 0 2.6 7 M9,12 A3,3 0 1,0 15,12 A3,3 0 1,0 9,12",
            1.9f,
        )
    }

    // --- Inline and status (11-17px at stroke 2-2.2) --------------------------------------------

    public val gapWarn: ImageVector = buildIcon("gapWarn") {
        strokePath("M12 8v5M12 16.5v.5 M3,12 A9,9 0 1,0 21,12 A9,9 0 1,0 3,12", 2f)
    }
    public val halt: ImageVector = buildIcon("halt") {
        strokePath("M3,12 A9,9 0 1,0 21,12 A9,9 0 1,0 3,12 M12 7v6M12 16v.5", 2f)
    }
    public val check: ImageVector = buildIcon("check") { strokePath("M5 12l5 5 9-10", 3f) }

    /** The one filled icon (guide §7). */
    public val play: ImageVector = buildIcon("play") { fillPath("M8 5l11 7-11 7z", OrtColors.accentGreen) }

    public val recent: ImageVector = buildIcon("recent") {
        strokePath("M3,12 A9,9 0 1,0 21,12 A9,9 0 1,0 3,12 M12 7v5l3 2", 2f)
    }

    /** "Never leaves" — a lock (guide's `lock` name in the required set). */
    public val lock: ImageVector = buildIcon("lock") {
        strokePath(
            "M6,10 H18 A2,2 0 0 1 20,12 V19 A2,2 0 0 1 18,21 H6 A2,2 0 0 1 4,19 V12 A2,2 0 0 1 6,10 Z " +
                "M8 10V7a4 4 0 0 1 8 0v3",
            2f,
        )
    }
    public val call: ImageVector = buildIcon("call") {
        strokePath("M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2", 2f)
    }
    public val thermal: ImageVector = buildIcon("thermal") {
        strokePath(
            "M12 3v3M12 18v3M5 12H2M22 12h-3M6.3 6.3L4.2 4.2M19.8 19.8l-2.1-2.1M6.3 17.7l-2.1 2.1M19.8 4.2l-2.1 2.1 " +
                "M8,12 A4,4 0 1,0 16,12 A4,4 0 1,0 8,12",
            2f,
        )
    }

    // --- Devices (18-20px at stroke 1.9) ---------------------------------------------------------

    public val usbAudio: ImageVector = buildIcon("usbAudio") {
        strokePath(
            "M8,3 H16 A2,2 0 0 1 18,5 V10 A2,2 0 0 1 16,12 H8 A2,2 0 0 1 6,10 V5 A2,2 0 0 1 8,3 Z " +
                "M12 12v4M8 21h8M12 16v5",
            1.9f,
        )
    }
    public val headset: ImageVector = buildIcon("headset") {
        strokePath(
            "M4 14v-2a8 8 0 0 1 16 0v2 " +
                "M4.5,14 H5.5 A1.5,1.5 0 0 1 7,15.5 V18.5 A1.5,1.5 0 0 1 5.5,20 H4.5 A1.5,1.5 0 0 1 3,18.5 " +
                "V15.5 A1.5,1.5 0 0 1 4.5,14 Z " +
                "M18.5,14 H19.5 A1.5,1.5 0 0 1 21,15.5 V18.5 A1.5,1.5 0 0 1 19.5,20 H18.5 A1.5,1.5 0 0 1 17,18.5 " +
                "V15.5 A1.5,1.5 0 0 1 18.5,14 Z",
            1.9f,
        )
    }
    public val builtInMic: ImageVector = buildIcon("builtInMic") {
        strokePath(
            "M12,3 H12 A3,3 0 0 1 15,6 V11 A3,3 0 0 1 12,14 H12 A3,3 0 0 1 9,11 V6 A3,3 0 0 1 12,3 Z " +
                "M5 11a7 7 0 0 0 14 0M12 18v3",
            1.9f,
        )
    }
    public val rig: ImageVector = buildIcon("rig") {
        strokePath(
            "M5.5,7 H18.5 A2.5,2.5 0 0 1 21,9.5 V16.5 A2.5,2.5 0 0 1 18.5,19 H5.5 A2.5,2.5 0 0 1 3,16.5 " +
                "V9.5 A2.5,2.5 0 0 1 5.5,7 Z M7 11h3M7 15h6M16 11h1M16 15h1",
            1.9f,
        )
    }
    public val storage: ImageVector = buildIcon("storage") {
        strokePath("M4 6a8 3 0 0 1 16 0v12a8 3 0 0 1-16 0z M4 6a8 3 0 0 0 16 0M4 12a8 3 0 0 0 16 0", 1.9f)
    }
    public val models: ImageVector = buildIcon("models") {
        strokePath(
            "M6.5,4 H17.5 A2.5,2.5 0 0 1 20,6.5 V17.5 A2.5,2.5 0 0 1 17.5,20 H6.5 A2.5,2.5 0 0 1 4,17.5 " +
                "V6.5 A2.5,2.5 0 0 1 6.5,4 Z M4 10h16M10 4v16",
            1.9f,
        )
    }
    public val export: ImageVector = buildIcon("export") {
        strokePath("M12 3v12M8 11l4 4 4-4M4 17v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2", 1.9f)
    }
    public val diagnostics: ImageVector = buildIcon("diagnostics") {
        strokePath("M4 19h16M6 15V9M10 15V5M14 15v-4M18 15V7", 1.9f)
    }
    public val edit: ImageVector = buildIcon("edit") { strokePath("M4 20h4l10-10-4-4L4 16v4z M12 8l4 4", 1.9f) }

    private fun buildIcon(name: String, build: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply(build).build()

    private fun ImageVector.Builder.strokePath(d: String, strokeWidth: Float) {
        addPath(
            pathData = addPathNodes(d),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = strokeWidth,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }

    private fun ImageVector.Builder.fillPath(d: String, color: Color = Color.Black) {
        addPath(pathData = addPathNodes(d), fill = SolidColor(color))
    }
}

/**
 * `Icons.dc.html`'s "in progress" indicator: an open-quadrant ring (a stroke circle missing its
 * right-hand quarter — the artboard draws it with `border-right-color: transparent`). Used beside
 * "resolving…" (guide §6.13) and any other in-flight indicator that is not a determinate
 * percentage (which uses [ProgressBar] instead, per guide §6.12's "never indeterminate" rule —
 * this ring is for genuinely unknown-length work, not a substitute for a known one).
 */
@Composable
public fun InProgressRing(
    modifier: Modifier = Modifier,
    size: Dp = 17.dp,
    color: Color = OrtColors.accentGreen,
    strokeWidth: Dp = 2.dp,
) {
    Canvas(modifier = modifier.size(size)) {
        drawArc(
            color = color,
            startAngle = -45f,
            sweepAngle = 270f,
            useCenter = false,
            style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round),
        )
    }
}
