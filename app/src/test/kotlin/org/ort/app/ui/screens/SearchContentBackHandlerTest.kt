package org.ort.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.SearchFilterInput
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner

/**
 * R-333 (Search half): WP3's host `BackHandler` (`OrtNavHost.kt`) handles the drawer and
 * drill-ins, but knows nothing about `SearchContent`'s own local `filtersSheetOpen` state — the
 * filters sheet is a plain overlay `Box`, not a `ModalBottomSheet`, so system back with the sheet
 * open fell through the host's handler entirely and exited Search instead of closing the sheet.
 *
 * A real `OnBackPressedDispatcher` dispatch needs a genuine `Activity` behind the composition —
 * `createComposeRule()` (`SearchContentTest`'s own rule, this class deliberately does not share
 * it) does not expose one to dispatch through. `createAndroidComposeRule<ComponentActivity>()`
 * (`LogAndThreadContentActivityTest`'s own pattern, this package) hosts the content inside a real,
 * `ActivityScenario`-backed `Activity` whose `onBackPressedDispatcher` this test can call
 * directly — the same mechanism a real system back gesture drives.
 */
@RunWith(RobolectricTestRunner::class)
class SearchContentBackHandlerTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun R_333_search_sheet_back() {
        composeTestRule.setContent {
            OrtTheme {
                SearchContent(
                    input = SearchFilterInput(),
                    result = null,
                    onInputChange = {},
                    onSearch = {},
                    onOpen = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("search-filters-chip").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("search-filters-sheet").assertExists()

        composeTestRule.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("search-filters-sheet").assertDoesNotExist()
        // System back closed the sheet, not the whole Search screen — the query field is still
        // the same, still-present composition, not a fresh one/a different destination entirely.
        composeTestRule.onNodeWithContentDescription("Search text").assertExists()
    }
}
