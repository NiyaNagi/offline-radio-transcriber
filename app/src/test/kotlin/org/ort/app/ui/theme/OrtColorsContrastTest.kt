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
}
