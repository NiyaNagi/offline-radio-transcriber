package org.ort.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Typography derived from `design/canvas/`'s "Editorial" direction — a large, tight-tracked
 * display face for screen titles (`Overnight`, `Log`) over a plain-weight body face for
 * transcripts and list rows (canvas.json's `integrated` annotation: "Editorial's typography and
 * rhythm as the base"). The canvas specifies IBM Plex Sans/Mono; those are Google Fonts loaded at
 * design time only — pulling a network font into the shipped app would violate constitution V
 * (no network in any path but the declared ones), so the reader uses the platform's default
 * sans/monospace families, matched for weight and letter-spacing instead of typeface.
 *
 * Every size here is in `sp`, not `dp` — the unit that scales with system font size (FR-A11Y-3,
 * AC-63). Fixed-height containers are avoided throughout `ui/` for the same reason: at maximum
 * font scale a row must be allowed to grow, not clip.
 */
public object OrtType {
    public val mono: FontFamily = FontFamily.Monospace

    /** Screen titles — "Overnight", "Log" (27sp / -0.022em in the canvas). */
    public val titleLarge: TextStyle = TextStyle(
        fontSize = 27.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.4).sp,
    )

    /** Section labels — "Worth knowing", "Stations heard" (11sp uppercase, tracked out). */
    public val sectionLabel: TextStyle = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
    )

    /** Callsigns and other monospace facts — always [mono], per the canvas throughout. */
    public val callsign: TextStyle = TextStyle(
        fontFamily = mono,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
    )

    public val body: TextStyle = TextStyle(fontSize = 15.sp, lineHeight = 21.sp)
    public val caption: TextStyle = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp)

    public val typography: Typography = Typography(
        titleLarge = titleLarge,
        bodyLarge = body,
        bodyMedium = caption,
        labelSmall = sectionLabel,
    )
}
