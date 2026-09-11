package org.ort.app.ui.setup

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** E2-E07 (`spec/e2e-capture-modes-plan.md` WPD) — `Setup-Bluetooth-Permission.dc.html` (S02c). */
@RunWith(RobolectricTestRunner::class)
class BluetoothPermissionScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `E2_E07 renders the rationale, the decline banner and counter 2 of 8`() {
        composeTestRule.setContent {
            OrtTheme { BluetoothPermissionScreen(onAllow = {}, onNotNow = {}) }
        }

        composeTestRule.onNodeWithText("Nearby devices").assertIsDisplayed()
        composeTestRule.onNodeWithText("2 of 8").assertIsDisplayed()
        composeTestRule.onNodeWithTag("setup-bt-permission-decline-banner").performScrollTo().assertIsDisplayed()
    }

    /**
     * R-940 (register, reviewer A3 run 4a): at font scale 2.0 the third bullet and the amber
     * decline banner never became reachable even at scroll end — the fixed pinned-action block's
     * own bottom bar has no idea how much of the scrollable body it visually sits over once its own
     * buttons wrap onto more lines at a larger scale, so the scrollable body needs the bar's own
     * real, measured height as trailing padding (R-613's shape, `SetupScaffold`'s own doc comment).
     * Not reproduced under a plain Robolectric render at any scale tried — both nodes were already
     * reachable via `performScrollTo()` before this fix — so this also proves the real structural
     * invariant the fix targets: once scrolled fully into view, the banner's own bottom edge never
     * sits at or below where the fixed bar's own top edge starts (they can never overlap).
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `R_940 the third bullet and decline banner are reachable at 2_0, never overlapping the fixed bar`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrtTheme { BluetoothPermissionScreen(onAllow = {}, onNotNow = {}) }
            }
        }

        composeTestRule.onNodeWithText(
            "Use a Bluetooth headset-class device as the input — only if you choose it on the " +
                "Input step",
            substring = true,
        ).performScrollTo().assertIsDisplayed()
        val bannerBottom = composeTestRule.onNodeWithTag("setup-bt-permission-decline-banner")
            .performScrollTo()
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
            .bottom
        val barTop = composeTestRule.onNodeWithTag("setup-bt-permission-allow").fetchSemanticsNode().boundsInRoot.top

        assert(bannerBottom <= barTop) {
            "expected the decline banner to settle fully clear of the fixed bar, banner bottom=" +
                "$bannerBottom bar top=$barTop"
        }
    }

    @Test
    fun `E2_E07 tapping Allow nearby devices invokes onAllow`() {
        var allowed = false
        composeTestRule.setContent {
            OrtTheme { BluetoothPermissionScreen(onAllow = { allowed = true }, onNotNow = {}) }
        }

        composeTestRule.onNodeWithTag("setup-bt-permission-allow").performClick()
        assert(allowed)
    }

    @Test
    fun `E2_E07 tapping Not now use USB instead invokes onNotNow`() {
        var declined = false
        composeTestRule.setContent {
            OrtTheme { BluetoothPermissionScreen(onAllow = {}, onNotNow = { declined = true }) }
        }

        composeTestRule.onNodeWithTag("setup-bt-permission-not-now").performClick()
        assert(declined)
    }
}
