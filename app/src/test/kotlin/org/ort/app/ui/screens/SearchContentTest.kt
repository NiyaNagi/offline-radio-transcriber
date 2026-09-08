package org.ort.app.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.RecentSearches
import org.ort.app.ui.data.SearchFacetCounts
import org.ort.app.ui.data.SearchFilterInput
import org.ort.app.ui.data.SearchResult
import org.ort.app.ui.theme.OrtTheme
import org.robolectric.RobolectricTestRunner

/**
 * [SearchContent] — the dispatch composable `OrtNavHost` (WP3) calls for the `SEARCH` destination.
 * It supplies everything [SearchScreen] needs beyond [SearchFilterInput]/[SearchResult] (recent
 * searches, the no-results widen suggestions, the filters-sheet open/closed state) without doing
 * its own read of the transmission log — those two host-held pieces come straight through.
 */
@RunWith(RobolectricTestRunner::class)
class SearchContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `a previously recorded recent search is loaded and shown`() {
        context.deleteSharedPreferences("recent_searches")
        RecentSearches.record(context, "WA7HJR", 6)

        composeTestRule.setContent {
            OrtTheme {
                SearchContent(input = SearchFilterInput(), result = null, onInputChange = {
                }, onSearch = {}, onOpen = {})
            }
        }

        composeTestRule.onNodeWithTag("search-recent-row-0").assertExists()
    }

    @Test
    fun `the Filters chip opens the sheet and the scrim closes it, entirely inside SearchContent`() {
        composeTestRule.setContent {
            OrtTheme {
                SearchContent(input = SearchFilterInput(), result = null, onInputChange = {
                }, onSearch = {}, onOpen = {})
            }
        }

        composeTestRule.onNodeWithTag("search-filters-chip").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("search-filters-sheet").assertExists()

        composeTestRule.onNodeWithTag("search-filters-scrim").performClick()
        composeTestRule.onNodeWithTag("search-filters-sheet").assertDoesNotExist()
    }

    @Test
    fun `an empty result computes widen suggestions rather than leaving the empty state bare`() {
        val result =
            SearchResult(details = emptyList(), textSearchUnavailable = false, facetCounts = SearchFacetCounts.EMPTY)
        composeTestRule.setContent {
            OrtTheme {
                SearchContent(
                    input = SearchFilterInput(callsign = "VE7ABC"),
                    result = result,
                    onInputChange = {},
                    onSearch = {},
                    onOpen = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("search-empty-state").assertExists()
        composeTestRule.onNodeWithText("is doing the narrowing.", substring = true).assertExists()
    }
}
