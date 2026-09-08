package org.ort.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.ort.app.ui.components.AttributionMarker
import org.ort.app.ui.data.ReaderTransmissionViewStateMapper
import org.ort.app.ui.data.SearchFilterInput
import org.ort.app.ui.data.SearchResult
import org.ort.app.ui.data.TransmissionListEntryViewState
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/**
 * The "Search" destination (build-plan P15, FR-UI-3): full-text search over the `transcript_fts`
 * index build-plan P5 built and nothing queried until now, with filters for callsign, frequency
 * and date. [SearchFilterParser]/[SearchPolling] own the parsing and the real DAO read; this
 * screen is a pure function of [input] and [result] — `null` result means "no search has run
 * yet", never an empty list standing in for "not searched".
 *
 * One deliberate divergence from `design/canvas/Log.dc.html`, which puts search behind a
 * magnifying-glass icon on the Log screen's own top bar and its filter chips inline: build-plan
 * P15's own scope note asks for "the drawer wiring for those two destinations" (search and
 * threads), so this is reached from the drawer as its own destination instead.
 */
@Composable
public fun SearchScreen(
    input: SearchFilterInput,
    result: SearchResult?,
    onInputChange: (SearchFilterInput) -> Unit,
    onSearch: () -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // A single scrollable Column, not a LazyColumn nested under other siblings: a search result
    // set is small enough to render eagerly, and a LazyColumn placed after other Column children
    // (via fillMaxSize() or weight()) is exactly the layout this project's own testing surfaced as
    // fragile under Robolectric's Compose host — rows placed outside the actual viewport, or a
    // zero-height list when the weighted constraint collapses. One scrollable column has no such
    // failure mode.
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(OrtSpacing.lg),
    ) {
        Text(text = "Search", style = OrtType.titleLarge)
        FilterFields(input = input, onInputChange = onInputChange)
        Text(
            text = "Search",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .padding(top = OrtSpacing.sm, bottom = OrtSpacing.md)
                .clickable(onClick = onSearch)
                .semantics { contentDescription = "Run search" },
        )
        ResultsSection(result = result, onOpen = onOpen)
    }
}

@Composable
private fun FilterFields(input: SearchFilterInput, onInputChange: (SearchFilterInput) -> Unit) {
    Column {
        TextField(
            value = input.text,
            onValueChange = { onInputChange(input.copy(text = it)) },
            label = { Text("Search transcripts") },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search text" },
        )
        TextField(
            value = input.callsign,
            onValueChange = { onInputChange(input.copy(callsign = it)) },
            label = { Text("Callsign") },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Callsign filter" },
        )
        TextField(
            value = input.frequencyMhz,
            onValueChange = { onInputChange(input.copy(frequencyMhz = it)) },
            label = { Text("Frequency (MHz)") },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Frequency filter" },
        )
        TextField(
            value = input.dateUtc,
            onValueChange = { onInputChange(input.copy(dateUtc = it)) },
            label = { Text("Date (YYYY-MM-DD)") },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Date filter" },
        )
    }
}

@Composable
private fun ResultsSection(result: SearchResult?, onOpen: (String) -> Unit) {
    if (result == null) {
        Text(
            text = "Enter a search term or filter, then tap Search",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { contentDescription = "Enter a search term or filter, then tap Search" },
        )
        return
    }
    if (result.textSearchUnavailable) {
        Text(
            text = "Full-text search unavailable right now; only the other filters were applied",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = OrtSpacing.sm),
        )
    }
    if (result.details.isEmpty()) {
        Text(
            text = "No results",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.semantics { contentDescription = "No results" },
        )
        return
    }
    val entries = result.details.map { ReaderTransmissionViewStateMapper.listEntry(it) }
    Column(modifier = Modifier.fillMaxWidth()) {
        entries.forEach { entry -> SearchResultRow(entry = entry, onOpen = onOpen) }
    }
}

@Composable
private fun SearchResultRow(entry: TransmissionListEntryViewState, onOpen: (String) -> Unit) {
    val callsignOrUnidentified = entry.attribution.stationId ?: "Unidentified station"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(entry.id) }
            .semantics(mergeDescendants = true) {
                contentDescription =
                    "Transmission at ${entry.timeLabel} on ${entry.frequencyLabel}, $callsignOrUnidentified"
            }
            .padding(vertical = OrtSpacing.sm),
    ) {
        Column {
            Row {
                AttributionMarker(attribution = entry.attribution)
                Text(text = "  $callsignOrUnidentified", style = OrtType.callsign)
            }
            Text(text = entry.transcriptText, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
