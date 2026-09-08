package org.ort.app.ui.screens

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.data.RecentSearchEntry
import org.ort.app.ui.data.SearchFacetCounts
import org.ort.app.ui.data.SearchFacetRow
import org.ort.app.ui.data.SearchFilterInput
import org.ort.app.ui.data.SearchResult
import org.ort.app.ui.data.SearchTimeFilter
import org.ort.app.ui.data.SearchWidenOption
import org.ort.app.ui.data.SearchWidenViewState
import org.ort.app.ui.data.TransmissionDetail
import org.ort.app.ui.theme.OrtTheme
import org.ort.core.Attribution
import org.ort.core.AttributionState
import org.robolectric.RobolectricTestRunner

/**
 * `Search`/`Search-Results`/`Search-Empty`/`Search-Unavailable.dc.html` (R-060, R-063, R-065).
 * [org.ort.app.ui.data.SearchFilterParserTest]/[org.ort.app.ui.data.SearchPollingTest] cover the
 * data half; this proves what renders for each of the screen's five states.
 */
@RunWith(RobolectricTestRunner::class)
class SearchScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun detail(
        id: String,
        text: String,
        attribution: Attribution = Attribution.unknown(),
        startedAt: Long = 0L,
    ) = TransmissionDetail(
        id = id,
        startedAtUtcMillis = startedAt,
        frequencyHz = 145_230_000L,
        durationMs = 1_000L,
        signalStrength = null,
        attribution = attribution,
        currentTranscriptText = text,
        supersededTranscriptTexts = emptyList(),
        hasAudio = false,
    )

    @Suppress("LongParameterList") // one param per SearchScreen argument — see SearchScreen.kt's own suppression.
    private fun screen(
        input: SearchFilterInput = SearchFilterInput(),
        result: SearchResult? = null,
        recent: List<RecentSearchEntry> = emptyList(),
        widenSuggestions: SearchWidenViewState? = null,
        filtersSheetOpen: Boolean = false,
        filterFacetCounts: SearchFacetCounts = SearchFacetCounts.EMPTY,
        heardFrequenciesHz: List<Long> = emptyList(),
        onInputChange: (SearchFilterInput) -> Unit = {},
        onSearch: () -> Unit = {},
        onOpen: (String) -> Unit = {},
        onOpenFilters: () -> Unit = {},
        onDismissFilters: () -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            OrtTheme {
                SearchScreen(
                    input = input,
                    result = result,
                    recent = recent,
                    widenSuggestions = widenSuggestions,
                    filtersSheetOpen = filtersSheetOpen,
                    filterFacetCounts = filterFacetCounts,
                    heardFrequenciesHz = heardFrequenciesHz,
                    onOpenFilters = onOpenFilters,
                    onDismissFilters = onDismissFilters,
                    onInputChange = onInputChange,
                    onSearch = onSearch,
                    onOpen = onOpen,
                    onBack = onBack,
                )
            }
        }
    }

    // --- R-060: no duplicated "Search" text, run action is the keyboard action + a conditional button ---

    @Test
    fun `R_060 before any search runs, no Search button shows for a blank field`() {
        screen(result = null)

        composeTestRule.onNodeWithTag("search-run-button").assertDoesNotExist()
    }

    @Test
    fun `R_060 once the field is non-blank a single PrimaryButton-styled Search action appears`() {
        var searched = false
        screen(input = SearchFilterInput(text = "mayday"), onSearch = { searched = true })

        composeTestRule.onNodeWithContentDescription("Run search").performScrollTo().performClick()
        assert(searched) { "expected the Search action to be invoked" }
    }

    @Test
    fun `R_060 typing into the query field issues an updated input`() {
        var current = SearchFilterInput()
        screen(input = current, onInputChange = { current = it })

        composeTestRule.onNodeWithContentDescription("Search text").performTextInput("mayday")

        assert(current.text.contains("mayday")) {
            "expected the query field edit to reach onInputChange, got ${current.text}"
        }
    }

    @Test
    fun R_060_the_keyboard_search_action_runs_the_search() {
        var searched = false
        screen(input = SearchFilterInput(text = "mayday"), onSearch = { searched = true })

        composeTestRule.onNodeWithContentDescription("Search text").performImeAction()

        assert(searched) { "expected the keyboard's search IME action to invoke onSearch" }
    }

    @Test
    fun `R_063 the unavailable field shows a degraded not-applied cue, never a halt one`() {
        val result =
            SearchResult(details = emptyList(), textSearchUnavailable = true, facetCounts = SearchFacetCounts.EMPTY)
        screen(input = SearchFilterInput(text = "mayday"), result = result)

        // A textual, description-based check (guide §9: never colour alone) — `errorTone =
        // FieldTone.Degraded` is what keeps this amber rather than `halt/text` red at the
        // component level; asserting the specific "Not applied" cue (not, say, an "Error" or
        // "Invalid" halt-style message) is the part observable from outside `TextField` itself.
        composeTestRule.onNodeWithText("Not applied").assertExists()
    }

    @Test
    fun `no not-applied cue shows once the search actually ran`() {
        val result = SearchResult(
            details = listOf(detail("TX1", "mayday mayday", Attribution.confirmed("W7NPC", 0.9))),
            textSearchUnavailable = false,
            facetCounts = SearchFacetCounts.EMPTY,
        )
        screen(input = SearchFilterInput(text = "mayday"), result = result)

        composeTestRule.onNodeWithText("Not applied").assertDoesNotExist()
    }

    // --- R-200: focused on entry, back chevron ---

    @Test
    fun `R_200 the query field is focused on entry to the untouched initial screen`() {
        screen(input = SearchFilterInput(), result = null)

        composeTestRule.onNodeWithContentDescription("Search text").assertIsFocused()
    }

    @Test
    fun `R_200 the query field is not stolen back into focus once a result exists`() {
        val result = SearchResult(
            details = listOf(detail("TX1", "mayday mayday", Attribution.confirmed("W7NPC", 0.9))),
            textSearchUnavailable = false,
            facetCounts = SearchFacetCounts.EMPTY,
        )
        screen(input = SearchFilterInput(text = "mayday"), result = result)

        composeTestRule.onNodeWithContentDescription("Search text").assertIsNotFocused()
    }

    @Test
    fun `R_200 the back chevron invokes onBack`() {
        var backed = false
        screen(onBack = { backed = true })

        composeTestRule.onNodeWithTag("search-back-chevron").performClick()

        assert(backed) { "expected the back chevron to invoke onBack" }
    }

    // --- R-202/R-203: the filter sheet's live counts and heard-frequency chips flow through ---

    @Test
    fun `R_202 the filter sheet's counts come from filterFacetCounts, live from the caller`() {
        val counts = SearchFacetCounts(
            listOf(
                SearchFacetRow(AttributionState.CONFIRMED, false, false),
                SearchFacetRow(AttributionState.CONFIRMED, false, false),
                SearchFacetRow(AttributionState.UNKNOWN, false, false),
            ),
        )
        screen(filtersSheetOpen = true, filterFacetCounts = counts)

        // Real counts on a plain, untouched (default) filter input — never the 0 the register
        // caught when this sourced `result?.facetCounts` (`EMPTY` before any search had run).
        composeTestRule.onNodeWithText("Show 3 overs").assertExists()
    }

    @Test
    fun `R_203 heardFrequenciesHz reaches the filter sheet's chip row`() {
        screen(filtersSheetOpen = true, heardFrequenciesHz = listOf(145_230_000L))

        composeTestRule.onNodeWithText("145.230").assertExists()
        composeTestRule.onNodeWithText("2M").assertExists()
    }

    // --- Initial state (R-063) ---

    @Test
    fun `R_063 the initial state shows Try hints and no results list`() {
        screen(result = null)

        composeTestRule.onNodeWithTag("search-try-hint-0").assertExists()
        composeTestRule.onNodeWithTag("search-empty-state").assertDoesNotExist()
    }

    @Test
    fun `R_063 recent searches render with their counts and a tap re-runs them`() {
        var current = SearchFilterInput()
        var searched = false
        screen(
            result = null,
            recent = listOf(RecentSearchEntry("WA7HJR", 6)),
            onInputChange = { current = it },
            onSearch = { searched = true },
        )

        composeTestRule.onNodeWithTag("search-recent-row-0").performScrollTo().performClick()

        assert(current.callsign == "WA7HJR") {
            "expected the recent row to fill the callsign field, got ${current.callsign}"
        }
        assert(searched) { "expected tapping a recent row to re-run the search" }
    }

    @Test
    fun `R_060 the Tonight and All nights quick chips set the time filter and run immediately`() {
        var current = SearchFilterInput()
        var searchCount = 0
        screen(result = null, onInputChange = { current = it }, onSearch = { searchCount++ })

        composeTestRule.onNodeWithTag("search-tonight-chip").performScrollTo().performClick()

        assert(current.timeFilter == SearchTimeFilter.TONIGHT) { "expected TONIGHT but was ${current.timeFilter}" }
        assert(searchCount == 1) { "expected the quick chip to run a search" }
    }

    // --- Results state (R-063/R-065) ---

    @Test
    fun `R_065 results render through the shared LogRow, grouped by night, with a count line`() {
        val result = SearchResult(
            details = listOf(detail("TX1", "mayday mayday", Attribution.confirmed("W7NPC", 0.9))),
            textSearchUnavailable = false,
            facetCounts = SearchFacetCounts(listOf(SearchFacetRow(AttributionState.CONFIRMED, false, false))),
        )
        screen(input = SearchFilterInput(text = "mayday"), result = result)

        composeTestRule.onNodeWithText("mayday mayday").assertExists()
        composeTestRule.onNodeWithTag("search-count-line").assertExists()
        composeTestRule.onNodeWithText("Newest first").assertExists()
    }

    @Test
    fun `R_065 tapping a result row opens it`() {
        var opened: String? = null
        val result = SearchResult(
            details = listOf(detail("TX1", "mayday mayday", Attribution.confirmed("W7NPC", 0.9))),
            textSearchUnavailable = false,
            facetCounts = SearchFacetCounts.EMPTY,
        )
        screen(result = result, onOpen = { opened = it })

        composeTestRule.onNodeWithTag("search-result-row-TX1").performScrollTo().performClick()

        assert(opened == "TX1") { "expected TX1 to be opened but was $opened" }
    }

    @Test
    fun `applied filters render as dismissable chips and dismissing one clears it and re-runs`() {
        var current = SearchFilterInput(callsign = "W7NPC")
        var searchCount = 0
        val result = SearchResult(
            details = listOf(detail("TX1", "text", Attribution.confirmed("W7NPC", 0.9))),
            textSearchUnavailable = false,
            facetCounts = SearchFacetCounts.EMPTY,
        )
        screen(input = current, result = result, onInputChange = { current = it }, onSearch = { searchCount++ })

        composeTestRule.onNodeWithContentDescription("Remove W7NPC filter").performScrollTo().performClick()

        assert(current.callsign == "") { "expected dismissing the chip to clear the callsign filter" }
        assert(searchCount == 1) { "expected dismissing a chip to re-run the search" }
    }

    // --- Empty state (R-063) ---

    @Test
    fun `R_063 a search with no matches shows the empty state with widen options, never a blank screen`() {
        val result =
            SearchResult(details = emptyList(), textSearchUnavailable = false, facetCounts = SearchFacetCounts.EMPTY)
        val widen = SearchWidenViewState(
            narrowingSummary = "The callsign filter is doing the narrowing.",
            options = listOf(SearchWidenOption("all_nights", "All nights", "would show 2 overs, from 1 Sep")),
            similarCallsigns = listOf("VE7ABD"),
        )
        screen(input = SearchFilterInput(callsign = "VE7ABC"), result = result, widenSuggestions = widen)

        composeTestRule.onNodeWithTag("search-empty-state").assertExists()
        composeTestRule.onNodeWithText("The callsign filter is doing the narrowing.").assertExists()
        composeTestRule.onNodeWithTag("search-widen-option-all_nights").assertExists()
        composeTestRule.onNodeWithTag("search-similar-callsigns").assertExists()
    }

    @Test
    fun `tapping a widen option applies it and re-runs the search`() {
        var current = SearchFilterInput(timeFilter = SearchTimeFilter.TONIGHT)
        var searchCount = 0
        val result =
            SearchResult(details = emptyList(), textSearchUnavailable = false, facetCounts = SearchFacetCounts.EMPTY)
        val widen = SearchWidenViewState(
            narrowingSummary = "narrowed",
            options = listOf(SearchWidenOption("all_nights", "All nights", "would show 2 overs")),
            similarCallsigns = emptyList(),
        )
        screen(input = current, result = result, widenSuggestions = widen, onInputChange = {
            current = it
        }, onSearch = { searchCount++ })

        composeTestRule.onNodeWithContentDescription("Show — All nights").performScrollTo().performClick()

        assert(current.timeFilter == SearchTimeFilter.ALL) {
            "expected the widen option to set ALL but was ${current.timeFilter}"
        }
        assert(searchCount == 1)
    }

    // --- Unavailable state (R-063) ---

    @Test
    fun `R_063 when the full-text index is unavailable the amber banner names what did and did not apply`() {
        val result = SearchResult(
            details = listOf(detail("TX1", "irrelevant transcript")),
            textSearchUnavailable = true,
            facetCounts = SearchFacetCounts.EMPTY,
        )
        screen(input = SearchFilterInput(text = "mayday", callsign = "W7NPC"), result = result)

        composeTestRule.onNodeWithTag("search-unavailable-banner").assertExists()
        composeTestRule.onNodeWithText("Text search is unavailable right now").assertExists()
        composeTestRule.onNodeWithText("irrelevant transcript").assertExists()
    }

    @Test
    fun `Retry on the unavailable banner re-runs the search`() {
        var searched = false
        val result =
            SearchResult(details = emptyList(), textSearchUnavailable = true, facetCounts = SearchFacetCounts.EMPTY)
        screen(input = SearchFilterInput(text = "mayday"), result = result, onSearch = { searched = true })

        composeTestRule.onNodeWithText("Retry").performScrollTo().performClick()

        assert(searched) { "expected Retry to invoke onSearch" }
    }

    // --- Filters sheet wiring ---

    @Test
    fun `tapping the Filters chip opens the filters sheet`() {
        var opened = false
        screen(onOpenFilters = { opened = true })

        composeTestRule.onNodeWithTag("search-filters-chip").performScrollTo().performClick()

        assert(opened) { "expected the Filters chip to call onOpenFilters" }
    }

    @Test
    fun `the filters sheet renders over a scrim when filtersSheetOpen is true`() {
        screen(filtersSheetOpen = true)

        composeTestRule.onNodeWithTag("search-filters-scrim").assertExists()
        composeTestRule.onNodeWithTag("search-filters-sheet").assertExists()
    }

    @Test
    fun `tapping the scrim dismisses the filters sheet`() {
        var dismissed = false
        screen(filtersSheetOpen = true, onDismissFilters = { dismissed = true })

        composeTestRule.onNodeWithTag("search-filters-scrim").performClick()

        assert(dismissed) { "expected tapping the scrim to call onDismissFilters" }
    }
}
