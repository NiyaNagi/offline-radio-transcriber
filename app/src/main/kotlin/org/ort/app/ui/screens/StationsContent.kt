package org.ort.app.ui.screens

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.ort.app.ui.data.StationListEntryViewState
import org.ort.app.ui.data.StationPolling
import org.ort.app.ui.data.StationsFilter
import org.ort.app.ui.data.UnidentifiedVoicesSummary

/**
 * The "Stations" destination's polling wrapper (R-070, ui-conformance-plan WP8) — the
 * `*Content.kt` composable `OrtNavHost` (WP3) dispatches `STATIONS` to. Fetches the full list once
 * (WP8's own read path, [StationPolling] — never `ReaderPolling`, WP4's file) and filters
 * client-side per [StationsFilter], since the chips are a display concern, not a second query.
 */
@Composable
public fun StationsContent(context: Context, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    var allStations by remember { mutableStateOf(emptyList<StationListEntryViewState>()) }
    var filter by remember { mutableStateOf(StationsFilter.ALL_TIME) }
    var unidentified by remember { mutableStateOf<UnidentifiedVoicesSummary?>(null) }
    // R-207: `Most heard` sorts by real all-time overs count — a display concern like the chips
    // themselves, so it lives here rather than a second `StationPolling` query.
    var sortMostHeard by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { allStations = StationPolling.listStations(context) }
    LaunchedEffect(filter) {
        unidentified = StationPolling.unidentifiedSummary(context, tonightOnly = filter == StationsFilter.TONIGHT)
    }

    val filtered = when (filter) {
        StationsFilter.TONIGHT -> allStations.filter { it.heardTonight }
        StationsFilter.ALL_TIME -> allStations
        StationsFilter.NAMED -> allStations.filter { it.givenName != null }
        StationsFilter.UNIDENTIFIED -> allStations.filter { it.attribution.stationId == null }
    }
    val visible = if (sortMostHeard) filtered.sortedByDescending { it.transmissionCount } else filtered

    StationsListScreen(
        stations = visible,
        onOpen = onOpen,
        modifier = modifier,
        selectedFilter = filter,
        onFilterSelected = { filter = it },
        unidentified = unidentified,
        // R-207: the subtitle's "N heard all time · M tonight" always names the real total, not
        // the current chip's filtered count — matching `Stations.dc.html`'s own subtitle, which
        // does not change as the chips below it are tapped.
        heardAllTimeCount = allStations.size,
        heardTonightCount = allStations.count { it.heardTonight },
        sortMostHeard = sortMostHeard,
        onToggleSort = { sortMostHeard = !sortMostHeard },
    )
}
