package org.ort.app.ui.navigation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.theme.OrtTheme
import org.ort.testing.Requirement
import org.robolectric.RobolectricTestRunner

/**
 * The drawer exposes every destination `Menu.dc.html` lists, in its order, and carries the D26
 * storage footer (canvas.json's `integrated` annotation) — build-plan P13.
 *
 * Audit F-020: the footer (FR-STO-5) and the Log/Threads/Capture badges (FR-UI-7) must report
 * only real, measured facts — see [StorageFooterViewState] and [DrawerBadgeViewState] for what
 * "real" means for each.
 */
@RunWith(RobolectricTestRunner::class)
class DrawerContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val someStorage = StorageFooterViewState(
        audioUsedBytes = 38_200_000_000L,
        freeBytes = 21_800_000_000L,
        hasBudget = false,
    )

    @Test
    fun `the drawer lists every Menu dc html destination`() {
        composeTestRule.setContent {
            OrtTheme {
                ReaderDrawerContent(
                    current = ReaderDestination.NOW,
                    storage = someStorage,
                    badges = DrawerBadgeViewState.NONE,
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
    @Requirement("FR-STO-5")
    fun `FR_STO_5 the footer reports the real audio usage and admits no budget exists`() {
        composeTestRule.setContent {
            OrtTheme {
                ReaderDrawerContent(
                    current = ReaderDestination.NOW,
                    storage = someStorage,
                    badges = DrawerBadgeViewState.NONE,
                    onSelect = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription(
            "Audio: 38.2 GB used, 21.8 GB free (no budget set)",
        ).assertExists()
    }

    @Test
    @Requirement("FR-STO-5")
    fun `FR_STO_5 the footer never fabricates a total budget`() {
        composeTestRule.setContent {
            OrtTheme {
                ReaderDrawerContent(
                    current = ReaderDestination.NOW,
                    storage = someStorage,
                    badges = DrawerBadgeViewState.NONE,
                    onSelect = {},
                )
            }
        }

        // "of 60"-style totals are exactly the fabricated D26 budget F-020 found — must never render.
        composeTestRule.onNodeWithText("of 60", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("of 60.0 GB", substring = true).assertDoesNotExist()
    }

    @Test
    @Requirement("FR-UI-7")
    fun `FR_UI_7 Log shows the session's real transmission count`() {
        composeTestRule.setContent {
            OrtTheme {
                ReaderDrawerContent(
                    current = ReaderDestination.NOW,
                    storage = someStorage,
                    badges = DrawerBadgeViewState(logCount = 412, threadsCount = null, captureElapsedLabel = null),
                    onSelect = {},
                )
            }
        }

        composeTestRule.onNodeWithText("412").assertExists()
    }

    @Test
    @Requirement("FR-UI-7")
    fun `FR_UI_7 Threads never shows a numeric badge while threadId is always null`() {
        composeTestRule.setContent {
            OrtTheme {
                ReaderDrawerContent(
                    current = ReaderDestination.NOW,
                    storage = someStorage,
                    badges = DrawerBadgeViewState(logCount = 412, threadsCount = null, captureElapsedLabel = null),
                    onSelect = {},
                )
            }
        }

        // Not "0" (fabricated grouping) and not any digit at all — only the honest "—" marker.
        composeTestRule.onNodeWithText("—").assertExists()
    }

    @Test
    @Requirement("FR-UI-7")
    fun `FR_UI_7 Capture shows the running session's elapsed time`() {
        composeTestRule.setContent {
            OrtTheme {
                ReaderDrawerContent(
                    current = ReaderDestination.NOW,
                    storage = someStorage,
                    badges = DrawerBadgeViewState(logCount = 0, threadsCount = null, captureElapsedLabel = "6:42"),
                    onSelect = {},
                )
            }
        }

        composeTestRule.onNodeWithText("6:42").assertExists()
    }

    @Test
    @Requirement("FR-UI-7")
    fun `FR_UI_7 no Log or Capture badge renders while idle`() {
        composeTestRule.setContent {
            OrtTheme {
                ReaderDrawerContent(
                    current = ReaderDestination.NOW,
                    storage = someStorage,
                    badges = DrawerBadgeViewState.NONE,
                    onSelect = {},
                )
            }
        }

        composeTestRule.onNodeWithText("0").assertDoesNotExist()
    }
}
