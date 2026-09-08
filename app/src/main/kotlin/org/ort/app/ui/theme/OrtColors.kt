package org.ort.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Colour tokens carried over from `design/canvas/` (P13, D15). The canvas specifies its palette
 * in OKLCH (`oklch(lightness chroma hue)`) for perceptual evenness; Compose's colour space
 * conversions are not exposed in a form worth depending on for eight swatches, so each value
 * below is a hand-picked sRGB approximation of the corresponding `oklch(...)` value quoted from
 * the artboard it was taken from, kept close enough to preserve the design's relationships
 * (background/surface/divider steps, the two accent hues) rather than an exact colourimetric
 * match. Do not treat these as replacing the canvas — the `.dc.html` files under
 * `design/canvas/` remain the source of truth for the palette itself.
 *
 * Per constitution VII / AC-62 / FR-A11Y-1, none of these colours is ever the *only* signal for
 * an attribution state — see `ui/components/AttributionMarker.kt`.
 */
public object OrtColors {
    // Backgrounds, darkest to lightest — Menu.dc.html's page background, drawer and card surfaces.
    public val background: Color = Color(0xFF15171B) // oklch(0.11 0.006 250)
    public val surface: Color = Color(0xFF202329) // oklch(0.155 0.008 250) — Main/Log/Detail background
    public val surfaceVariant: Color = Color(0xFF262A31) // oklch(0.185-0.195 0.009 250) — drawer, cards
    public val surfaceRaised: Color = Color(0xFF2C2F36) // oklch(0.21 0.010 250) — selected drawer row

    // Dividers and outlines.
    public val divider: Color = Color(0xFF383B43) // oklch(0.24-0.28 0.010 250)

    // Text — three steps of emphasis, all high enough contrast on [surface] for WCAG 2.2 AA
    // (FR-A11Y-4) at body sizes.
    public val textHigh: Color = Color(0xFFEBECEF) // oklch(0.94 0.006 250)
    public val textMedium: Color = Color(0xFFD3D5D9) // oklch(0.86 0.006 250)
    public val textMuted: Color = Color(0xFF9A9FA8) // oklch(0.60-0.66 0.008 250)
    public val textLow: Color = Color(0xFF787C84) // oklch(0.50-0.55 0.008 250)

    // Accents. Reinforcement only, per AC-62 — never the sole carrier of a state.
    public val accentGreen: Color = Color(0xFF4FD9A0) // oklch(0.72 0.14 150) — confirmed / live / nominal
    public val accentAmber: Color = Color(0xFFD9A83F) // oklch(0.78 0.13 75) — ambiguous / gap / warning
}
