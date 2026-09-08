package org.ort.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.ActivityPatternChart
import org.ort.app.ui.components.AttributionMarker
import org.ort.app.ui.components.AttributionRow
import org.ort.app.ui.components.Badge
import org.ort.app.ui.components.BadgeKind
import org.ort.app.ui.components.DrillInHeader
import org.ort.app.ui.components.EmptyState
import org.ort.app.ui.components.FilterChip
import org.ort.app.ui.components.FilterChipRow
import org.ort.app.ui.components.KeyValueRow
import org.ort.app.ui.components.LOG_FREQ_COLUMN
import org.ort.app.ui.components.LOG_TIME_COLUMN
import org.ort.app.ui.components.SectionHeader
import org.ort.app.ui.components.TextAction
import org.ort.app.ui.data.StationDetailViewState
import org.ort.app.ui.data.StationListBadge
import org.ort.app.ui.data.StationListEntryViewState
import org.ort.app.ui.data.StationsFilter
import org.ort.app.ui.data.TransmissionListEntryViewState
import org.ort.app.ui.data.UnidentifiedVoicesSummary
import org.ort.app.ui.data.pluralize
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType

/**
 * The "Stations" list (R-070, FR-UI-9): `Stations.dc.html`'s chips, column header, per-row
 * [AttributionRow] at 9dp (the station's dominant state tonight), honest count context, `NEW` /
 * `CORRECTED` badges, a given name beside the callsign when one exists, and the trailing "N
 * unidentified voices · M overs" row — a real aggregate, never a station.
 */
@Composable
public fun StationsListScreen(
    stations: List<StationListEntryViewState>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
    selectedFilter: StationsFilter = StationsFilter.ALL_TIME,
    onFilterSelected: (StationsFilter) -> Unit = {},
    unidentified: UnidentifiedVoicesSummary? = null,
    // R-207: the subtitle names the real, unfiltered totals — see `StationsContent`'s own doc
    // comment for why these are passed in rather than derived from `stations` (already filtered).
    heardAllTimeCount: Int = 0,
    heardTonightCount: Int = 0,
    sortMostHeard: Boolean = false,
    onToggleSort: () -> Unit = {},
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(modifier = Modifier.padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Stations", style = OrtType.screenTitle, modifier = Modifier.semantics { heading() })
                Text(
                    text = "${pluralize(heardAllTimeCount, "station")} heard all time · $heardTonightCount tonight",
                    style = OrtType.subtitle,
                    color = OrtColors.textDim,
                    modifier = Modifier.padding(top = OrtSpacing.xs),
                )
            }
            TextAction(
                text = if (sortMostHeard) "Most recent" else "Most heard",
                onClick = onToggleSort,
                modifier = Modifier.testTag("stations-sort-most-heard"),
            )
        }
        FilterChipRow(modifier = Modifier.padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm)) {
            StationsFilter.entries.forEach { filter ->
                FilterChip(
                    label = filterLabel(filter),
                    selected = filter == selectedFilter,
                    onClick = { onFilterSelected(filter) },
                    modifier = Modifier.testTag("stations-filter-${filter.name}"),
                )
            }
        }
        if (stations.isEmpty() && unidentified == null) {
            EmptyState(
                message = "No stations heard yet",
                modifier = Modifier.padding(horizontal = OrtSpacing.lg),
            )
            return@Column
        }
        StationsColumnHeaderRow()
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(stations, key = { it.stationId }) { entry -> StationRow(entry = entry, onOpen = onOpen) }
            if (unidentified != null) {
                item(key = "unidentified") { UnidentifiedVoicesRow(unidentified) }
            }
        }
    }
}

/**
 * `Stations.dc.html`'s own column header (R-207) — a marker-width spacer, `station`, `tonight`
 * (the row's own overs/context column), `last` — never the shared `ColumnHeaderRow`, which is
 * `Rows.dc.html`'s TIME/FREQ/STATION/SIG log-row header and cannot be told to omit the two
 * columns this board never had (V5 @f8430b8 found exactly that mismatch on device).
 */
@Composable
private fun StationsColumnHeaderRow(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
    ) {
        Spacer(modifier = Modifier.width(14.dp))
        Text(text = "station".uppercase(), style = OrtType.columnHeader, color = OrtColors.textDisabled)
        Text(
            text = "tonight".uppercase(),
            style = OrtType.columnHeader,
            color = OrtColors.textDisabled,
            modifier = Modifier.weight(1f).padding(start = OrtSpacing.sm),
        )
        Text(text = "last".uppercase(), style = OrtType.columnHeader, color = OrtColors.textDisabled)
    }
}

private fun filterLabel(filter: StationsFilter): String = when (filter) {
    StationsFilter.TONIGHT -> "Tonight"
    StationsFilter.ALL_TIME -> "All time"
    StationsFilter.NAMED -> "Named"
    StationsFilter.UNIDENTIFIED -> "Unidentified"
}

@Composable
private fun StationRow(entry: StationListEntryViewState, onOpen: (String) -> Unit) {
    val lastHeard = entry.lastHeardLabel?.let { "last heard $it" } ?: "never heard"
    val description = buildString {
        append(entry.label)
        entry.givenName?.let { append(", $it") }
        append(", ${entry.countContext.ifBlank { "${entry.transmissionCount} transmissions" }}")
        append(", $lastHeard")
        entry.badge?.let { append(", ${badgeLabel(it)}") }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = { onOpen(entry.stationId) })
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AttributionRow(attribution = entry.attribution, callsign = entry.label)
        Spacer(modifier = Modifier.width(OrtSpacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.countContext.ifBlank { "${entry.transmissionCount} overs" },
                    style = OrtType.subtitle,
                    color = OrtColors.textTime,
                )
                entry.givenName?.let {
                    Text(text = " · $it", style = OrtType.subtitle, color = OrtColors.textBody)
                }
                entry.badge?.let { badge ->
                    Spacer(modifier = Modifier.width(OrtSpacing.xs))
                    Badge(
                        text = badgeLabel(badge),
                        kind = if (badge == StationListBadge.NEW) BadgeKind.NEW else BadgeKind.CORRECTED,
                    )
                }
            }
        }
        Text(text = entry.lastHeardLabel ?: "—", style = OrtType.timeFreq, color = OrtColors.textFigure)
    }
}

private fun badgeLabel(badge: StationListBadge): String = when (badge) {
    StationListBadge.NEW -> "new"
    StationListBadge.CORRECTED -> "corrected"
}

/** `Stations.dc.html`'s trailing "N unidentified voices · M overs" row — a real aggregate, never a station row. */
@Composable
private fun UnidentifiedVoicesRow(summary: UnidentifiedVoicesSummary, modifier: Modifier = Modifier) {
    val label = if (summary.voiceCount != null) {
        "${summary.voiceCount} unidentified voices · ${summary.overCount} overs"
    } else {
        "unidentified voices · ${summary.overCount} overs"
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm)
            .semantics(mergeDescendants = true) { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.size(5.dp).background(OrtColors.markerUnknown, CircleShape))
        Text(
            text = label,
            style = OrtType.subtitle.copy(fontStyle = FontStyle.Italic),
            color = OrtColors.textLow,
            modifier = Modifier.weight(1f).padding(start = OrtSpacing.sm),
        )
    }
}

// -------------------------------------------------------------------------------------------
// Station detail (R-071) — Station.dc.html.
// -------------------------------------------------------------------------------------------

/**
 * Everything heard from one station, across every session (FR-UI-9), plus its activity pattern
 * (FR-UI-11) — [ActivityPatternChart] renders FR-UI-12's not-heard/not-listening distinction
 * structurally — the R-071 facts table, and `RECENT OVERS` rows that open the over.
 */
@Composable
public fun StationDetailScreen(
    state: StationDetailViewState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenTransmission: (String) -> Unit = {},
    onOpenPattern: () -> Unit = {},
    onOpenIdentity: () -> Unit = {},
    onViewAllOvers: () -> Unit = {},
    // R-017: `OrtNavHost` (WP3) does not render a second header over this drill-in's own — this
    // screen's `DrillInHeader` is the only one drawn — but it does not yet know the operator's
    // real navigation origin either, so it hands nothing down. Defaulted so `OrtNavHost.kt`
    // (WP10 is editing it concurrently) compiles unchanged; WP3 wires the true origin afterwards.
    backLabel: String = "Stations",
) {
    Column(modifier = modifier.fillMaxSize()) {
        DrillInHeader(parentLabel = backLabel, onBack = onBack, onKebab = onOpenIdentity)
        // A single top-level LazyColumn — the header/facts/chart section as its first item, then
        // one item per recent over. A `verticalScroll` Column was tried here first and measured
        // fine, but a `clickable` Row nested inside it silently swallowed its own tap in this
        // host (`assertHasClickAction()` true, the tap injected, `onClick` never ran) — found by
        // testing the click, not by inspection; LazyColumn's own gesture handling does not have
        // this problem (proven by `StationsListScreen`'s own row, identically structured).
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                StationHeaderSection(
                    state = state,
                    onOpenPattern = onOpenPattern,
                    onViewAllOvers = onViewAllOvers,
                )
            }
            item {
                SectionHeader(
                    label = "Recent overs",
                    trailingActionLabel = "All ${state.transmissionCount}",
                    onTrailingAction = onViewAllOvers,
                    modifier = Modifier.padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm),
                )
            }
            if (state.transmissions.isEmpty()) {
                item {
                    EmptyState(
                        message = "No transmissions recorded from this station",
                        modifier = Modifier.padding(horizontal = OrtSpacing.lg),
                    )
                }
            } else {
                items(state.transmissions.asReversed(), key = { it.id }) { entry ->
                    RecentOverRow(entry = entry, onOpen = onOpenTransmission)
                }
            }
        }
    }
}

@Composable
private fun StationHeaderSection(
    state: StationDetailViewState,
    onOpenPattern: () -> Unit,
    onViewAllOvers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(horizontal = OrtSpacing.lg)) {
        // R-208 (register, spec, V5 @f8430b8): the same real, all-time state marker the Stations
        // list row shows belongs beside this screen's own title too.
        Row(verticalAlignment = Alignment.CenterVertically) {
            AttributionMarker(attribution = state.attribution, showConfidence = false)
            Text(
                text = state.label,
                style = OrtType.callsignTitle,
                modifier = Modifier.padding(start = OrtSpacing.sm).semantics { heading() },
            )
            state.givenName?.let {
                Text(
                    text = " · $it",
                    style = OrtType.bodyProse,
                    color = OrtColors.textDim,
                    modifier = Modifier.padding(start = OrtSpacing.xs),
                )
            }
        }
        val subtitle = if (state.contextSentence.isNotBlank()) {
            "${state.contextSentence} · ${pluralize(state.transmissionCount, "transmission")}"
        } else {
            pluralize(state.transmissionCount, "transmission")
        }
        Text(
            text = subtitle,
            style = OrtType.subtitle,
            color = OrtColors.textDim,
            modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.md),
        )

        Column {
            KeyValueRow(
                key = "Overs",
                value = "${state.transmissionCount} all time · ${state.transmissionCountTonight} tonight",
                trailingMarker = { TextAction(text = "Log", onClick = onViewAllOvers) },
            )
            KeyValueRow(
                key = "Attribution",
                value = "${state.confirmedCount} confirmed · ${state.inferredCount} inferred · " +
                    "${state.correctedCount} corrected",
            )
            if (state.frequenciesSummary.isNotBlank()) {
                KeyValueRow(key = "Frequencies", value = state.frequenciesSummary)
            }
            state.firstHeardLabel?.let { KeyValueRow(key = "First heard", value = it) }
            state.lastHeardLabel?.let {
                val withSignal = state.lastHeardSignalLabel?.let { sig -> "$it · $sig" } ?: it
                KeyValueRow(key = "Last heard", value = withSignal)
            }
        }

        Column(modifier = Modifier.padding(top = OrtSpacing.md)) {
            SectionHeader(
                label = "When they are around",
                trailingActionLabel = "By day",
                onTrailingAction = onOpenPattern,
            )
            ActivityPatternChart(
                pattern = state.activityPattern,
                title = "",
                modifier = Modifier.padding(top = OrtSpacing.sm),
            )
            if (state.patternSummarySentence.isNotBlank()) {
                Text(
                    text = state.patternSummarySentence,
                    style = OrtType.cardBody,
                    color = OrtColors.textBody,
                    modifier = Modifier.padding(top = OrtSpacing.sm),
                )
            }
        }
    }
}

@Composable
private fun RecentOverRow(
    entry: TransmissionListEntryViewState,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = "${entry.timeLabel}, ${entry.frequencyLabel}, ${entry.transcriptText}"
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("recent-over-${entry.id}")
            .clickable(role = Role.Button, onClickLabel = "Open this over", onClick = { onOpen(entry.id) })
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm),
    ) {
        Text(
            text = entry.timeLabel,
            style = OrtType.timeFreq,
            color = OrtColors.textTime,
            modifier = Modifier.width(LOG_TIME_COLUMN),
        )
        Text(
            text = entry.frequencyLabel,
            style = OrtType.timeFreq,
            color = OrtColors.textTime,
            modifier = Modifier.width(LOG_FREQ_COLUMN).padding(start = OrtSpacing.sm),
        )
        Text(
            text = entry.transcriptText,
            style = OrtType.transcript,
            color = OrtColors.textSecondary,
            modifier = Modifier.weight(1f).padding(start = OrtSpacing.sm),
        )
    }
}
