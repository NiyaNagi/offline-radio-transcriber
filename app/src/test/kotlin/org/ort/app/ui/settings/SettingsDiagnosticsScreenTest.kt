package org.ort.app.ui.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * R-137 (register, round 7 System validator pass 3): `Settings-Diagnostics.dc.html`'s own
 * scrubbing example — "resolved [callsign] at 0.94" — was missing from the "Not in the bundle"
 * prose; the per-file byte size and the header's own running total stay honestly absent (no
 * diagnostics-bundle producer exists in `:app`/`:pipeline` — [SettingsDiagnosticsScreen]'s own doc
 * comment), so this is the one part of R-137 that was real copy this package could fix directly.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsDiagnosticsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun state() = SettingsDiagnosticsViewState(
        aliveLabel = "alive",
        realTimeFactorLabel = "0.31",
        failedPassCount = 0,
        files = listOf(SettingsDiagnosticsFileViewState("lifecycle.log", "service start, stop")),
    )

    @Test
    @Requirement("R-137")
    fun `R_137 the never-included prose carries the board's own scrubbing example verbatim`() {
        composeTestRule.setContent { OrtTheme { SettingsDiagnosticsScreen(state = state(), onBack = {}) } }

        composeTestRule.onNodeWithText("resolved [callsign] at 0.94", substring = true).assertExists()
    }
}
