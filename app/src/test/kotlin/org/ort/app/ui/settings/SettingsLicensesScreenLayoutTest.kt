package org.ort.app.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * P27 (constitution VIII): the licence-notices list's own rows at the tour's real width, at both
 * font scales the visual re-verification requires — every row must occupy real, non-overlapping
 * space, so a row's sub-line can never silently run into the next row's title the way R-1044 found
 * for a sibling Settings screen.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsLicensesScreenLayoutTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun assertRowsDoNotOverlap(fontScale: Float) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = fontScale)) {
                OrtTheme { SettingsLicensesScreen(context = context, onBack = {}) }
            }
        }
        val bounds = BundledLicenceNotices.ALL.map { entry ->
            composeTestRule.onNodeWithContentDescription("${entry.name}. ${entry.licenceLabel}")
                .getUnclippedBoundsInRoot()
        }
        for (i in 0 until bounds.size - 1) {
            val gap = (bounds[i + 1].top - bounds[i].bottom).value
            assert(gap >= 0f) {
                "expected row $i (${BundledLicenceNotices.ALL[i].name}) and row ${i + 1} " +
                    "(${BundledLicenceNotices.ALL[i + 1].name}) not to overlap at fontScale=$fontScale, " +
                    "got gap=${gap}dp (${bounds[i]} vs ${bounds[i + 1]})"
            }
        }
    }

    @Test
    @Requirement("AC-167")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `AC_167 every licence row occupies non-overlapping space at font scale 1_0`() {
        assertRowsDoNotOverlap(fontScale = 1f)
    }

    @Test
    @Requirement("AC-167")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `AC_167 every licence row occupies non-overlapping space at font scale 2_0`() {
        assertRowsDoNotOverlap(fontScale = 2f)
    }
}
