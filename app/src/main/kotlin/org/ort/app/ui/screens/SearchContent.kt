package org.ort.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import org.ort.app.ui.data.RecentSearchEntry
import org.ort.app.ui.data.RecentSearches
import org.ort.app.ui.data.SearchFacetFilter
import org.ort.app.ui.data.SearchFilterInput
import org.ort.app.ui.data.SearchFilterParser
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
) {
    val context = LocalContext.current
    var recent by remember { mutableStateOf(emptyList<RecentSearchEntry>()) }
    var widenSuggestions by remember { mutableStateOf<SearchWidenViewState?>(null) }
    var filtersSheetOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        recent = RecentSearches.list(context)
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
        onOpenFilters = { filtersSheetOpen = true },
        onDismissFilters = { filtersSheetOpen = false },
        onInputChange = onInputChange,
        onSearch = onSearch,
        onOpen = onOpen,
        modifier = modifier,
    )
}
