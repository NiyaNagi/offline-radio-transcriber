package org.ort.app.ui.settings

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.ModelCatalog
import org.ort.app.ui.theme.OrtTheme
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * Register R-865 (Validator V9, device): CF04's Space row used to total real, measured on-disk
 * bytes (`measureStorageAccounting(...).bundledBytes`) — honest for a genuine install, but a
 * test/dev fixture's placeholder-install shortcut left every real asset at `0` bytes on disk,
 * which rendered "Bundled assets use 0 MB" on a real device that had, by every other measure
 * (`Settings-Assets`'s own per-row markers, the root "N of 5 installed" line), five real models.
 * `rememberModelsExtras` now sums [ModelCatalog]'s own declared sizes instead — a plain,
 * synchronous, build-time-fixed figure with no file I/O and no dependency on what a given
 * install shortcut happened to leave on disk.
 */
@RunWith(RobolectricTestRunner::class)
class ModelsContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    @Requirement("FR-AST-3a")
    fun `R_865 the Space row totals the catalogue's own declared sizes, real even with nothing installed on disk`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        composeTestRule.setContent {
            OrtTheme { ModelsContent(context = context, modifier = Modifier) }
        }

        // The real `bundled-assets.json` sum, computed independently here rather than asserting a
        // literal this test and the production code could both drift from together — proves the
        // row reads through `ModelCatalog` for real, not a hand-typed figure this test happens to
        // share with production by coincidence.
        val expectedBytes = ModelCatalog.entries.sumOf { it.sizeBytes }
        val expectedMb = "%.0f".format(expectedBytes / 1_000_000.0)
        composeTestRule.onNodeWithText("Bundled assets use $expectedMb MB").assertExists()
    }
}
