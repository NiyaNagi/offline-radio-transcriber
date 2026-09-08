package org.ort.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.Banner
import org.ort.app.ui.components.BannerTone
import org.ort.app.ui.components.FilterChip
import org.ort.app.ui.components.FilterChipRow
import org.ort.app.ui.components.LogRow
import org.ort.app.ui.components.LogRowBadge
import org.ort.app.ui.components.LogRowViewState
import org.ort.app.ui.components.OrtIcons
import org.ort.app.ui.components.PrimaryButton
import org.ort.app.ui.data.ReaderTransmissionViewStateMapper
import org.ort.app.ui.data.RecentSearchEntry
import org.ort.app.ui.data.SearchFacetCounts
import org.ort.app.ui.data.SearchFilterInput
import org.ort.app.ui.data.SearchResult
import org.ort.app.ui.data.SearchTimeFilter
import org.ort.app.ui.data.SearchWidenViewState
import org.ort.app.ui.data.TransmissionListEntryViewState
import org.ort.app.ui.data.label
import org.ort.app.ui.data.prose
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType
import org.ort.core.AttributionState
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The "Search" destination (build-plan P15, FR-UI-3; audit R-060..R-065): a full rebuild against
 * `Search.dc.html`/`Search-Filters.dc.html`/`Search-Results.dc.html`/`Search-Empty.dc.html`/
 * `Search-Unavailable.dc.html`. Five states, a pure function of [input]/[result] plus the two
 * small pieces of derived state its caller ([org.ort.app.ui.screens.SearchContent]) supplies —
 * [recent] (I/O: an app-private store) and [widenSuggestions] (I/O: computed only once a search
 * comes back empty) — this screen itself does no I/O and holds no `Context`.
 *
 * R-060 (halt): the old screen said "Search" three times — the destination header (not this
 * screen's concern), a duplicate screen title, and an unstyled `Search` action text
 * indistinguishable from a filter label. This screen renders no screen title at all (the header
 * already carries "Search"), and the run action is the keyboard's own search action; a
 * [PrimaryButton]-styled `Search` only appears once [input.text] is non-blank.
 */
@Suppress("LongParameterList") // every param is independently meaningful view-state/callback.
@Composable
public fun SearchScreen(
    input: SearchFilterInput,
    result: SearchResult?,
    recent: List<RecentSearchEntry>,
    widenSuggestions: SearchWidenViewState?,
    filtersSheetOpen: Boolean,
    onOpenFilters: () -> Unit,
    onDismissFilters: () -> Unit,
    onInputChange: (SearchFilterInput) -> Unit,
    onSearch: () -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = OrtSpacing.lg),
        ) {
            SearchHeaderRow(input = input, result = result, onInputChange = onInputChange, onSearch = onSearch)
            QuickFilterChipsRow(
                input = input,
                onInputChange = onInputChange,
                onSearch = onSearch,
                onOpenFilters = onOpenFilters,
            )

            when {
                result == null -> InitialState(
                    recent = recent,
                    onInputChange = onInputChange,
                    onSearch = onSearch,
                    input = input,
                )
                result.textSearchUnavailable -> UnavailableState(
                    input = input,
                    result = result,
                    onSearch = onSearch,
                    onOpen = onOpen,
                )
                result.details.isEmpty() -> EmptyState(
                    widenSuggestions = widenSuggestions,
                    onInputChange = onInputChange,
                    onSearch = onSearch,
                    input = input,
                )
                else -> ResultsState(result = result, onOpen = onOpen)
            }
        }

        if (filtersSheetOpen) {
            FiltersSheetOverlay(
                input = input,
                result = result,
                onInputChange = onInputChange,
                onSearch = onSearch,
                onDismissFilters = onDismissFilters,
            )
        }
    }
}

// -------------------------------------------------------------------------------------------
// Query field + quick chips — present in every state.
// -------------------------------------------------------------------------------------------

@Composable
private fun SearchHeaderRow(
    input: SearchFilterInput,
    result: SearchResult?,
    onInputChange: (SearchFilterInput) -> Unit,
    onSearch: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
    ) {
        val mono = isCallsignLike(input.text) || isFrequencyLike(input.text)
        val struckThrough = result?.textSearchUnavailable == true
        Box(
            modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .background(OrtColors.bgRaised, RoundedCornerShape(8.dp))
                .border(
                    1.dp,
                    if (struckThrough) OrtColors.bannerAmberBorder else OrtColors.lineStrong,
                    RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 13.dp)
                .testTag("search-query-field"),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Icon(
                    imageVector = OrtIcons.search,
                    contentDescription = null,
                    tint = OrtColors.textFaint,
                    modifier = Modifier.width(15.dp).height(15.dp),
                )
                Box(modifier = Modifier.weight(1f)) {
                    if (input.text.isEmpty()) {
                        Text(
                            text = "Search transcripts, callsigns, frequencies",
                            style = OrtType.control,
                            color = OrtColors.textSignal,
                        )
                    }
                    BasicTextField(
                        value = input.text,
                        onValueChange = { onInputChange(input.copy(text = it)) },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = OrtColors.textHigh,
                            fontFamily = if (mono) OrtType.mono else OrtType.sans,
                            fontSize = OrtType.control.fontSize,
                            textDecoration = if (struckThrough) TextDecoration.LineThrough else null,
                        ),
                        cursorBrush = SolidColor(OrtColors.accentGreen),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Search text" },
                    )
                }
                if (input.text.isNotEmpty()) {
                    Icon(
                        imageVector = OrtIcons.dismiss,
                        contentDescription = "Clear search text",
                        tint = OrtColors.textFaint,
                        modifier = Modifier
                            .width(14.dp)
                            .height(14.dp)
                            .clickable(role = Role.Button, onClickLabel = "Clear search text") {
                                onInputChange(input.copy(text = ""))
                            },
                    )
                }
            }
        }
        if (input.text.isNotBlank()) {
            PrimaryButton(
                text = "Search",
                onClick = onSearch,
                modifier = Modifier.testTag("search-run-button").semantics { contentDescription = "Run search" },
            )
        }
    }
}

@Composable
private fun QuickFilterChipsRow(
    input: SearchFilterInput,
    onInputChange: (SearchFilterInput) -> Unit,
    onSearch: () -> Unit,
    onOpenFilters: () -> Unit,
) {
    FilterChipRow(modifier = Modifier.fillMaxWidth().padding(horizontal = OrtSpacing.lg)) {
        FilterChip(
            label = "Filters",
            selected = false,
            onClick = onOpenFilters,
            modifier = Modifier.testTag("search-filters-chip"),
        )
        FilterChip(
            label = SearchTimeFilter.TONIGHT.label(),
            selected = input.timeFilter == SearchTimeFilter.TONIGHT,
            onClick = {
                onInputChange(input.copy(timeFilter = SearchTimeFilter.TONIGHT))
                onSearch()
            },
            modifier = Modifier.testTag("search-tonight-chip"),
        )
        FilterChip(
            label = SearchTimeFilter.ALL.label() + " nights",
            selected = input.timeFilter == SearchTimeFilter.ALL,
            onClick = {
                onInputChange(input.copy(timeFilter = SearchTimeFilter.ALL))
                onSearch()
            },
            modifier = Modifier.testTag("search-all-nights-chip"),
        )
        appliedFilterChips(input).forEach { chip ->
            FilterChip(
                label = chip.label,
                selected = true,
                onClick = onOpenFilters,
                onDismiss = {
                    onInputChange(chip.reset(input))
                    onSearch()
                },
            )
        }
    }
}

private data class AppliedFilterChip(val label: String, val reset: (SearchFilterInput) -> SearchFilterInput)

private fun appliedFilterChips(input: SearchFilterInput): List<AppliedFilterChip> {
    val chips = mutableListOf<AppliedFilterChip>()
    if (input.band != null) {
        chips += AppliedFilterChip(input.band.prose()) { it.copy(band = null) }
    }
    if (input.frequencyMhz.isNotBlank()) {
        chips += AppliedFilterChip(input.frequencyMhz) { it.copy(frequencyMhz = "") }
    }
    if (input.callsign.isNotBlank()) {
        chips += AppliedFilterChip(input.callsign) { it.copy(callsign = "") }
    }
    if (input.attributionStates != AttributionState.entries.toSet()) {
        val label = when (input.attributionStates.size) {
            0 -> "No states"
            1 -> "${input.attributionStates.first().prose()} only"
            else -> "${input.attributionStates.size} states"
        }
        chips += AppliedFilterChip(label) { it.copy(attributionStates = AttributionState.entries.toSet()) }
    }
    if (input.includeRejected) {
        chips += AppliedFilterChip("Rejected included") { it.copy(includeRejected = false) }
    }
    if (!input.includeCorrected) {
        chips += AppliedFilterChip("Corrected excluded") { it.copy(includeCorrected = true) }
    }
    return chips
}

// -------------------------------------------------------------------------------------------
// Initial state — R-060/R-063.
// -------------------------------------------------------------------------------------------

@Composable
private fun InitialState(
    recent: List<RecentSearchEntry>,
    input: SearchFilterInput,
    onInputChange: (SearchFilterInput) -> Unit,
    onSearch: () -> Unit,
) {
    Column {
        if (recent.isNotEmpty()) {
            SearchSectionLabel("Recent")
            recent.forEachIndexed { index, entry ->
                RecentRow(
                    entry = entry,
                    modifier = Modifier.testTag("search-recent-row-$index"),
                    onClick = {
                        val updated = if (isCallsignLike(entry.label)) {
                            input.copy(callsign = entry.label, text = "")
                        } else {
                            input.copy(text = entry.label, callsign = "")
                        }
                        onInputChange(updated)
                        onSearch()
                    },
                )
            }
        }

        SearchSectionLabel("Try")
        listOf(
            "A callsign, or part of one — " to "K7L",
            "A frequency — " to "146.96",
            "Words from a transcript — " to "park activation",
            "A POTA reference — " to "K-4412",
        ).forEachIndexed { index, (prefix, example) ->
            TryHintRow(prefix = prefix, example = example, modifier = Modifier.testTag("search-try-hint-$index"))
        }

        Text(
            text = "Full-text over every transcript on this phone, all nights. Nothing is sent anywhere.",
            style = OrtType.subLine,
            color = OrtColors.textFaint,
            modifier = Modifier.padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.md),
        )
    }
}

@Composable
private fun SearchSectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = OrtType.sectionLabel,
        color = OrtColors.textFaint,
        modifier = Modifier.padding(start = OrtSpacing.lg, end = OrtSpacing.lg, top = OrtSpacing.md, bottom = 4.dp),
    )
}

@Composable
private fun RecentRow(entry: RecentSearchEntry, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val mono = isCallsignLike(entry.label)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = "${entry.label}, ${entry.overCount} overs"
            }
            .padding(horizontal = OrtSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OrtSpacing.md),
    ) {
        Icon(
            imageVector = OrtIcons.recent,
            contentDescription = null,
            tint = OrtColors.textLow,
            modifier = Modifier.width(15.dp).height(15.dp),
        )
        Text(
            text = entry.label,
            style = OrtType.control,
            color = OrtColors.textBody,
            fontFamily = if (mono) OrtType.mono else OrtType.sans,
            modifier = Modifier.weight(1f),
        )
        Text(text = "${entry.overCount} overs", style = OrtType.subLine, color = OrtColors.textLow)
    }
}

@Composable
private fun TryHintRow(prefix: String, example: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = OrtSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = OrtColors.textSecondary)) { append(prefix) }
                withStyle(SpanStyle(color = OrtColors.textBody, fontFamily = OrtType.mono)) { append(example) }
            },
            style = OrtType.subtitle,
        )
    }
}

// -------------------------------------------------------------------------------------------
// Results state — R-063/R-065.
// -------------------------------------------------------------------------------------------

@Composable
private fun ResultsState(result: SearchResult, onOpen: (String) -> Unit) {
    val entries = result.details.map { ReaderTransmissionViewStateMapper.listEntry(it) }
    val grouped = result.details.groupBy { dayLabel(it.startedAtUtcMillis) }
    val nightCount = grouped.size
    val stationCount = result.details.mapNotNull { it.attribution.stationId }.toSet().size

    CountLine(overCount = result.details.size, nightCount = nightCount, stationCount = stationCount)

    var cursor = 0
    grouped.forEach { (day, detailsInDay) ->
        DayHeader(day)
        detailsInDay.forEach { detail ->
            val entry = entries[cursor]
            cursor++
            LogRow(state = entry.toLogRowViewState(), onClick = {
                onOpen(detail.id)
            }, modifier = Modifier.testTag("search-result-row-${detail.id}"))
        }
    }
}

@Composable
private fun CountLine(overCount: Int, nightCount: Int, stationCount: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm)
            .testTag("search-count-line"),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = OrtColors.textHigh, fontWeight = FontWeight.Medium)) {
                    append("$overCount ${if (overCount == 1) "over" else "overs"}")
                }
                withStyle(SpanStyle(color = OrtColors.textDim)) {
                    append(" · $nightCount ${if (nightCount == 1) "night" else "nights"}")
                    append(" · $stationCount ${if (stationCount == 1) "station" else "stations"}")
                }
            },
            style = OrtType.subtitle,
            modifier = Modifier.weight(1f),
        )
        Text(text = "Newest first", style = OrtType.cardBody, color = OrtColors.accentGreen)
    }
}

@Composable
private fun DayHeader(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(OrtColors.bgGroup)
            .drawBehind {
                drawLine(
                    color = OrtColors.lineDefault,
                    start = androidx.compose.ui.geometry.Offset(0f, 0f),
                    end = androidx.compose.ui.geometry.Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .padding(start = OrtSpacing.lg, end = OrtSpacing.lg, top = OrtSpacing.md, bottom = 5.dp),
    ) {
        Text(text = label.uppercase(), style = OrtType.columnHeader, color = OrtColors.textLow)
    }
}

private fun TransmissionListEntryViewState.toLogRowViewState(): LogRowViewState = LogRowViewState(
    id = id,
    timeLabel = timeLabel,
    frequencyLabel = frequencyLabel,
    transcript = transcriptText,
    attribution = attribution,
    signalLabel = signalLabel,
    badge = if (revisionNote != null) LogRowBadge.REVISED else null,
)

private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ROOT)

private fun dayLabel(startedAtUtcMillis: Long): String =
    Instant.ofEpochMilli(startedAtUtcMillis).atZone(ZoneOffset.UTC).toLocalDate().format(DAY_FORMAT)

// -------------------------------------------------------------------------------------------
// Empty state — R-063.
// -------------------------------------------------------------------------------------------

@Composable
private fun EmptyState(
    widenSuggestions: SearchWidenViewState?,
    input: SearchFilterInput,
    onInputChange: (SearchFilterInput) -> Unit,
    onSearch: () -> Unit,
) {
    Column(modifier = Modifier.testTag("search-empty-state")) {
        Text(
            text = "Nothing matched.",
            style = OrtType.bodyProse,
            color = OrtColors.textMuted,
            modifier = Modifier.padding(start = OrtSpacing.lg, end = OrtSpacing.lg, top = OrtSpacing.lg),
        )
        val summary = widenSuggestions?.narrowingSummary
        if (summary != null) {
            Text(
                text = summary,
                style = OrtType.cardBody,
                color = OrtColors.textFaint,
                modifier = Modifier.padding(start = OrtSpacing.lg, end = OrtSpacing.lg, top = 5.dp),
            )
        }

        if (widenSuggestions != null &&
            (widenSuggestions.options.isNotEmpty() || widenSuggestions.similarCallsigns.isNotEmpty())
        ) {
            SearchSectionLabel("Widen")
            widenSuggestions.options.forEach { option ->
                WidenRow(
                    title = option.label,
                    detail = option.detail,
                    modifier = Modifier.testTag("search-widen-option-${option.id}"),
                    onShow = {
                        val updated = when (option.id) {
                            "all_nights" -> input.copy(timeFilter = SearchTimeFilter.ALL)
                            "include_all_states" -> input.copy(
                                attributionStates = AttributionState.entries.toSet(),
                                includeRejected = true,
                                includeCorrected = true,
                            )
                            else -> input
                        }
                        onInputChange(updated)
                        onSearch()
                    },
                )
            }
            if (widenSuggestions.similarCallsigns.isNotEmpty()) {
                WidenRow(
                    title = "Similar callsigns heard",
                    detail = widenSuggestions.similarCallsigns.joinToString(" · ") + " · one edit away",
                    modifier = Modifier.testTag("search-similar-callsigns"),
                    onShow = {
                        onInputChange(input.copy(callsign = widenSuggestions.similarCallsigns.first()))
                        onSearch()
                    },
                )
            }
        }
    }
}

@Composable
private fun WidenRow(title: String, detail: String, modifier: Modifier = Modifier, onShow: () -> Unit) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = OrtSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = OrtType.control, color = OrtColors.textHigh)
            Text(
                text = detail,
                style = OrtType.subLine,
                color = OrtColors.textFaint,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        Box(
            modifier = Modifier
                .heightIn(min = 44.dp)
                .clickable(role = Role.Button, onClick = onShow)
                .semantics { contentDescription = "Show — $title" },
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "Show", style = OrtType.cardBody, color = OrtColors.accentGreen)
        }
    }
}

// -------------------------------------------------------------------------------------------
// Unavailable state — R-063.
// -------------------------------------------------------------------------------------------

@Composable
private fun UnavailableState(
    input: SearchFilterInput,
    result: SearchResult,
    onSearch: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val appliedFilters = appliedFilterChips(input).joinToString(", ") { it.label }
    val body = buildString {
        append("The full-text index could not be searched right now. Your words were not applied.")
        if (appliedFilters.isNotEmpty()) {
            append(" The $appliedFilters filter${if (appliedFilterChips(input).size == 1) "" else "s"} still applied.")
        }
    }
    Banner(
        title = "Text search is unavailable right now",
        body = body,
        tone = BannerTone.DEGRADED,
        primaryActionLabel = "Retry",
        onPrimaryAction = onSearch,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OrtSpacing.lg, vertical = OrtSpacing.sm)
            .testTag("search-unavailable-banner"),
    )
    if (result.details.isNotEmpty()) {
        CountLine(
            overCount = result.details.size,
            nightCount = result.details.groupBy { dayLabel(it.startedAtUtcMillis) }.size,
            stationCount = result.details.mapNotNull { it.attribution.stationId }.toSet().size,
        )
        val entries = result.details.map { ReaderTransmissionViewStateMapper.listEntry(it) }
        result.details.forEachIndexed { index, detail ->
            LogRow(state = entries[index].toLogRowViewState(), onClick = { onOpen(detail.id) })
        }
    }
}

// -------------------------------------------------------------------------------------------
// Filters sheet overlay — guide §6.9: a scrim dims the screen beneath to 22%, tap-outside dismisses.
// -------------------------------------------------------------------------------------------

@Composable
private fun FiltersSheetOverlay(
    input: SearchFilterInput,
    result: SearchResult?,
    onInputChange: (SearchFilterInput) -> Unit,
    onSearch: () -> Unit,
    onDismissFilters: () -> Unit,
) {
    // A Column, not two fillMaxSize() siblings in a Box: the sheet (capped to 85% of the height,
    // guide §6.9's "never taller than the screen minus ~120px") and the scrim each own a distinct
    // region this way — the scrim's clickable area is exactly the strip actually exposed above
    // the sheet, never the area the sheet itself covers. Two overlapping fillMaxSize() elements
    // both claim the same centre point for a tap; the sheet, drawn on top, would always win there
    // regardless of which one the operator meant to tap, so a scrim occupying the sheet's own area
    // is not just untestable but the wrong behaviour on a real device too.
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(OrtColors.bgPage.copy(alpha = 0.78f))
                .clickable(role = Role.Button, onClickLabel = "Dismiss filters", onClick = onDismissFilters)
                .testTag("search-filters-scrim"),
        )
        SearchFiltersSheet(
            input = input,
            facetCounts = result?.facetCounts ?: SearchFacetCounts.EMPTY,
            onInputChange = onInputChange,
            onShowResults = {
                onSearch()
                onDismissFilters()
            },
            onClearAll = {
                onInputChange(
                    input.copy(
                        callsign = "",
                        frequencyMhz = "",
                        band = null,
                        timeFilter = SearchTimeFilter.ALL,
                        rangeFromLocal = "",
                        rangeToLocal = "",
                        attributionStates = AttributionState.entries.toSet(),
                        includeRejected = false,
                        includeCorrected = true,
                    ),
                )
            },
            modifier = Modifier.testTag("search-filters-sheet"),
        )
    }
}

// -------------------------------------------------------------------------------------------
// Shared helpers.
// -------------------------------------------------------------------------------------------

private val CALLSIGN_PATTERN = Regex("^[A-Za-z]{1,2}[0-9][A-Za-z0-9]{1,4}$")

private fun isCallsignLike(text: String): Boolean = CALLSIGN_PATTERN.matches(text.trim())

private fun isFrequencyLike(text: String): Boolean = text.trim().toDoubleOrNull() != null
