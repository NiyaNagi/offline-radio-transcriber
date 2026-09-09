package org.ort.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ort.app.ui.components.LogRow
import org.ort.app.ui.components.LogRowViewState
import org.ort.app.ui.data.ReaderTransmissionViewStateMapper
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
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

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
    fun `R_504 the query text renders struck through in a neutral field, never an amber not-applied caption`() {
        val result =
            SearchResult(details = emptyList(), textSearchUnavailable = true, facetCounts = SearchFacetCounts.EMPTY)
        screen(input = SearchFilterInput(text = "mayday"), result = result)

        // R-504: the board strikes the query text through, in a neutral field — not the amber
        // border + separate "Not applied" caption the shared `TextField`'s `FieldTone.Degraded`
        // drew before this fix. The live, editable field is gone in this state entirely (a
        // read-only stand-in takes over) and so is the old caption text.
        composeTestRule.onNodeWithTag("search-struck-through-query").assertExists()
        composeTestRule.onNodeWithContentDescription("mayday", substring = true).assertExists()
        composeTestRule.onNodeWithText("Not applied").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Search text").assertDoesNotExist()
    }

    @Test
    fun `R_504 the live editable field returns, with no strikethrough, once a search actually succeeds`() {
        val result = SearchResult(
            details = listOf(detail("TX1", "mayday mayday", Attribution.confirmed("W7NPC", 0.9))),
            textSearchUnavailable = false,
            facetCounts = SearchFacetCounts.EMPTY,
        )
        screen(input = SearchFilterInput(text = "mayday"), result = result)

        composeTestRule.onNodeWithTag("search-struck-through-query").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Search text").assertExists()
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
        // R-500: the sheet's own default filter is Confirmed + Inferred (never Unknown) — every
        // row here must be a state the default actually includes, or this test would be proving
        // R-500's own narrowing instead of R-202's live-count wiring.
        val counts = SearchFacetCounts(
            listOf(
                SearchFacetRow(AttributionState.CONFIRMED, false, false),
                SearchFacetRow(AttributionState.CONFIRMED, false, false),
                SearchFacetRow(AttributionState.INFERRED, false, false),
            ),
        )
        screen(filtersSheetOpen = true, filterFacetCounts = counts)

        // Real counts on a plain, untouched (default) filter input — never the 0 the register
        // caught when this sourced `result?.facetCounts` (`EMPTY` before any search had run).
        // `PrimaryButton`'s own `clearAndSetSemantics` (`Controls.kt`) exposes its label only via
        // `contentDescription`, same as `FilterChip`/`LogRow` above.
        composeTestRule.onNodeWithContentDescription("Show 3 overs").assertExists()
    }

    @Test
    fun `R_203 heardFrequenciesHz reaches the filter sheet's chip row`() {
        screen(filtersSheetOpen = true, heardFrequenciesHz = listOf(145_230_000L))

        // `FilterChip`'s own `clearAndSetSemantics` (`Controls.kt`, alongside `LogRow`'s R-373 fix)
        // now exposes its label only via `contentDescription`, never `Text`/`EditableText`.
        composeTestRule.onNodeWithContentDescription("145.230").assertExists()
        composeTestRule.onNodeWithContentDescription("2 m").assertExists()
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

        // R-380/R-381 (WP2, `ui/components/CHANGELOG.md`): `LogRow`'s own outer node
        // `clearAndSetSemantics`-es its composed description — its transcript `Text` is only
        // reachable on the unmerged tree now.
        composeTestRule.onNodeWithText("mayday mayday", useUnmergedTree = true).assertExists()
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

        // R-380/R-381/R-543 (WP2, `ui/components/CHANGELOG.md`): the dismiss icon is a descendant
        // of `FilterChip`'s own outer `clearAndSetSemantics` node, so it is only reachable on the
        // unmerged tree in Robolectric's own model now — on a real device the icon is also exposed
        // as a `CustomAccessibilityAction` on the chip's own node (`Controls.kt`'s own doc
        // comment), so this stays reachable for TalkBack too, not only for a raw tap.
        composeTestRule
            .onNodeWithContentDescription("Remove W7NPC filter", useUnmergedTree = true)
            .performScrollTo()
            .performClick()

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
        // R-380/R-381 (WP2, `ui/components/CHANGELOG.md`): see the note on the `R_065` test above.
        composeTestRule.onNodeWithText("irrelevant transcript", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `R_504 the unavailable count line reads N overs unfiltered by text, never the ordinary count line`() {
        val result = SearchResult(
            details = listOf(
                detail("TX1", "one", Attribution.confirmed("W7NPC", 0.9)),
                detail("TX2", "two", Attribution.confirmed("KJ7ABC", 0.9)),
            ),
            textSearchUnavailable = true,
            facetCounts = SearchFacetCounts.EMPTY,
        )
        screen(input = SearchFilterInput(text = "mayday"), result = result)

        // No frequency/band filter is active here — "on <freq>" never appears when there is
        // nothing real to name (never a frequency invented to match the board's own example).
        composeTestRule.onNodeWithTag("search-unavailable-count-line").assertExists()
        composeTestRule.onNodeWithText("2 overs", substring = true).assertExists()
        composeTestRule.onNodeWithText("unfiltered by text", substring = true).assertExists()
        composeTestRule.onNodeWithText("2 overs · 1 night", substring = true).assertDoesNotExist()
    }

    @Test
    fun `R_504 the unavailable count line names the active frequency when one is set`() {
        val result = SearchResult(
            details = listOf(detail("TX1", "one", Attribution.confirmed("W7NPC", 0.9))),
            textSearchUnavailable = true,
            facetCounts = SearchFacetCounts.EMPTY,
        )
        screen(input = SearchFilterInput(text = "mayday", frequencyMhz = "146.960"), result = result)

        composeTestRule.onNodeWithText("on 146.960", substring = true).assertExists()
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

    // --- R-370: day-group headers use the device's own locale, never Locale.ROOT's "M09" ---

    @Test
    fun `R_370 the day-group header renders the locale's real month name, never a raw MMM field code`() {
        val startedAt = Instant.parse("2026-09-07T22:11:38Z").toEpochMilli()
        val result = SearchResult(
            details = listOf(detail("TX1", "mayday mayday", Attribution.confirmed("W7NPC", 0.9), startedAt)),
            textSearchUnavailable = false,
            facetCounts = SearchFacetCounts.EMPTY,
        )
        screen(result = result)

        // Computed the same way `SearchScreen.kt`'s own (now-fixed) `DAY_FORMAT` is — locale-aware,
        // never `Locale.ROOT` — so this passes under whatever locale the host JVM actually runs
        // with, and would have failed against the pre-fix `Locale.ROOT` formatter (whose "MMM"
        // degrades to the literal field code "M09" the register's screenshot showed, not a real
        // month name in any locale that has one).
        val expectedFormat = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
        val expected = Instant.ofEpochMilli(startedAt).atZone(ZoneOffset.UTC).toLocalDate().format(expectedFormat)
        composeTestRule.onNodeWithText(expected.uppercase()).assertExists()
        composeTestRule.onNodeWithText("M09", substring = true).assertDoesNotExist()
    }

    // --- R-373: a result row gets exactly the width LogScreen's own LogRow usage would give it ---

    @Test
    fun `R_373 a result row measures exactly as tall as the same LogRow rendered bare, at font scale 2_0`() {
        // Diagnosis (register: "find the difference and make Search use the row exactly as
        // LogScreen does"): there is no difference to fix in this package. `ResultsState`
        // (`SearchScreen.kt`) composes the shared `LogRow` (`ui/components/Rows.kt`) as a direct
        // child of a plain `Column` with no extra width-constraining wrapper — no `IntrinsicSize`,
        // no missing `fillMaxWidth` (`LogRow`'s own root always applies one, regardless of caller),
        // no horizontal scroll — exactly how `LogScreen.kt`'s own `LazyColumn` items compose it.
        // This test proves that structurally: the same `LogRowViewState`, at the same fixed width
        // and font scale, measures the *same height* whether it goes through the full `SearchScreen`
        // or is rendered bare — real, on-device evidence (`overnight/L01-log-pass3@2x.png`,
        // `search-corpus/Q03-results-2x-clean-pass4.png`) shows the *same* mid-word wrapping in
        // both Log's and Search's own screenshots at font scale 2.0, confirming this is a shared
        // `LogRow`/`Rows.kt` defect (its time/frequency column floors, `rememberTimeColumnWidth`/
        // `rememberFreqColumnWidth`, leaving too little width for the weighted transcript column at
        // a large scale) — outside this package's file ownership. Routed to WP2 for a min-width
        // contract on the weighted column; see this round's CHANGELOG addendum.
        val detail = detail(
            id = "TX1",
            text = "this is KE7QRS, doing a park activation at K-4403, any hunters listening",
            attribution = Attribution.confirmed("KE7QRS", 0.9),
        )
        val entry = ReaderTransmissionViewStateMapper.listEntry(detail)
        val referenceState = LogRowViewState(
            id = entry.id,
            timeLabel = entry.timeLabel,
            frequencyLabel = entry.frequencyLabel,
            transcript = entry.transcriptText,
            attribution = entry.attribution,
            signalLabel = entry.signalLabel,
        )
        val result = SearchResult(
            details = listOf(detail),
            textSearchUnavailable = false,
            facetCounts = SearchFacetCounts.EMPTY,
        )

        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                OrtTheme {
                    Box(modifier = Modifier.width(390.dp)) {
                        SearchScreen(
                            input = SearchFilterInput(),
                            result = result,
                            recent = emptyList(),
                            widenSuggestions = null,
                            filtersSheetOpen = false,
                            onOpenFilters = {},
                            onDismissFilters = {},
                            onInputChange = {},
                            onSearch = {},
                            onOpen = {},
                        )
                    }
                    // The same `LogRowViewState` LogScreen's own `LazyColumn` would render it
                    // through — plain `LogRow(state = item.state, onClick = ...)`, no extra
                    // modifier — at the identical width.
                    Box(modifier = Modifier.width(390.dp)) {
                        LogRow(
                            state = referenceState,
                            onClick = {},
                            modifier = Modifier.testTag("log-row-reference"),
                        )
                    }
                }
            }
        }

        val searchRowHeight =
            composeTestRule.onNodeWithTag("search-result-row-TX1").fetchSemanticsNode().size.height
        val referenceHeight =
            composeTestRule.onNodeWithTag("log-row-reference").fetchSemanticsNode().size.height
        assert(searchRowHeight == referenceHeight) {
            "expected SearchScreen's own row to measure identically to a bare LogRow at the same " +
                "width/font scale (search=${searchRowHeight}px, reference=${referenceHeight}px) — " +
                "if these ever diverge, this package's own composition of LogRow has drifted from " +
                "LogScreen's, which this test exists to catch"
        }
    }
}
