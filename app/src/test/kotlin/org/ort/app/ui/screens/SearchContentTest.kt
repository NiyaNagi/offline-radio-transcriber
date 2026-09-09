package org.ort.app.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.debug.Scenarios
import org.ort.app.ui.data.RecentSearches
import org.ort.app.ui.data.SearchFacetCounts
import org.ort.app.ui.data.SearchFacetFilter
import org.ort.app.ui.data.SearchFilterInput
import org.ort.app.ui.data.SearchFilterParser
import org.ort.app.ui.data.SearchPolling
import org.ort.app.ui.data.SearchResult
import org.ort.app.ui.theme.OrtTheme
import org.ort.core.AttributionState
import org.ort.core.SystemClock
import org.ort.core.TransmissionState
import org.ort.data.OrtDatabase
import org.ort.data.entity.SessionEntity
import org.ort.data.entity.TransmissionEntity
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
    fun `R_261 class the query field is unreachable while the filters sheet is open, reachable again after dismiss`() {
        composeTestRule.setContent {
            OrtTheme {
                SearchContent(input = SearchFilterInput(), result = null, onInputChange = {
                }, onSearch = {}, onOpen = {})
            }
        }

        // Before the sheet opens, the query field's own content description ("Search text",
        // SearchScreen.kt's SearchHeaderRow) is a normal, reachable semantics node.
        composeTestRule.onNodeWithContentDescription("Search text").assertExists()

        composeTestRule.onNodeWithTag("search-filters-chip").performScrollTo().performClick()
        // `Modifier.clearedWhileOverlaid` (WP2, Feedback.kt) applied to SearchScreen's own content
        // Column clears its entire merged semantics subtree while the sheet is open — the query
        // field is still there (visually, beneath the scrim) but no longer in the semantics tree
        // TalkBack/Compose focus traversal reaches, exactly the R-261 class of bug the register
        // found on the detail screen's "Back to Log" button.
        composeTestRule.onNodeWithContentDescription("Search text").assertDoesNotExist()

        composeTestRule.onNodeWithTag("search-filters-scrim").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Search text").assertExists()
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

    private fun session() = SessionEntity(
        id = "S1", startedAt = 0L, endedAt = null, profileId = null, deviceTier = null,
        appVersion = "test", terminationReason = null, sourceId = null, schemaVersion = OrtDatabase.SCHEMA_VERSION,
    )

    private fun transmission(id: String, samplePosition: Long) = TransmissionEntity(
        id = id, sessionId = "S1", threadId = null, startedAtUtc = samplePosition, endedAtUtc = samplePosition + 1000L,
        durationMs = 1000L, audioFormat = "flac/16k/mono", preRollMs = 200, postRollMs = 200,
        frequencyHz = 145_230_000L, frequencyProvenance = "measured", mode = null, signalStrength = null,
        channelName = null, voiceprintId = null, attributionState = AttributionState.CONFIRMED, stationId = null,
        attributionConfidence = null, attributionSourceTransmissionId = null,
        processingState = TransmissionState.COMPLETE, rejectionReason = null, samplePosition = samplePosition,
        monotonicStartNanos = 0L, utcOffsetMinutes = 0, calibrationId = null, executionProvider = null,
    )

    @Test
    fun `R_202 opening the filter sheet with a real, non-empty corpus shows real counts, never 0`(): Unit = runTest {
        val db = OrtDatabase.create(context)
        db.sessionDao().insert(session())
        db.transmissionDao().insert(transmission("TX1", 1L))
        db.transmissionDao().insert(transmission("TX2", 2L))
        db.transmissionDao().insert(transmission("TX3", 3L))

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

        // Same real-background-thread-I/O reasoning as the widen-suggestions test above:
        // `SearchPolling.facetCounts` is a genuine Room query, not tracked by `waitForIdle()`.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Show 3 overs").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Show 3 overs").assertExists()
    }

    // --- Screenshot-tour seam: initialQuery/submitOnStart/initialFiltersOpen ---

    @Test
    fun R_TOUR_search_initial_query(): Unit = runTest {
        // The same `search-corpus` debug fixture the register's own validator and `R_371_*`
        // (`SearchPollingTest.kt`) use — 14 overs across 3 nights, every transcript reading
        // "...doing a park activation...", so "park" matches all 14.
        Scenarios.load(context, "search-corpus")

        composeTestRule.setContent {
            OrtTheme {
                // A small stand-in for `OrtNavHost`'s own `searchHostState` (`OrtNavHost.kt`) —
                // `onInputChange`/`onSearch` wired the identical way, so this proves the tour seam
                // reaches a real `SearchResult` through the *caller's* own real search path, not a
                // seeded/faked one specific to this test.
                var input by remember { mutableStateOf(SearchFilterInput()) }
                var result by remember { mutableStateOf<SearchResult?>(null) }
                val scope = rememberCoroutineScope()
                SearchContent(
                    input = input,
                    result = result,
                    onInputChange = { input = it },
                    onSearch = {
                        scope.launch {
                            val params = SearchFilterParser.parse(input, SystemClock.wallMillis())
                            result = SearchPolling.search(context, params, SearchFacetFilter.from(input))
                        }
                    },
                    onOpen = {},
                    initialQuery = "park",
                    submitOnStart = true,
                )
            }
        }

        // Real background-thread Room I/O (`SearchPolling.search`), not tracked by `waitForIdle()`
        // — the standard polling pattern this file already uses above.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("14 overs", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("14 overs", substring = true).assertExists()
        composeTestRule.onNodeWithTag("search-count-line").assertExists()
        composeTestRule.onNodeWithTag("search-empty-state").assertDoesNotExist()
    }

    @Test
    fun R_TOUR_search_filters_open() {
        composeTestRule.setContent {
            OrtTheme {
                SearchContent(
                    input = SearchFilterInput(),
                    result = null,
                    onInputChange = {},
                    onSearch = {},
                    onOpen = {},
                    initialFiltersOpen = true,
                )
            }
        }

        composeTestRule.onNodeWithTag("search-filters-sheet").assertExists()
        composeTestRule.onNodeWithTag("search-filters-scrim").assertExists()
    }
}
