package org.ort.app.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * P32 (FR-A11Y-2, the R-1073 pattern) — split out of `RowsTest.kt` (detekt's own `LargeClass`
 * finding, the same reason `LogRowResponsiveTest.kt` exists as its own file).
 *
 * Found by re-reading `DrillInHeader` and `ScreenHeader` for this accessibility pass: R-1073
 * (register) fixed `DrillInHeader`'s back chevron — `.clickable` sat directly on the 20dp glyph's
 * own `Modifier.size` rather than a real 44dp touch box — but the identical shape was left
 * standing on two siblings in the same file: `DrillInHeader`'s own "more" kebab (19dp), and
 * `ScreenHeader`'s drawer (21dp) and search (19dp) icons. `ScreenHeader` is the header the nav
 * host draws above almost every top-level destination (Now, Log, Threads, Stations, Recordings,
 * and every screen that has not grown its own header — `OrtNavHost.kt`'s own doc comment), so this
 * one fix (`HeaderTouchTargetIcon`, `Rows.kt`) carries to nearly the whole app. Both composables now
 * route through it. Checked at both font scales — the touch box is a fixed 44dp square independent
 * of text/glyph scaling, so a regression back to sizing the box from the glyph would fail this at
 * either scale, not only at 2.0 — the same discipline R-1073's own tests (`RowsTest.kt`) established.
 */
@RunWith(RobolectricTestRunner::class)
class HeaderTouchTargetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun assertDrillInHeaderKebabTargetMeetsFloor(fontScale: Float) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = fontScale)) {
                OrtTheme {
                    DrillInHeader(parentLabel = "Log", onBack = {}, onKebab = {})
                }
            }
        }
        val bounds = composeTestRule.onNodeWithTag("drill-in-header-kebab").getUnclippedBoundsInRoot()
        val width = bounds.right - bounds.left
        val height = bounds.bottom - bounds.top
        assert(width >= 44.dp && height >= 44.dp) {
            "expected the kebab touch target at least 44dp square at fontScale=$fontScale, got " +
                "${width}x$height"
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `P32 the kebab touch target meets the 44dp floor at font scale 1_0`() {
        assertDrillInHeaderKebabTargetMeetsFloor(fontScale = 1f)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `P32 the kebab touch target meets the 44dp floor at font scale 2_0`() {
        assertDrillInHeaderKebabTargetMeetsFloor(fontScale = 2f)
    }

    private fun assertScreenHeaderTargetsMeetFloor(fontScale: Float) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = fontScale)) {
                OrtTheme {
                    ScreenHeader(onDrawer = {}, onSearch = {})
                }
            }
        }
        val drawerBounds = composeTestRule.onNodeWithTag("screen-header-drawer-icon").getUnclippedBoundsInRoot()
        val drawerWidth = drawerBounds.right - drawerBounds.left
        val drawerHeight = drawerBounds.bottom - drawerBounds.top
        val searchBounds = composeTestRule.onNodeWithTag("screen-header-search-icon").getUnclippedBoundsInRoot()
        val searchWidth = searchBounds.right - searchBounds.left
        val searchHeight = searchBounds.bottom - searchBounds.top
        assert(drawerWidth >= 44.dp && drawerHeight >= 44.dp) {
            "expected the drawer touch target at least 44dp square at fontScale=$fontScale, got " +
                "${drawerWidth}x$drawerHeight"
        }
        assert(searchWidth >= 44.dp && searchHeight >= 44.dp) {
            "expected the search touch target at least 44dp square at fontScale=$fontScale, got " +
                "${searchWidth}x$searchHeight"
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `P32 the drawer and search touch targets meet the 44dp floor at font scale 1_0`() {
        assertScreenHeaderTargetsMeetFloor(fontScale = 1f)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-420dpi")
    fun `P32 the drawer and search touch targets meet the 44dp floor at font scale 2_0`() {
        assertScreenHeaderTargetsMeetFloor(fontScale = 2f)
    }
}
