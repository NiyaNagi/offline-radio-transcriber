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
import org.ort.app.ui.data.RejectedFilter
import org.ort.app.ui.data.SearchFilterInput
import org.ort.app.ui.data.SearchResult
import org.ort.app.ui.data.TransmissionListEntryViewState
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType
import org.ort.core.AttributionState
import org.ort.data.Band

/**
 * The "Search" destination (build-plan P15, FR-UI-3; band/attribution-state/rejected filters
 * added by audit F-017): full-text search over the `transcript_fts` index build-plan P5 built and
 * nothing queried until now, with filters for callsign, frequency, date, band, attribution state
 * and rejected/accepted/all. [SearchFilterParser]/[SearchPolling] own the parsing and the real DAO
 * read; this screen is a pure function of [input] and [result] — `null` result means "no search
 * has run yet", never an empty list standing in for "not searched".
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
        BandFilterControl(band = input.band, onInputChange = onInputChange, input = input)
        AttributionStateFilterControl(
            attributionState = input.attributionState,
            onInputChange = onInputChange,
            input = input,
        )
        RejectedFilterControl(rejectedFilter = input.rejectedFilter, onInputChange = onInputChange, input = input)
    }
}

/** A human-readable label for [band]: `HF_160M` -> `"160M"`, `VHF_1_25M` -> `"1.25M"`; `null` reads as "All bands". */
private fun bandLabel(band: Band?): String = band?.name?.substringAfter('_')?.replace('_', '.') ?: "All bands"

/**
 * FR-UI-3's band filter: tapping cycles through "All bands" and every [Band] in declaration
 * order. A cycling text control, rather than a dropdown/menu widget this project has no existing
 * pattern for, matching [SearchScreen]'s own "tap to act" style (see the "Search" action text
 * above) and the accessibility floor (§VII): the current selection is always plain text, never
 * colour-only.
 */
@Composable
private fun BandFilterControl(band: Band?, onInputChange: (SearchFilterInput) -> Unit, input: SearchFilterInput) {
    val options: List<Band?> = listOf(null) + Band.entries
    val next = options[(options.indexOf(band) + 1) % options.size]
    Text(
        text = "Band filter: ${bandLabel(band)}",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = OrtSpacing.sm)
            .clickable { onInputChange(input.copy(band = next)) }
            .semantics { contentDescription = "Band filter: ${bandLabel(band)}" },
    )
}

/** A human-readable label for [state]; `null` reads as "All states". */
private fun attributionStateLabel(state: AttributionState?): String = state?.name ?: "All states"

/** FR-UI-3's attribution-state filter: tapping cycles through "All states" and the four closed states. */
@Composable
private fun AttributionStateFilterControl(
    attributionState: AttributionState?,
    onInputChange: (SearchFilterInput) -> Unit,
    input: SearchFilterInput,
) {
    val options: List<AttributionState?> = listOf(null) + AttributionState.entries
    val next = options[(options.indexOf(attributionState) + 1) % options.size]
    Text(
        text = "Attribution state filter: ${attributionStateLabel(attributionState)}",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = OrtSpacing.sm)
            .clickable { onInputChange(input.copy(attributionState = next)) }
            .semantics { contentDescription = "Attribution state filter: ${attributionStateLabel(attributionState)}" },
    )
}

private fun rejectedFilterLabel(value: RejectedFilter): String = when (value) {
    RejectedFilter.ALL -> "All"
    RejectedFilter.ACCEPTED -> "Accepted"
    RejectedFilter.REJECTED -> "Rejected"
}

/** FR-UI-3's rejected/accepted/all tri-state: tapping cycles All -> Accepted -> Rejected -> All. */
@Composable
private fun RejectedFilterControl(
    rejectedFilter: RejectedFilter,
    onInputChange: (SearchFilterInput) -> Unit,
    input: SearchFilterInput,
) {
    val options = RejectedFilter.entries
    val next = options[(options.indexOf(rejectedFilter) + 1) % options.size]
    Text(
        text = "Accepted/rejected filter: ${rejectedFilterLabel(rejectedFilter)}",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = OrtSpacing.sm)
            .clickable { onInputChange(input.copy(rejectedFilter = next)) }
            .semantics { contentDescription = "Accepted/rejected filter: ${rejectedFilterLabel(rejectedFilter)}" },
    )
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
