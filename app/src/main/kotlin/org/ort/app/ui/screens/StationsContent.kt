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

    LaunchedEffect(Unit) { allStations = StationPolling.listStations(context) }
    LaunchedEffect(filter) {
        unidentified = StationPolling.unidentifiedSummary(context, tonightOnly = filter == StationsFilter.TONIGHT)
    }

    val visible = when (filter) {
        StationsFilter.TONIGHT -> allStations.filter { it.heardTonight }
        StationsFilter.ALL_TIME -> allStations
        StationsFilter.NAMED -> allStations.filter { it.givenName != null }
        StationsFilter.UNIDENTIFIED -> allStations.filter { it.attribution.stationId == null }
    }

    StationsListScreen(
        stations = visible,
        onOpen = onOpen,
        modifier = modifier,
        selectedFilter = filter,
        onFilterSelected = { filter = it },
        unidentified = unidentified,
    )
}
