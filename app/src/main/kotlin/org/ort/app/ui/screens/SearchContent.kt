package org.ort.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import org.ort.app.ui.data.HeardFrequencies
import org.ort.app.ui.data.RecentSearchEntry
import org.ort.app.ui.data.RecentSearches
import org.ort.app.ui.data.SearchFacetCounts
import org.ort.app.ui.data.SearchFacetFilter
import org.ort.app.ui.data.SearchFilterInput
import org.ort.app.ui.data.SearchFilterParser
import org.ort.app.ui.data.SearchPolling
import org.ort.app.ui.data.SearchResult
import org.ort.app.ui.data.SearchWidenSuggestions
import org.ort.app.ui.data.SearchWidenViewState
import org.ort.core.SystemClock

/**
 * The content composable [org.ort.app.ui.navigation.OrtNavHost] dispatches `SEARCH` to
 * (ui-conformance-plan WP7/WP3's nav-host rule — today the host still carries its own inline
 * copy; WP3 deletes that and calls this one). The host keeps [input]/[result] in its own
 * `rememberSaveable` so returning from a result screen restores exactly what was there (R-017) —
 * everything else this screen needs ([recent], the filters-sheet's open/closed state, and the
 * no-results widen suggestions) is derived here, close to the [android.content.Context] it needs,
 * so [SearchScreen] itself stays a pure function of view-state.
 */
@Composable
public fun SearchContent(
    input: SearchFilterInput,
    result: SearchResult?,
    onInputChange: (SearchFilterInput) -> Unit,
    onSearch: () -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
    // R-200: `Search.dc.html`'s header is a back chevron + the inline query field, not the
    // generic drawer/search `ScreenHeader` every other destination gets — `SearchScreen` now
    // draws that chevron itself. Optional, defaulting to a no-op, so this stays source-compatible
    // with `OrtNavHost`'s existing call site until WP3 wires a real "return to the previous
    // destination" callback in and also stops rendering its own `ScreenHeader` above this content
    // for `SEARCH` (see this package's CHANGELOG entry — that second half is WP3's file, outside
    // this package's ownership).
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    var recent by remember { mutableStateOf(emptyList<RecentSearchEntry>()) }
    var widenSuggestions by remember { mutableStateOf<SearchWidenViewState?>(null) }
    var filtersSheetOpen by remember { mutableStateOf(false) }
    var filterFacetCounts by remember { mutableStateOf(SearchFacetCounts.EMPTY) }
    var heardFrequenciesHz by remember { mutableStateOf(emptyList<Long>()) }

    LaunchedEffect(Unit) {
        recent = RecentSearches.list(context)
        // R-203: the frequency/band chips in the filter sheet offer what this corpus actually
        // heard, not a generic fixed band table — computed once, independent of the current
        // filters, since this is what helps *pick* a filter, not a reflection of one already set.
        heardFrequenciesHz = HeardFrequencies.list(context)
    }

    // R-202 (halt): the filter sheet's own attribution/include counts and `Show N overs` must
    // reflect the real corpus at the *current* text/callsign/band/time filters, not whatever
    // `SearchResult.facetCounts` a previous `onSearch` happened to leave behind (`EMPTY` before
    // any search has ever run — the "every count reads 0 at default filters on a non-empty corpus"
    // bug). Recomputed live, from `SearchPolling.facetCounts`, whenever the sheet opens or one of
    // those non-facet filters changes while it is open.
    LaunchedEffect(
        filtersSheetOpen,
        input.text,
        input.callsign,
        input.frequencyMhz,
        input.band,
        input.timeFilter,
        input.rangeFromLocal,
        input.rangeToLocal,
    ) {
        if (filtersSheetOpen) {
            val params = SearchFilterParser.parse(input, SystemClock.wallMillis())
            filterFacetCounts = SearchPolling.facetCounts(context, params)
        }
    }

    LaunchedEffect(result) {
        val current = result
        if (current == null) {
            widenSuggestions = null
            return@LaunchedEffect
        }
        val term = RecentSearches.primaryTermFor(input)
        if (term != null) {
            RecentSearches.record(context, term, current.details.size)
            recent = RecentSearches.list(context)
        }
        widenSuggestions = if (!current.textSearchUnavailable && current.details.isEmpty()) {
            val params = SearchFilterParser.parse(input, SystemClock.wallMillis())
            SearchWidenSuggestions.build(context, input, params, SearchFacetFilter.from(input), current.facetCounts)
        } else {
            null
        }
    }

    SearchScreen(
        input = input,
        result = result,
        recent = recent,
        widenSuggestions = widenSuggestions,
        filtersSheetOpen = filtersSheetOpen,
        filterFacetCounts = filterFacetCounts,
        heardFrequenciesHz = heardFrequenciesHz,
        onOpenFilters = { filtersSheetOpen = true },
        onDismissFilters = { filtersSheetOpen = false },
        onInputChange = onInputChange,
        onSearch = onSearch,
        onOpen = onOpen,
        onBack = onBack,
        modifier = modifier,
    )
}
