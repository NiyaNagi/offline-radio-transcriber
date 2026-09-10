package org.ort.app.ui.theme

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric-driven half of the scale finding (2026-09-09, see `OrtThemeScaleArithmeticTest`'s
 * own doc comment for the full finding and the evidence paths). Each case here drives a real
 * measured layout via `@Config(qualifiers = ...)` — a genuine `Configuration.screenWidthDp` from
 * Robolectric, not a call into [ortScaleFor] directly — so the composition wiring in `OrtTheme`
 * (reading [androidx.compose.ui.platform.LocalConfiguration], applying the scale to
 * [LocalDensity]) is proven, not just the arithmetic.
 *
 * Density is captured *before* entering [OrtTheme] (the ambient value the device/qualifiers
 * establish) and *inside* it (after `OrtTheme`'s own [CompositionLocalProvider]); the ratio of
 * the two is the scale actually applied, independent of which density bucket (`mdpi`/`xxhdpi`/…)
 * the qualifier string also names — the qualifier's density bucket is incidental to this test,
 * only its `wNNNdp` width matters.
 */
@RunWith(RobolectricTestRunner::class)
class OrtThemeScaleTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun capturedScale(providedFontScale: Float? = null): Pair<Float, Float> {
        var outerDensity = 0f
        var innerDensity = 0f
        var innerFontScale = 0f
        composeTestRule.setContent {
            if (providedFontScale != null) {
                val platformDensity = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(platformDensity.density, providedFontScale),
                ) {
                    outerDensity = LocalDensity.current.density
                    OrtTheme {
                        val density = LocalDensity.current.density
                        val fontScale = LocalDensity.current.fontScale
                        SideEffect {
                            innerDensity = density
                            innerFontScale = fontScale
                        }
                        Spacer(modifier = Modifier.size(1.dp))
                    }
                }
            } else {
                outerDensity = LocalDensity.current.density
                OrtTheme {
                    val density = LocalDensity.current.density
                    val fontScale = LocalDensity.current.fontScale
                    SideEffect {
                        innerDensity = density
                        innerFontScale = fontScale
                    }
                    Spacer(modifier = Modifier.size(1.dp))
                }
            }
        }
        composeTestRule.waitForIdle()
        return Pair(innerDensity / outerDensity, innerFontScale)
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun `R_600 scale is 1_0 at exactly the board width, 390dp`() {
        val (scale, _) = capturedScale()
        assertEquals(1.0f, scale, TOLERANCE)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xhdpi")
    fun `R_600 scale is 1_0 below the board width, 360dp`() {
        val (scale, _) = capturedScale()
        assertEquals(1.0f, scale, TOLERANCE)
    }

    @Test
    @Config(qualifiers = "w480dp-h1056dp-xxhdpi")
    fun `R_600 scale is approximately 1_23 at 480dp, the Find X9 Ultra width`() {
        val (scale, _) = capturedScale()
        assertEquals(1.2307f, scale, TOLERANCE)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `R_600 scale is approximately 1_05 at 411dp, the audit emulator width`() {
        val (scale, _) = capturedScale()
        assertEquals(1.0538f, scale, TOLERANCE)
    }

    @Test
    @Config(qualifiers = "w600dp-h800dp-mdpi")
    fun `R_600 scale clamps at 1_35 for a 600dp screen`() {
        val (scale, _) = capturedScale()
        assertEquals(1.35f, scale, TOLERANCE)
    }

    @Test
    @Config(qualifiers = "w480dp-h1056dp-xxhdpi")
    fun `R_600 fontScale survives unchanged at 1_0 through the scaled density`() {
        val (_, fontScale) = capturedScale(providedFontScale = 1f)
        assertEquals(1.0f, fontScale, TOLERANCE)
    }

    @Test
    @Config(qualifiers = "w480dp-h1056dp-xxhdpi")
    fun `R_600 fontScale survives unchanged at 2_0 through the scaled density`() {
        val (scale, fontScale) = capturedScale(providedFontScale = 2f)
        assertEquals(2.0f, fontScale, TOLERANCE)
        assertEquals(1.2307f, scale, TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 0.01f
    }
}
