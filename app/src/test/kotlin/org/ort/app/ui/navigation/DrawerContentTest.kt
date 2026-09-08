package org.ort.app.ui.navigation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner

/**
 * The drawer exposes every destination `Menu.dc.html` lists, in its order, and carries the D26
 * storage footer (canvas.json's `integrated` annotation) — build-plan P13.
 */
@RunWith(RobolectricTestRunner::class)
class DrawerContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `the drawer lists every Menu dc html destination`() {
        composeTestRule.setContent {
            OrtTheme {
                ReaderDrawerContent(
                    current = ReaderDestination.NOW,
                    storage = StorageFooterViewState(
                        usedBytes = 38_200_000_000L,
                        totalBytes = 60_000_000_000L,
                        isPlaceholder = true,
                    ),
                    onSelect = {},
                )
            }
        }

        listOf(
            "Now",
            "Log",
            "Threads",
            "Stations",
            "Frequencies",
            "Earlier nights",
            "Capture",
            "Improve records",
            "Settings",
        ).forEach { label ->
            composeTestRule.onNodeWithText(label).assertExists()
        }
    }

    @Test
    fun `D26 the drawer footer carries the storage budget`() {
        composeTestRule.setContent {
            OrtTheme {
                ReaderDrawerContent(
                    current = ReaderDestination.NOW,
                    storage = StorageFooterViewState(
                        usedBytes = 38_200_000_000L,
                        totalBytes = 60_000_000_000L,
                        isPlaceholder = true,
                    ),
                    onSelect = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription(
            "Storage: 38.2 GB of 60.0 GB used (device total — per-category budgets not yet set)",
        ).assertExists()
    }
}
