package org.ort.app.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.RecentSearches
import org.ort.app.ui.data.SearchFacetCounts
import org.ort.app.ui.data.SearchFilterInput
import org.ort.app.ui.data.SearchResult
import org.ort.app.ui.theme.OrtTheme
import org.ort.data.OrtDatabase
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

    @Before
    fun openDatabase() {
        // `SearchWidenSuggestions.build` (called from this content composable's own `LaunchedEffect`)
        // queries the real `OrtDatabase` via `SimilarCallsigns.near` — starting from a clean,
        // freshly-migrated file rather than whatever an earlier test class in this Robolectric
        // process left behind, matching `SearchPollingTest`/`SearchWidenSuggestionsTest`'s own
        // `@Before` for the same reason (that file's own doc comment explains it).
        context.deleteDatabase(OrtDatabase.DATABASE_NAME)
    }

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
        composeTestRule.waitForIdle()
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
        // `SearchWidenSuggestions.build` (inside this screen's own `LaunchedEffect`) makes a real
        // Room query (`SimilarCallsigns.near`); Room's own executor runs on a genuine background
        // thread that `waitForIdle()`'s virtual/composition clock does not track, so the
        // assertion has to poll with real wall-clock waits rather than assume one idle pass is
        // enough — the standard pattern for Compose UI test content whose state depends on I/O
        // outside the composition clock.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("is doing the narrowing.", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("is doing the narrowing.", substring = true).assertExists()
    }
}
