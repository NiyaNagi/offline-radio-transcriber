package org.ort.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Typography, one named [TextStyle] per row of `design/design-guide.md` §4 (lifted from
 * `design/canvas/Type.dc.html`). The canvas specifies IBM Plex Sans/Mono; those are Google Fonts
 * loaded at design time only — pulling a network font into the shipped app would violate
 * constitution V (no network in any path but the declared ones), so the reader uses the
 * platform's default sans/monospace families, matched for weight and letter-spacing instead of
 * typeface, exactly as the canvas's own `Type.dc.html` documents this substitution is fine
 * ("scaling" panel — the size/weight/tracking relationships are what's binding, not the glyphs).
 *
 * Every size and every tracking value here is in `sp`, not `dp` — the unit that scales with
 * system font size (FR-A11Y-3, AC-63): a `-0.022em` tracking at 27px becomes `27 * -0.022 =
 * -0.594.sp`, computed the same way for every row below. Fixed-height containers are avoided
 * throughout `ui/` for the same reason: at maximum font scale a row must be allowed to grow, not
 * clip. A row the guide marks "—" for tracking or does not give a line-height for is left at
 * Compose's own default (unspecified) rather than an invented number.
 *
 * Three rows carry a weight that changes with state (`rowTitle`, `control`, `subtitle`,
 * `transcript`/`textAction`, `chip`: "400 (500 selected)" in the guide) — the [TextStyle] below is
 * the base (400) weight; a call site renders the emphasised state with
 * `style.copy(fontWeight = FontWeight.Medium)` rather than a second named constant, since the
 * guide describes it as a state of the same role, not a different role. Likewise "Sans; **Mono**
 * when the value is a callsign or frequency" ([control]) is a per-value override a call site
 * applies with `style.copy(fontFamily = OrtType.mono)`, not a second constant.
 *
 * Three roles the guide marks uppercase ([sectionLabel], [badge], [columnHeader]) carry no
 * built-in text transform — Compose [TextStyle] has none — so a call site renders
 * `text.uppercase()` itself; this mirrors how the original file already left casing to callers.
 *
 * R-005 (ui-conformance-plan WP1): [typography] now maps `bodyMedium` to [control] (14sp, not the
 * 12.5sp [cardBody] it was mapped to before), `bodyLarge` to [bodyProse] (15sp), `bodySmall` to
 * [cardBody] (12.5sp), `labelSmall` to [sectionLabel] (11sp), `titleLarge` to [screenTitle]
 * (27sp) and `titleMedium` to [cardTitle] (19sp) — the sizes guide §4 requires for each M3 slot
 * every other package's `MaterialTheme.typography.*` call already resolves through.
 */
public object OrtType {
    public val sans: FontFamily = FontFamily.SansSerif
    public val mono: FontFamily = FontFamily.Monospace

    /** Screen titles — "Overnight", "Log". 27 / 600 / −0.022em / Sans. */
    public val screenTitle: TextStyle = TextStyle(
        fontFamily = sans,
        fontSize = 27.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.594).sp,
    )

    /** A callsign standing in as the screen title (`Detail.dc.html`). 27 / 600 / −0.01em / Mono. */
    public val callsignTitle: TextStyle = TextStyle(
        fontFamily = mono,
        fontSize = 27.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.27).sp,
    )

    /** Card / sheet title. 19 / 600 / −0.01em / Sans. */
    public val cardTitle: TextStyle = TextStyle(
        fontFamily = sans,
        fontSize = 19.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.19).sp,
    )

    /** A frequency or count as the point of a tile. 17 / 600 / — / Mono. */
    public val figure: TextStyle = TextStyle(
        fontFamily = mono,
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
    )

    /** Drawer title. 16 / 600 / −0.01em / Sans. */
    public val drawerTitle: TextStyle = TextStyle(
        fontFamily = sans,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.16).sp,
    )

    /** Digest item, body prose, button label. 15 / 400 (500 selected) / — / Sans, line-height 1.4. */
    public val bodyProse: TextStyle = TextStyle(
        fontFamily = sans,
        fontSize = 15.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 21.sp,
    )

    /** Drawer rows, settings rows, list items. 14.5 / 400 (500 selected) / — / Sans. */
    public val rowTitle: TextStyle = TextStyle(
        fontFamily = sans,
        fontSize = 14.5.sp,
        fontWeight = FontWeight.Normal,
    )

    /** Control, field, dialog body — Sans; **Mono** when the value is a callsign or frequency. 14 / 400 (500). */
    public val control: TextStyle = TextStyle(
        fontFamily = sans,
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
    )

    /** Subtitle, banner title. 13.5 / 400 (500) / — / Sans. */
    public val subtitle: TextStyle = TextStyle(
        fontFamily = sans,
        fontSize = 13.5.sp,
        fontWeight = FontWeight.Normal,
    )

    /** Transcript row. 13 / 400 (500) / — / Sans, line-height 1.35. */
    public val transcript: TextStyle = TextStyle(
        fontFamily = sans,
        fontSize = 13.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 17.55.sp,
    )

    /** Text action ("Filter", "Save") — same guide row as [transcript]: 13 / 400 (500) / Sans, line-height 1.35. */
    public val textAction: TextStyle = TextStyle(
        fontFamily = sans,
        fontSize = 13.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 17.55.sp,
    )

    /** Card body, sub-line. 12.5 / 400 / — / Sans, line-height 1.5. */
    public val cardBody: TextStyle = TextStyle(
        fontFamily = sans,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 18.75.sp,
    )

    /** Chip label, metadata. 12 / 400 (500 selected) / — / Sans. */
    public val chip: TextStyle = TextStyle(
        fontFamily = sans,
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal,
    )

    /** Secondary sub-line. 11.5 / 400 / — / Sans. */
    public val subLine: TextStyle = TextStyle(
        fontFamily = sans,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.Normal,
    )

    /** Section labels — "Worth knowing", "Stations heard". Render uppercase. 11 / 600 / +0.08em / Sans. */
    public val sectionLabel: TextStyle = TextStyle(
        fontFamily = sans,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.88.sp,
    )

    /** Callsign in a log/list row. 13.5 / 600 / — / Mono. */
    public val callsignRow: TextStyle = TextStyle(
        fontFamily = mono,
        fontSize = 13.5.sp,
        fontWeight = FontWeight.SemiBold,
    )

    /** Callsign on a card. 13 / 600 / — / Mono. */
    public val callsignCard: TextStyle = TextStyle(
        fontFamily = mono,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
    )

    /** Time and frequency columns. 12 / 400 / — / Mono. */
    public val timeFreq: TextStyle = TextStyle(
        fontFamily = mono,
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal,
    )

    /** Signal figures, counts, step counter ("n of N"). 11 / 400 / — / Mono. */
    public val signal: TextStyle = TextStyle(
        fontFamily = mono,
        fontSize = 11.sp,
        fontWeight = FontWeight.Normal,
    )

    /** Score chip, small annotation. 10.5 / 400 / — / Mono. */
    public val scoreChip: TextStyle = TextStyle(
        fontFamily = mono,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.Normal,
    )

    /** Badge (`NEW`, `REVISED`, tier chip). Render uppercase. 10 / 600 / +0.05em / Sans. */
    public val badge: TextStyle = TextStyle(
        fontFamily = sans,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp,
    )

    /** Axis label on a chart. 10 / 400 / — / Mono. */
    public val axis: TextStyle = TextStyle(
        fontFamily = mono,
        fontSize = 10.sp,
        fontWeight = FontWeight.Normal,
    )

    /** Column header, artboard id. Render uppercase. 9.5 / 400 / +0.08–0.09em / Mono. */
    public val columnHeader: TextStyle = TextStyle(
        fontFamily = mono,
        fontSize = 9.5.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.81.sp,
    )

    // ---------------------------------------------------------------------------------------
    // Legacy aliases — the five names the reader already depended on before R-005, kept exactly
    // as names (other packages reference them) and repointed at the exact or closest-matching
    // named row above.
    // ---------------------------------------------------------------------------------------

    /** = [screenTitle] (already an exact match: 27 / 600 / −0.022em / Sans). */
    public val titleLarge: TextStyle = screenTitle

    /** = [callsignRow] — closest named row to the old generic 14sp/600/mono "a callsign, monospace". */
    public val callsign: TextStyle = callsignRow

    /** = [bodyProse] (already an exact match: 15 / lineHeight 21 / Sans). */
    public val body: TextStyle = bodyProse

    /** = [cardBody] (same 12.5sp size; guide's line-height for this role is 1.5, i.e. 18.75sp). */
    public val caption: TextStyle = cardBody

    public val typography: Typography = Typography(
        titleLarge = screenTitle,
        titleMedium = cardTitle,
        bodyLarge = bodyProse,
        bodyMedium = control,
        bodySmall = cardBody,
        labelSmall = sectionLabel,
    )
}
