package org.ort.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * FR-A11Y-4: "Contrast SHALL meet WCAG 2.2 AA for text and for the state markers." This is a
 * genuine, computed proof rather than an eyeballed palette — it implements the WCAG 2.2 relative
 * luminance / contrast-ratio formula directly against [OrtColors]' real sRGB values and asserts
 * the actual numeric thresholds AA sets, not merely that the colours differ.
 *
 * This file predates ui-conformance-plan R-005 (its original tests, kept below, check the small
 * eight-token palette that shipped before R-005) — R-005 then landed the full token set in
 * `OrtColors.kt` (surfaces, lines, 21 text steps, both accent families, the halt colour family,
 * chart ramps) and kept every one of those eight original names as an exact-value alias of a precise new
 * token, so the original tests below still hold unmodified; the tests R-005 added prove the same
 * facts against the new canonical names directly:
 * - **4.5:1 (SC 1.4.3, normal text)** for every `OrtColors.text*` token that renders prose against
 *   a real screen ground (`bgScreen`/`surface`).
 * - **3:1 (SC 1.4.11, non-text contrast)** for the state-marker/graphical accents
 *   ([OrtColors.accentGreen], [OrtColors.accentAmber], [OrtColors.haltFill]) against the same
 *   ground, and for [OrtColors.textLow] where `ActivityPatternChart` uses it as a bar fill rather
 *   than text.
 *
 * If a future palette change ever regresses one of these pairs below its real threshold, this
 * test fails with the actual computed ratio, not a "looks fine" judgement.
 */
class OrtColorsContrastTest {

    /** WCAG 2.2's sRGB-to-linear channel transform (the formula the spec defines, not an approximation). */
    private fun linearize(channel: Float): Double {
        val c = channel.toDouble()
        return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }

    private fun relativeLuminance(color: Color): Double =
        0.2126 * linearize(color.red) + 0.7152 * linearize(color.green) + 0.0722 * linearize(color.blue)

    /** WCAG 2.2's contrast-ratio formula: (L1 + 0.05) / (L2 + 0.05), lighter over darker. */
    private fun contrastRatio(a: Color, b: Color): Double {
        val l1 = relativeLuminance(a)
        val l2 = relativeLuminance(b)
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private val normalTextMinimum = 4.5
    private val nonTextMinimum = 3.0

    // --- Original (pre-R-005) coverage of the eight-token palette, via its legacy alias names ---

    @Test
    fun `FR_A11Y_4 textHigh meets 4-5-to-1 normal-text contrast against every surface it renders on`() {
        listOf(OrtColors.background, OrtColors.surface, OrtColors.surfaceVariant, OrtColors.surfaceRaised)
            .forEach { surface ->
                val ratio = contrastRatio(OrtColors.textHigh, surface)
                assertTrue(ratio >= normalTextMinimum, "textHigh on $surface: ratio $ratio below $normalTextMinimum")
            }
    }

    @Test
    fun `FR_A11Y_4 textMedium meets 4-5-to-1 normal-text contrast where Theme-kt uses it`() {
        // Theme.kt: onSurface / onSurfaceVariant.
        listOf(OrtColors.surface, OrtColors.surfaceVariant).forEach { surface ->
            val ratio = contrastRatio(OrtColors.textMedium, surface)
            assertTrue(ratio >= normalTextMinimum, "textMedium on $surface: ratio $ratio below $normalTextMinimum")
        }
    }

    @Test
    fun `FR_A11Y_4 textMuted meets 4-5-to-1 normal-text contrast where it renders text in ActivityPatternChart`() {
        val ratio = contrastRatio(OrtColors.textMuted, OrtColors.surface)
        assertTrue(ratio >= normalTextMinimum, "textMuted on surface: ratio $ratio below $normalTextMinimum")
    }

    @Test
    fun `FR_A11Y_4 the state-marker accent colours meet 3-to-1 non-text contrast against surface`() {
        listOf(OrtColors.accentGreen, OrtColors.accentAmber).forEach { accent ->
            val ratio = contrastRatio(accent, OrtColors.surface)
            assertTrue(ratio >= nonTextMinimum, "$accent on surface: ratio $ratio below $nonTextMinimum")
        }
    }

    // --- R-005: the full token set, proven against the new canonical names directly ---

    /** Every text step bright enough to be used as normal prose — guide §3's "Text" table down to
     * [OrtColors.textDim] (subtitles, metadata; the dimmest role still used as running text). */
    @Test
    fun `FR_A11Y_4 every prose text token meets 4-5-to-1 normal-text contrast on bg_screen`() {
        listOf(
            OrtColors.textBright,
            OrtColors.textHigh,
            OrtColors.textAmbiguous,
            OrtColors.textBody,
            OrtColors.textPrior,
            OrtColors.textSecondary,
            OrtColors.textMuted,
            OrtColors.textDim,
        ).forEach { text ->
            val ratio = contrastRatio(text, OrtColors.bgScreen)
            assertTrue(ratio >= normalTextMinimum, "$text on bgScreen: ratio $ratio below $normalTextMinimum")
        }
    }

    @Test
    fun `R_005 textHigh meets 4-5-to-1 normal-text contrast against every raised surface it renders on`() {
        listOf(OrtColors.bgPage, OrtColors.bgScreen, OrtColors.bgRaised, OrtColors.bgCard, OrtColors.bgSelected)
            .forEach { surface ->
                val ratio = contrastRatio(OrtColors.textHigh, surface)
                assertTrue(ratio >= normalTextMinimum, "textHigh on $surface: ratio $ratio below $normalTextMinimum")
            }
    }

    @Test
    fun `R_005 legacy alias textMedium still meets 4-5-to-1 contrast on surface`() {
        val ratio = contrastRatio(OrtColors.textMedium, OrtColors.surface)
        assertTrue(ratio >= normalTextMinimum, "textMedium on surface: ratio $ratio below $normalTextMinimum")
    }

    @Test
    fun `FR_A11Y_4 the state-marker accent colours meet 3-to-1 non-text contrast against bg_screen`() {
        listOf(OrtColors.accentGreen, OrtColors.accentAmber, OrtColors.haltFill).forEach { accent ->
            val ratio = contrastRatio(accent, OrtColors.bgScreen)
            assertTrue(ratio >= nonTextMinimum, "$accent on bgScreen: ratio $ratio below $nonTextMinimum")
        }
    }

    @Test
    fun `FR_A11Y_4 textLow meets 3-to-1 non-text contrast where ActivityPatternChart uses it as a bar fill`() {
        val ratio = contrastRatio(OrtColors.textLow, OrtColors.surface)
        assertTrue(ratio >= nonTextMinimum, "textLow on surface: ratio $ratio below $nonTextMinimum")
    }

    @Test
    fun `R_005 legacy names still resolve to the same Color as the precise token they alias`() {
        assertTrue(OrtColors.background == OrtColors.bgPage)
        assertTrue(OrtColors.surface == OrtColors.bgScreen)
        assertTrue(OrtColors.surfaceVariant == OrtColors.bgRaised)
        assertTrue(OrtColors.surfaceRaised == OrtColors.bgSelected)
        assertTrue(OrtColors.divider == OrtColors.lineDefault)
        assertTrue(OrtColors.textMedium == OrtColors.textBody)
    }

    // --- R-1089: every token pair the guide defines, in one place, so this class of defect
    // (a token that individually looks fine but was never actually run through the WCAG formula)
    // cannot come back silently on a future palette edit without this test naming it. Constitution
    // VII, FR-A11Y. The P32 accessibility pass measured six failing pairs by this exact
    // computation; this table is a superset — every text step against the ground it actually
    // renders on, every non-text marker/line against the ground it actually renders on — so a
    // regression anywhere in the ramp fails loudly with the real ratio, not just the six known at
    // the time this was written. ---

    private data class TokenPair(val name: String, val fg: Color, val bg: Color, val floor: Double, val bgName: String)

    /** Guide §3: "WCAG 2.2 AA contrast for text, 3:1 for markers and bar fills." Every pair below
     * is named for the token, the ground it is measured against (per its own `Use` column in
     * `design/design-guide.md` §3), and the floor that applies to that use — 4.5:1 normal text,
     * 3:1 for a UI component (a line/border) or a graphical object required to understand content
     * (a state marker). */
    private val allGuideTokenPairs: List<TokenPair> = listOf(
        // Every "Text" step actually used as prose against bg/screen ("every screen background")
        // — normal text, 4.5:1 (SC 1.4.3).
        TokenPair("text/bright", OrtColors.textBright, OrtColors.bgScreen, normalTextMinimum, "bg/screen"),
        TokenPair("text/high", OrtColors.textHigh, OrtColors.bgScreen, normalTextMinimum, "bg/screen"),
        TokenPair("text/icon", OrtColors.textIcon, OrtColors.bgScreen, normalTextMinimum, "bg/screen"),
        TokenPair("text/ambiguous", OrtColors.textAmbiguous, OrtColors.bgScreen, normalTextMinimum, "bg/screen"),
        TokenPair("text/body", OrtColors.textBody, OrtColors.bgScreen, normalTextMinimum, "bg/screen"),
        TokenPair("text/prior", OrtColors.textPrior, OrtColors.bgScreen, normalTextMinimum, "bg/screen"),
        TokenPair("text/secondary", OrtColors.textSecondary, OrtColors.bgScreen, normalTextMinimum, "bg/screen"),
        TokenPair("text/live", OrtColors.textLive, OrtColors.bgScreen, normalTextMinimum, "bg/screen"),
        TokenPair("text/chip-x", OrtColors.textChipX, OrtColors.bgScreen, normalTextMinimum, "bg/screen"),
        TokenPair("text/icon-dim", OrtColors.textIconDim, OrtColors.bgScreen, normalTextMinimum, "bg/screen"),
        TokenPair("text/muted", OrtColors.textMuted, OrtColors.bgScreen, normalTextMinimum, "bg/screen"),
        TokenPair("text/dim", OrtColors.textDim, OrtColors.bgScreen, normalTextMinimum, "bg/screen"),
        TokenPair("text/time", OrtColors.textTime, OrtColors.bgScreen, normalTextMinimum, "bg/screen"),
        TokenPair("text/faint", OrtColors.textFaint, OrtColors.bgScreen, normalTextMinimum, "bg/screen"),
        // R-1089's six: text/figure, text/low, text/signal and text/disabled render real prose at
        // normal (non-large) sizes — score-chip values and list counts (text/figure), "unknown
        // station"/axis labels at 13sp in AttributionRow/TitleAttributionRow (text/low), signal
        // figures/a 14sp placeholder in Controls.kt (text/signal), column headers at 9.5sp
        // (text/disabled, ModelsScreen/StationScreen/ThreadDetailScreen/FrequencyScreen) — every
        // one of those is normal text, so 4.5:1 is the real floor, not the 3:1 a marker gets.
        TokenPair("text/figure", OrtColors.textFigure, OrtColors.bgScreen, normalTextMinimum, "bg/screen"),
        TokenPair("text/low", OrtColors.textLow, OrtColors.bgScreen, normalTextMinimum, "bg/screen"),
        TokenPair("text/signal", OrtColors.textSignal, OrtColors.bgScreen, normalTextMinimum, "bg/screen"),
        TokenPair("text/disabled", OrtColors.textDisabled, OrtColors.bgScreen, normalTextMinimum, "bg/screen"),
        // text/figure's other named ground (guide: "Score chips ... beside a list row") — the
        // score chip itself sits on bg/score, a lighter surface than bg/screen, so this is the
        // harder of its two pairs.
        TokenPair("text/figure", OrtColors.textFigure, OrtColors.bgScore, normalTextMinimum, "bg/score"),
        // Non-text: a UI component border or a graphical state marker, 3:1 (SC 1.4.11).
        TokenPair("accent/green", OrtColors.accentGreen, OrtColors.bgScreen, nonTextMinimum, "bg/screen"),
        TokenPair("accent/amber", OrtColors.accentAmber, OrtColors.bgScreen, nonTextMinimum, "bg/screen"),
        TokenPair("halt/fill", OrtColors.haltFill, OrtColors.bgScreen, nonTextMinimum, "bg/screen"),
        TokenPair("marker/unknown", OrtColors.markerUnknown, OrtColors.bgScreen, nonTextMinimum, "bg/screen"),
        TokenPair("line/control", OrtColors.lineControl, OrtColors.bgScreen, nonTextMinimum, "bg/screen"),
        // text/low doubles as ActivityPatternChart's bar fill — a graphical object, 3:1.
        TokenPair("text/low (bar fill)", OrtColors.textLow, OrtColors.bgScreen, nonTextMinimum, "bg/screen"),
    )

    @Test
    fun `R_1089 every token pair the guide defines meets its WCAG 2-2 AA floor`() {
        val failures = allGuideTokenPairs.mapNotNull { pair ->
            val ratio = contrastRatio(pair.fg, pair.bg)
            if (ratio < pair.floor) {
                "${pair.name} on ${pair.bgName}: computed $ratio, needs ${pair.floor}"
            } else {
                null
            }
        }
        assertTrue(failures.isEmpty(), "AA floor violated for:\n" + failures.joinToString("\n"))
    }
}
