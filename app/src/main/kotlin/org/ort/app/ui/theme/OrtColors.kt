package org.ort.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Colour tokens, one per token in `design/design-guide.md` §3 (lifted from
 * `design/canvas/Tokens.dc.html`, the source of truth for the palette). The canvas specifies its
 * palette in OKLCH (`oklch(lightness chroma hue)`) for perceptual evenness; each value below is
 * the sRGB result of running that exact `oklch(L C H)` through the standard OKLab → linear sRGB →
 * gamma-encoded sRGB conversion (Björn Ottosson's published matrices, the same ones a browser uses
 * to render `oklch()` in CSS) — not a hand-picked approximation, so a reader can regenerate every
 * value here from the OKLCH quoted beside it. Do not treat this file as replacing the canvas — the
 * `.dc.html` files under `design/canvas/` remain the source of truth for the palette itself; where
 * this file and a board disagree, the board wins and this file is wrong (guide's own rule).
 *
 * Per constitution VII / AC-62 / FR-A11Y-1, none of these colours is ever the *only* signal for an
 * attribution state — see `ui/components/AttributionMarker.kt`. Contrast is proven, not assumed,
 * in `ui/theme/OrtColorsContrastTest.kt`.
 *
 * R-005 (ui-conformance-plan WP1): this is the full token set the guide requires. The original
 * eight names below §"Legacy aliases" are kept exactly as they were — other packages already
 * reference them — and now point at the precise token their own doc comment described.
 */
@Suppress("TooManyFunctions", "LargeClass")
public object OrtColors {

    // ---------------------------------------------------------------------------------------
    // Surfaces (guide §3 "Surfaces", plus the extra surfaces the boards use beyond that table).
    // ---------------------------------------------------------------------------------------

    /** The drawer's page ground, behind the scrim. oklch(0.11 0.006 250) */
    public val bgPage: Color = Color(0xFF030506)

    /** Every screen background. oklch(0.155 0.008 250) */
    public val bgScreen: Color = Color(0xFF0A0D10)

    /** Drawer sheet, live bar, log group header. oklch(0.185 0.009 250) */
    public val bgRaised: Color = Color(0xFF101317)

    /** Cards, state tiles. oklch(0.195 0.009 250) */
    public val bgCard: Color = Color(0xFF121519)

    /** Selected drawer row. oklch(0.26 0.012 250) */
    public val bgSelected: Color = Color(0xFF20252A)

    /** Selected filter chip. oklch(0.30 0.012 250) */
    public val bgChip: Color = Color(0xFF292E34)

    /** Score chip behind a confidence number. oklch(0.24 0.010 250) */
    public val bgScore: Color = Color(0xFF1C2024)

    /** A log row whose attribution is AMBIGUOUS. oklch(0.175 0.010 75) */
    public val bgRowAmbiguous: Color = Color(0xFF13100C)

    /** A "not listening" row. oklch(0.185 0.012 60) */
    public val bgRowGap: Color = Color(0xFF17110D)

    /** The waveform card. oklch(0.20 0.010 250) */
    public val bgAudio: Color = Color(0xFF13161A)

    /** The live bar. oklch(0.19 0.010 250) */
    public val bgLive: Color = Color(0xFF101418)

    /** A log group header. oklch(0.185 0.008 250) */
    public val bgGroup: Color = Color(0xFF101316)

    /** The row a screen is currently about. oklch(0.175 0.009 250) */
    public val bgCurrent: Color = Color(0xFF0E1114)

    /** A text action while pressed. oklch(0.22 0.010 250) */
    public val bgPressed: Color = Color(0xFF171B1F)

    /** The persistent notification's card ground. oklch(0.17 0.008 250) */
    public val bgNotification: Color = Color(0xFF0D1013)

    /** The shade the notification card sits on. oklch(0.09 0.006 250) */
    public val bgNotificationGround: Color = Color(0xFF020303)

    /** The fill of a prior that barely argued. oklch(0.48 0.05 250) */
    public val barNeutral: Color = Color(0xFF486079)

    // ---------------------------------------------------------------------------------------
    // Lines (guide §3 "Lines", plus line/control and line/handle from the "Text" table).
    // ---------------------------------------------------------------------------------------

    /** Live bar top edge, drawer right edge. oklch(0.28 0.010 250) */
    public val lineStrong: Color = Color(0xFF25292E)

    /** Section dividers in the drawer. oklch(0.26 0.010 250) */
    public val lineDefault: Color = Color(0xFF202429)

    /** Between page sections. oklch(0.24 0.010 250) */
    public val lineSection: Color = Color(0xFF1C2024)

    /** Between log rows. oklch(0.215 0.010 250) */
    public val lineRow: Color = Color(0xFF161A1E)

    /** Between station rows. oklch(0.21 0.010 250) */
    public val lineFaint: Color = Color(0xFF15191D)

    /** Unselected chip border. oklch(0.30 0.010 250) */
    public val lineChip: Color = Color(0xFF2A2E33)

    /** Unselected radio and checkbox border. oklch(0.40 0.008 250) */
    public val lineControl: Color = Color(0xFF44484C)

    /** The bottom-sheet drag handle. oklch(0.34 0.010 250) */
    public val lineHandle: Color = Color(0xFF34383D)

    // ---------------------------------------------------------------------------------------
    // Text — 21 steps, chosen by role, never by "looks about right" (guide §3 "Text").
    // ---------------------------------------------------------------------------------------

    /** Selected drawer row only. oklch(0.96 0.006 250) */
    public val textBright: Color = Color(0xFFEFF2F6)

    /** Titles, primary values. oklch(0.94 0.006 250) */
    public val textHigh: Color = Color(0xFFE8EBEF)

    /** Header icons — drawer, back. oklch(0.90 0.006 250) */
    public val textIcon: Color = Color(0xFFDBDEE2)

    /** An AMBIGUOUS callsign, a shade under CONFIRMED. oklch(0.88 0.006 250) */
    public val textAmbiguous: Color = Color(0xFFD5D8DB)

    /** Drawer rows, INFERRED callsigns, list copy. oklch(0.86 0.006 250) */
    public val textBody: Color = Color(0xFFCED1D5)

    /** Prior names in the inspection surface. oklch(0.80 0.006 250) */
    public val textPrior: Color = Color(0xFFBBBEC1)

    /** Transcript text, banner body. oklch(0.76 0.006 250) */
    public val textSecondary: Color = Color(0xFFAEB1B5)

    /** Partial text in the live bar. oklch(0.72 0.006 250) */
    public val textLive: Color = Color(0xFFA2A5A8)

    /** The dismiss glyph inside a chip. oklch(0.70 0.008 250) */
    public val textChipX: Color = Color(0xFF9B9FA3)

    /** Unselected drawer icons, device icons. oklch(0.68 0.008 250) */
    public val textIconDim: Color = Color(0xFF95999D)

    /** Card body copy, unselected chip label. oklch(0.66 0.008 250) */
    public val textMuted: Color = Color(0xFF8F9397)

    /** Subtitles, metadata, sub-lines. oklch(0.62 0.008 250) */
    public val textDim: Color = Color(0xFF83878B)

    /** Time and frequency columns in a log row. oklch(0.60 0.008 250) */
    public val textTime: Color = Color(0xFF7D8185)

    /** Section labels, secondary sub-lines. oklch(0.58 0.008 250) */
    public val textFaint: Color = Color(0xFF777B7F)

    /** Score chips, counts beside a list row. oklch(0.55 0.008 250) */
    public val textFigure: Color = Color(0xFF6E7276)

    /** "unknown station", axis labels, artboard ids. oklch(0.52 0.008 250) */
    public val textLow: Color = Color(0xFF66696D)

    /** Signal figures, chevrons, recent-search icon. oklch(0.50 0.008 250) */
    public val textSignal: Color = Color(0xFF606468)

    /** Column headers, disabled text actions. oklch(0.46 0.008 250) */
    public val textDisabled: Color = Color(0xFF55585C)

    /** The UNKNOWN attribution dot. oklch(0.45 0.008 250) */
    public val markerUnknown: Color = Color(0xFF52565A)

    // ---------------------------------------------------------------------------------------
    // Green accent family — CONFIRMED, live, nominal (guide §3 "Accents").
    // ---------------------------------------------------------------------------------------

    /** CONFIRMED marker, live dot, links, nominal, primary fill. oklch(0.72 0.14 150) */
    public val accentGreen: Color = Color(0xFF5BBD74)

    /** Link hover, pressed text action. oklch(0.80 0.14 150) */
    public val accentGreenHover: Color = Color(0xFF75D78D)

    /** Elapsed time beside the live dot, "verified" labels. oklch(0.68 0.10 150) */
    public val accentGreenDim: Color = Color(0xFF69AA77)

    /** Text on a green fill. oklch(0.16 0.01 150) */
    public val accentOnGreen: Color = Color(0xFF0A0F0B)

    /** Speech bars in a waveform card. oklch(0.68 0.13 150) */
    public val waveSpeech: Color = Color(0xFF56AE6C)

    /** A lattice slot score that is fine. oklch(0.68 0.11 150) */
    public val scoreGood: Color = Color(0xFF63AB74)

    /** The live-bar meter with nothing to hear. oklch(0.60 0.10 150) */
    public val meterIdle: Color = Color(0xFF519160)

    /** The callsign span inside a transcript. oklch(0.22 0.04 150) */
    public val highlightGreen: Color = Color(0xFF0B2010)

    /** A card border that means "this is the good one". oklch(0.34 0.06 150) */
    public val cardOkBorder: Color = Color(0xFF1F4127)

    // ---------------------------------------------------------------------------------------
    // Amber accent family — AMBIGUOUS, gap, warning, degradation (guide §3 "Accents").
    // ---------------------------------------------------------------------------------------

    /** AMBIGUOUS marker, alternate candidate, warning badge, live dot when degraded. oklch(0.78 0.13 75) */
    public val accentAmber: Color = Color(0xFFE8AA4E)

    /** Text on an amber fill. oklch(0.16 0.01 75) */
    public val accentOnAmber: Color = Color(0xFF100D09)

    /** Degraded live-bar text. oklch(0.74 0.07 60) */
    public val accentAmberDim: Color = Color(0xFFCCA17E)

    /** "not listening" text and icon. oklch(0.70 0.09 60) */
    public val accentGap: Color = Color(0xFFC89164)

    /** Amber explanatory text, "revised" badge text. oklch(0.62 0.05 60) */
    public val accentAmberText: Color = Color(0xFF9D7F68)

    /** Meter bars below the band. oklch(0.62 0.10 60) */
    public val meterWarn: Color = Color(0xFFB27744)

    /** The hatch pattern in a gap bar, gap-row icon. oklch(0.55 0.07 60) */
    public val accentGapDim: Color = Color(0xFF906847)

    /** The hatch stripe in a full-height not-listening bar. oklch(0.40 0.055 60) */
    public val hatchBar: Color = Color(0xFF5E4028)

    /** The "revised" badge outline. oklch(0.40 0.04 60) */
    public val badgeRevisedBorder: Color = Color(0xFF584332)

    /** Border on a degradation banner. oklch(0.38 0.06 60) */
    public val bannerAmberBorder: Color = Color(0xFF5A3A1F)

    /** An uncertain span inside a transcript. oklch(0.22 0.04 75) */
    public val highlightAmber: Color = Color(0xFF251804)

    /** Border on a lattice slot below threshold. oklch(0.42 0.07 75) */
    public val latticeUncertain: Color = Color(0xFF63471C)

    // ---------------------------------------------------------------------------------------
    // Halt / red family — capture has stopped and the operator must act. Never a degradation.
    // ---------------------------------------------------------------------------------------

    /** Text and icon on a halting banner, the Stop action. oklch(0.72 0.15 25) */
    public val haltText: Color = Color(0xFFF47B74)

    /** A destructive button, a failed-check dot. oklch(0.60 0.16 25) */
    public val haltFill: Color = Color(0xFFCE514D)

    /** Text on [haltFill]. oklch(0.16 0.01 25) */
    public val haltOnFill: Color = Color(0xFF110C0B)

    /** Border on a halting banner or dialog. oklch(0.42 0.10 25) */
    public val haltBorder: Color = Color(0xFF7A3430)

    /** Ground of a halting banner. oklch(0.20 0.045 25) */
    public val haltBg: Color = Color(0xFF270D0B)

    // ---------------------------------------------------------------------------------------
    // Chart ramps (guide §3 "Chart ramps" — activity charts, hour × day grids, FR-UI-11/12).
    // ---------------------------------------------------------------------------------------

    /** Green by intensity, brightest first: 0.72 0.14 150 → 0.46 0.055 150. */
    public val chartGreenRamp: List<Color> = listOf(
        accentGreen,
        Color(0xFF50A064), // oklch(0.64 0.12 150)
        Color(0xFF4B8B5A), // oklch(0.58 0.10 150)
        Color(0xFF457650), // oklch(0.52 0.08 150)
        Color(0xFF416148), // oklch(0.46 0.055 150)
    )

    /** Amber by intensity — a departure from usual, a queue growing: 0.78 0.13 75 → 0.62 0.10 60. */
    public val chartAmberRamp: List<Color> = listOf(
        accentAmber,
        Color(0xFFD3A056), // oklch(0.74 0.11 75)
        accentGap, // oklch(0.70 0.09 60)
        meterWarn, // oklch(0.62 0.10 60)
    )

    /** Neutral for low activity: 0.42 0.04 250 → 0.34 0.02 250. */
    public val chartNeutralRamp: List<Color> = listOf(
        Color(0xFF3C4F62), // oklch(0.42 0.04 250)
        Color(0xFF414F5D), // oklch(0.42 0.03 250)
        Color(0xFF3C4958), // oklch(0.40 0.03 250)
        Color(0xFF3B434D), // oklch(0.38 0.02 250)
        Color(0xFF353E47), // oklch(0.36 0.02 250)
        Color(0xFF303942), // oklch(0.34 0.02 250)
    )

    /** A listened-but-silent cell in an hour × day grid. oklch(0.30 0.01 250) */
    public val chartNeutralListenedSilent: Color = Color(0xFF2A2E33)

    /** Waveform card quiet bars, brighter of the pair. oklch(0.46 0.03 250) */
    public val waveformQuiet1: Color = Color(0xFF4C5A69)

    /** Waveform card quiet bars, dimmer of the pair. oklch(0.42 0.02 250) */
    public val waveformQuiet2: Color = Color(0xFF454E58)

    // ---------------------------------------------------------------------------------------
    // Legacy aliases — the eight names the reader already depended on before R-005. Kept exactly
    // as-is (other WP2-WP11 packages reference these by name) and repointed to the precise token
    // each one's own doc comment already described.
    // ---------------------------------------------------------------------------------------

    /** = [bgPage]. Menu.dc.html's page background. */
    public val background: Color get() = bgPage

    /** = [bgScreen]. Main/Log/Detail background. */
    public val surface: Color get() = bgScreen

    /** = [bgRaised]. Drawer, cards. */
    public val surfaceVariant: Color get() = bgRaised

    /** = [bgSelected]. Selected drawer row. */
    public val surfaceRaised: Color get() = bgSelected

    /** = [lineDefault]. Dividers and outlines. */
    public val divider: Color get() = lineDefault

    /** = [textBody]. */
    public val textMedium: Color get() = textBody
}
