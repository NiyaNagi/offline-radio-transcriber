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
 * Two different thresholds apply, per how a colour is actually used in `:app`'s main source
 * (checked against every real call site, not assumed):
 * - **4.5:1 (SC 1.4.3, normal text)** for every `OrtColors.text*` token used to render `Text`
 *   composables: [OrtColors.textHigh]/[OrtColors.textMedium] (`Theme.kt`'s `onBackground`/
 *   `onSurface`/`onSurfaceVariant`) and [OrtColors.textMuted] (the "ACTIVITY BY HOUR" section
 *   label in `ActivityPatternChart.kt`).
 * - **3:1 (SC 1.4.11, non-text contrast)** for [OrtColors.accentGreen]/[OrtColors.accentAmber] and
 *   [OrtColors.textLow], which `AttributionMarker.kt` and `ActivityPatternChart.kt` use only to
 *   fill graphical shapes (a state marker dot, a chart bar) — never to render text.
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

    @Test
    fun `FR_A11Y_4 textLow meets 3-to-1 non-text contrast where ActivityPatternChart uses it as a bar fill`() {
        val ratio = contrastRatio(OrtColors.textLow, OrtColors.surface)
        assertTrue(ratio >= nonTextMinimum, "textLow on surface: ratio $ratio below $nonTextMinimum")
    }
}
