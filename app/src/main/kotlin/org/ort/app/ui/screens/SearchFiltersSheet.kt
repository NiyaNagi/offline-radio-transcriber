package org.ort.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.ort.app.ui.components.AttributionMarker
import org.ort.app.ui.components.CheckboxRow
import org.ort.app.ui.components.FilterChip
import org.ort.app.ui.components.FilterChipRow
import org.ort.app.ui.components.PrimaryButton
import org.ort.app.ui.components.Sheet
import org.ort.app.ui.data.SearchFacetCounts
import org.ort.app.ui.data.SearchFacetFilter
import org.ort.app.ui.data.SearchFilterInput
import org.ort.app.ui.data.SearchTimeFilter
import org.ort.app.ui.data.label
import org.ort.app.ui.data.prose
import org.ort.app.ui.theme.OrtColors
import org.ort.app.ui.theme.OrtSpacing
import org.ort.app.ui.theme.OrtType
import org.ort.core.Attribution
import org.ort.core.AttributionState
import org.ort.data.Band

/**
 * `Search-Filters.dc.html` (R-061/R-062/R-064): every closed set here is a visible list the
 * operator can see and tap directly — chips for frequency/band and time, checkboxes with real
 * counts for attribution and include, a genuine from/to pair for [SearchTimeFilter.RANGE]
 * (FR-UI-3's time **range**, R-062). The tap-to-cycle text controls this replaces are gone
 * entirely (guide §6.11, `Controls.dc.html`'s closed-set rule). Prose labels only (R-064) — never
 * a raw enum name.
 *
 * A pure function of [input]/[facetCounts]: every count shown, and the `Show N overs` total, is
 * computed from [facetCounts] (already fetched by the last search that ran with the current
 * text/callsign/band/time filters) — never re-queried per tap, and never fabricated (guide §9).
 */
@Composable
public fun SearchFiltersSheet(
    input: SearchFilterInput,
    facetCounts: SearchFacetCounts,
    onInputChange: (SearchFilterInput) -> Unit,
    onShowResults: () -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // guide §6.9: "a sheet is never taller than the screen minus 120px — past that, it is a
    // screen." Six sections (callsign, frequency/band, time, four attribution rows, two include
    // rows, the action button) genuinely exceed that on real content, so the whole sheet —
    // including `Sheet`'s own title/`Clear all` row, not just this composable's own content — has
    // to scroll as one unit. `Sheet` (WP2) has no scroll of its own (by design: it is the static
    // surface, guide's own words, "not the scaffold around it"), so the scrollable container wraps
    // the `Sheet(...)` call itself here, at the one call site that needs it, rather than being
    // added to the shared component. `fillMaxHeight(0.85f)` is the same "never taller than the
    // screen minus ~120px" cap in fraction form (120/844 ≈ 0.14, so 0.85 leaves slightly more) —
    // capped, not `fillMaxSize()`, so the scrim above the sheet stays visible and tappable to
    // dismiss (an uncapped scrollable Column expands to the full available height and silently
    // covers the scrim, swallowing its tap).
    Column(modifier = modifier.fillMaxHeight(0.85f).verticalScroll(rememberScrollState())) {
        Sheet(title = "Filters", onClearAll = onClearAll) {
            Column(modifier = Modifier.padding(top = OrtSpacing.xs)) {
                CallsignSection(input, onInputChange)
                FrequencyAndBandSection(input, onInputChange)
                TimeSection(input, onInputChange)
                AttributionSection(input, facetCounts, onInputChange)
                IncludeSection(input, facetCounts, onInputChange)
                ShowResultsButton(input, facetCounts, onShowResults)
            }
        }
    }
}

@Composable
private fun CallsignSection(input: SearchFilterInput, onInputChange: (SearchFilterInput) -> Unit) {
    SectionLabel("Callsign")
    MonoField(
        value = input.callsign,
        onValueChange = { onInputChange(input.copy(callsign = it)) },
        hint = "prefix match",
        contentDescription = "Callsign prefix filter",
        modifier = Modifier.padding(top = OrtSpacing.sm, bottom = OrtSpacing.xs),
    )
}

@Composable
private fun FrequencyAndBandSection(input: SearchFilterInput, onInputChange: (SearchFilterInput) -> Unit) {
    SectionLabel("Frequency and band", topPadding = OrtSpacing.md)
    FilterChipRow(modifier = Modifier.padding(top = OrtSpacing.sm, bottom = OrtSpacing.xs).fillMaxWidth()) {
        FilterChip(label = "All", selected = input.band == null, onClick = { onInputChange(input.copy(band = null)) })
        Band.entries.forEach { band ->
            FilterChip(
                label = band.prose(),
                selected = input.band == band,
                onClick = { onInputChange(input.copy(band = band)) },
            )
        }
    }
    MonoField(
        value = input.frequencyMhz,
        onValueChange = { onInputChange(input.copy(frequencyMhz = it)) },
        hint = "exact MHz",
        contentDescription = "Exact frequency filter",
        modifier = Modifier.padding(top = OrtSpacing.xs, bottom = OrtSpacing.xs),
    )
}

@Composable
private fun TimeSection(input: SearchFilterInput, onInputChange: (SearchFilterInput) -> Unit) {
    SectionLabel("Time", topPadding = OrtSpacing.md)
    FilterChipRow(modifier = Modifier.padding(top = OrtSpacing.sm, bottom = OrtSpacing.xs).fillMaxWidth()) {
        SearchTimeFilter.entries.forEach { option ->
            FilterChip(
                label = option.label(),
                selected = input.timeFilter == option,
                onClick = { onInputChange(input.copy(timeFilter = option)) },
            )
        }
    }
    if (input.timeFilter == SearchTimeFilter.RANGE) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = OrtSpacing.xs),
            horizontalArrangement = Arrangement.spacedBy(OrtSpacing.sm),
        ) {
            MonoField(
                value = input.rangeFromLocal,
                onValueChange = { onInputChange(input.copy(rangeFromLocal = it)) },
                hint = "from",
                contentDescription = "Range start",
                modifier = Modifier.weight(1f),
            )
            MonoField(
                value = input.rangeToLocal,
                onValueChange = { onInputChange(input.copy(rangeToLocal = it)) },
                hint = "to",
                contentDescription = "Range end",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun AttributionSection(
    input: SearchFilterInput,
    facetCounts: SearchFacetCounts,
    onInputChange: (SearchFilterInput) -> Unit,
) {
    SectionLabel("Attribution", topPadding = OrtSpacing.md)
    Column {
        AttributionCheckboxRow(AttributionState.CONFIRMED, facetCounts.confirmed, input, onInputChange)
        AttributionCheckboxRow(AttributionState.INFERRED, facetCounts.inferred, input, onInputChange)
        AttributionCheckboxRow(AttributionState.AMBIGUOUS, facetCounts.ambiguous, input, onInputChange)
        AttributionCheckboxRow(AttributionState.UNKNOWN, facetCounts.unknown, input, onInputChange)
    }
}

@Composable
private fun IncludeSection(
    input: SearchFilterInput,
    facetCounts: SearchFacetCounts,
    onInputChange: (SearchFilterInput) -> Unit,
) {
    SectionLabel("Include", topPadding = OrtSpacing.md)
    Column {
        CheckboxRow(
            label = "Rejected segments",
            checked = input.includeRejected,
            onCheckedChange = { onInputChange(input.copy(includeRejected = it)) },
            count = facetCounts.rejectedCount.toString(),
        )
        CheckboxRow(
            label = "Corrected by you",
            checked = input.includeCorrected,
            onCheckedChange = { onInputChange(input.copy(includeCorrected = it)) },
            count = facetCounts.correctedCount.toString(),
        )
    }
}

@Composable
private fun ShowResultsButton(input: SearchFilterInput, facetCounts: SearchFacetCounts, onShowResults: () -> Unit) {
    val previewCount = facetCounts.countMatching(SearchFacetFilter.from(input))
    PrimaryButton(
        text = "Show $previewCount ${if (previewCount == 1) "over" else "overs"}",
        onClick = onShowResults,
        modifier = Modifier.fillMaxWidth().padding(top = OrtSpacing.md),
    )
}

@Composable
private fun AttributionCheckboxRow(
    state: AttributionState,
    count: Int,
    input: SearchFilterInput,
    onInputChange: (SearchFilterInput) -> Unit,
) {
    val checked = state in input.attributionStates
    CheckboxRow(
        label = state.prose(),
        checked = checked,
        onCheckedChange = { on ->
            val updated = if (on) input.attributionStates + state else input.attributionStates - state
            onInputChange(input.copy(attributionStates = updated))
        },
        count = count.toString(),
        leading = { AttributionMarker(attribution = previewAttribution(state), showConfidence = false) },
    )
}

/** A placeholder [Attribution] carrying only [state] — used to draw the shape via the shared
 * [AttributionMarker] component (never re-drawn locally) with `showConfidence = false`, which
 * renders the shape alone regardless of the placeholder station/confidence values below. */
private fun previewAttribution(state: AttributionState): Attribution = when (state) {
    // `confirmed`/`inferred` require a non-blank station id — this placeholder is never rendered
    // ([AttributionMarker] draws only the shape and, when [showConfidence] is true, the
    // confidence chip; the station id itself has no render path in that composable).
    AttributionState.CONFIRMED -> Attribution.confirmed("preview", 0.0)
    AttributionState.INFERRED -> Attribution.inferred("preview", 0.0)
    AttributionState.AMBIGUOUS -> Attribution.ambiguous()
    AttributionState.UNKNOWN -> Attribution.unknown()
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier, topPadding: Dp = OrtSpacing.sm) {
    Text(
        text = text.uppercase(),
        style = OrtType.sectionLabel,
        color = OrtColors.textFaint,
        modifier = modifier.padding(top = topPadding),
    )
}

/** `Search-Filters.dc.html`'s `.fld`: `bg/screen` on the sheet's `bg/raised`, `line/strong` border,
 * 8px radius, 44dp tall, mono value, a dim trailing/leading [hint]. Used for every free-text field
 * in the sheet (callsign prefix, exact frequency, the range from/to pair) — never a bare
 * Material `TextField`, which does not match the artboard's inset look. */
@Composable
private fun MonoField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(OrtColors.bgScreen, RoundedCornerShape(8.dp))
            .border(1.dp, OrtColors.lineStrong, RoundedCornerShape(8.dp))
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            // The content description belongs on the text field itself, not the wrapping Row: a
            // Row has no RequestFocus/text-input semantics action of its own to merge one into, so
            // a description placed on it instead of the field silently makes the field unreachable
            // by `performTextInput` (found by SearchScreen.kt's own query field, which puts its
            // description directly on the `BasicTextField` — the pattern this now matches).
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = TextStyle(
                    color = OrtColors.textHigh,
                    fontFamily = OrtType.mono,
                    fontSize = OrtType.control.fontSize,
                ),
                singleLine = true,
                cursorBrush = SolidColor(OrtColors.accentGreen),
                modifier = Modifier.semantics { this.contentDescription = contentDescription },
            )
        }
        Text(text = hint, style = OrtType.axis, color = OrtColors.textLow)
    }
}
