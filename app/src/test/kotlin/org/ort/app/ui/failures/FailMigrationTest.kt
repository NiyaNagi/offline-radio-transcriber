package org.ort.app.ui.failures

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner

/**
 * WP11b, register R-562 — split out of `FailureScreensTest.kt` (detekt's `LargeClass`, that file
 * already at its own line budget) rather than grown there; F20's other coverage stays in that file.
 */
@RunWith(RobolectricTestRunner::class)
class FailMigrationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `R_562 F20_migration closing paragraph carries the board's second sentence verbatim`() {
        composeTestRule.setContent {
            OrtTheme {
                FailMigrationScreen(
                    state = MigrationViewState(
                        versionLabel = "Updated to 1.1.0",
                        headline = "The records did not fully carry over",
                        steps = listOf(MigrationStep("Audio untouched", "38.2 GB", ok = true)),
                    ),
                    onRebuildNow = {},
                    onSaveDiagnosticBundle = {},
                )
            }
        }
        // Register R-562: the board's own second sentence (`Fail-Migration.dc.html`) was missing
        // entirely — the build used to stop at "ships.". `substring = true`: the full paragraph is
        // one AnnotatedString with an italic span mid-sentence, not a separate node.
        composeTestRule.onNodeWithText(
            "Station and frequency views will show pattern rebuilding until the marked overs are " +
                "re-derived.",
            substring = true,
        ).assertIsDisplayed()
    }
}
